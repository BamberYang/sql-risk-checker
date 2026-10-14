package com.demo.sqlrisk.core;

import com.demo.sqlrisk.diff.CliArgProductionCommitProvider;
import com.demo.sqlrisk.diff.DiffService;
import com.demo.sqlrisk.diff.GitRepo;
import com.demo.sqlrisk.diff.RepoContentReader;
import com.demo.sqlrisk.diff.RepoContext;
import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.extractor.ExtractionOutput;
import com.demo.sqlrisk.extractor.ExtractionService;
import com.demo.sqlrisk.metadata.JsonMetadataProvider;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.parser.DynamicEvalContext;
import com.demo.sqlrisk.parser.ParseContext;
import com.demo.sqlrisk.parser.ParseOutcome;
import com.demo.sqlrisk.parser.ParsedStatement;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.parser.StatementParser;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.Summary;
import com.demo.sqlrisk.rule.RuleEngine;
import com.demo.sqlrisk.rule.RuleRegistry;
import com.demo.sqlrisk.util.TextUtils;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.file.Paths;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分析引擎（详细设计 §13.2）：P0~P9 阶段编排。
 *
 * <p>阶段接入：P1 元数据(M3) → P2 差异(M4) → P3 提取(M5) → P4 白名单(M8) →
 * P5/P6 解析(M6) → P7 规则(M7) → P8 汇总(M8)；P9 输出由 cli 层完成。</p>
 *
 * <p>并行约定（§16）：P3 文件级、P5~P7 语句级共用同一线程池；结果收集后按
 * (file, line, statementId) 排序后进入汇总，保证确定性。</p>
 */
public final class AnalysisEngine {

    private static final int MAX_POOL_SIZE = 8;

    private final Clock clock;

    public AnalysisEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReportModel execute(RunContext ctx) {
        ExecutorService executor = createExecutor();
        try {
            // P1 配置（已在 cli 层完成）与表元数据加载（必需输入，缺失/格式错误 → exit 2）
            // metadata 自 M6/M7 起透传至解析与规则阶段
            MetadataProvider metadata = JsonMetadataProvider.load(Paths.get(ctx.config().metadataFile()));

            // P2/P3 模式判定、差异获取与语句提取（数据源恒为提交树：DIFF 两端均提交树 / FULL 遍历 head 提交树，D21/D22）
            List<ExtractedStatement> extracted = Collections.emptyList();
            ExtractionOutput extraction = null;
            GitRepo gitRepo = GitRepo.open(ctx.repoPath());
            String headCommitName;
            String prodCommitName = null;
            try {
                RevCommit head = gitRepo.resolveCommit(ctx.effectiveHeadCommitArg());
                headCommitName = head.getName();
                RepoContentReader reader = gitRepo.contentReader(head);

                if (ctx.mode() == Mode.DIFF) {
                    // FR-1 预留 SPI：v1 由 CLI 参数提供生产 commit
                    String prodArg = new CliArgProductionCommitProvider(ctx.prodCommitArg())
                            .provide(new RepoContext(ctx.repoPath()))
                            .orElseThrow(() -> new com.demo.sqlrisk.diff.DiffException("DIFF 模式缺少 --prod-commit"));
                    RevCommit prod = gitRepo.resolveCommit(prodArg);
                    prodCommitName = prod.getName();
                    if (!prod.getName().equals(head.getName())) {
                        com.demo.sqlrisk.diff.ChangeSet changeSet = DiffService.diff(gitRepo, prod, head);
                        extraction = ExtractionService.run(Mode.DIFF, changeSet,
                                loadCandidates(reader, ".xml"), loadCandidates(reader, ".java"), ctx.config());
                    }
                    // prod == head → 空差异：0 条语句，正常结束（§4.3）
                } else {
                    extraction = ExtractionService.run(Mode.FULL, null,
                            loadCandidates(reader, ".xml"), loadCandidates(reader, ".java"), ctx.config());
                }
            } finally {
                gitRepo.close();
            }

            // P5/P6 骨架生成与解析（保守求值 / Lambda 渲染 → 归一化 → Druid → 语句事实）
            List<SkippedItem> skipped = new ArrayList<SkippedItem>(); // P3/P5/P6 的 SKIP
            List<SkippedItem> extractionSkipped = new ArrayList<SkippedItem>();
            List<SkippedItem> parseFailures = new ArrayList<SkippedItem>();
            List<IgnoredItem> ignored = new ArrayList<IgnoredItem>(); // P4 白名单命中
            List<ParsedStatement> parsedStatements = new ArrayList<ParsedStatement>();
            int skeletonCount = 0;
            if (extraction != null) {
                extracted = extraction.result().statements();
                extractionSkipped.addAll(extraction.result().skipped());
                skipped.addAll(extractionSkipped);

                // P4 白名单过滤（M8，§13.3）：仅 mapper.xml / 注解参与，Lambda 不参与校验；
                // 命中语句不进入 P5 之后任何阶段，计入 ignored[]
                List<ExtractedStatement> accepted = new ArrayList<ExtractedStatement>();
                for (ExtractedStatement statement : extracted) {
                    if (WhitelistFilter.isIgnored(statement, ctx.config().whitelistStatements())) {
                        ignored.add(WhitelistFilter.toIgnored(statement));
                    } else {
                        accepted.add(statement);
                    }
                }

                ParseContext parseContext = new ParseContext(
                        new DynamicEvalContext(extraction.xmlIndex(), extraction.xmlContents()),
                        extraction.entityIndex(), metadata);
                for (ExtractedStatement statement : accepted) {
                    ParseOutcome outcome = StatementParser.parse(statement, parseContext);
                    if (outcome.parsed() != null) {
                        parsedStatements.add(outcome.parsed());
                        skeletonCount += outcome.parsed().parsed().size();
                    } else {
                        parseFailures.add(outcome.skipped());
                        skipped.add(outcome.skipped());
                    }
                }
            }
            if (ctx.verbose()) {
                logExtraction(extracted, extractionSkipped);
                logWhitelist(ignored);
                logParsing(parsedStatements, skeletonCount, parseFailures);
            }

            // P7 规则执行（M7）：语句级短路（M8）→ 骨架循环首命中即停 → 归类；
            // 空骨架未命中短路规则 → SKIP（v1.6，§11.2-④）
            RuleRegistry ruleRegistry = RuleRegistry.create(ctx.config());
            RuleEngine.Outcome ruleOutcome = RuleEngine.run(parsedStatements, ruleRegistry, metadata, ctx.config());
            List<StatementResult> results = ruleOutcome.results();
            if (!ruleOutcome.skipped().isEmpty()) {
                skipped.addAll(ruleOutcome.skipped());
            }
            if (ctx.verbose()) {
                logRules(results);
                logEmptySkeletonSkips(ruleOutcome.skipped());
            }

            // P8 汇总（口径 §13.4）：按语句最高命中级别互斥归类；skip 含解析失败；ignored 为白名单
            Summary summary = SummaryCalculator.calc(results, ignored, skipped);

            // P9 输出由 cli 层完成（M8：ConsoleReporter / ReportWriter）
            return new ReportModel(OffsetDateTime.now(clock), ctx.mode(),
                    headCommitName, prodCommitName, summary, results, ignored, skipped);
        } finally {
            executor.shutdown();
        }
    }

    /** 提交树候选内容装载（P3 输入；*.java / *.xml，FR-2 承载形式）。 */
    private static List<CandidateFile> loadCandidates(RepoContentReader reader, final String suffix) {
        List<CandidateFile> out = new ArrayList<CandidateFile>();
        for (String path : reader.listFiles(p -> p.endsWith(suffix))) {
            Optional<String> content = reader.readText(path);
            if (content.isPresent()) {
                out.add(CandidateFile.of(path, content.get()));
            }
        }
        return out;
    }

    /** --verbose 的提取阶段进度日志（stderr，不污染 stdout 报告契约；M8 报告格式不受影响）。 */
    private static void logExtraction(List<ExtractedStatement> statements, List<SkippedItem> skipped) {
        System.err.println("[提取] 变动语句 " + statements.size() + " 条，SKIP " + skipped.size() + " 条");
        for (ExtractedStatement statement : statements) {
            System.err.println("[提取] " + statement.statementId()
                    + "  " + statement.file() + ":" + statement.line()
                    + "  (" + statement.changeType()
                    + (statement.changedVia() != null ? ", via " + statement.changedVia() : "") + ")");
            System.err.println("        " + TextUtils.truncate(
                    TextUtils.collapseWhitespace(statement.rawText()), 100));
        }
        for (SkippedItem item : skipped) {
            System.err.println("[提取] SKIP " + item.file() + "  原因: " + item.reason());
        }
    }

    /** --verbose 的白名单阶段提示（M8）：仅命中时输出。 */
    private static void logWhitelist(List<IgnoredItem> ignored) {
        if (ignored.isEmpty()) {
            return;
        }
        System.err.println("[白名单] 忽略 " + ignored.size() + " 条");
        for (IgnoredItem item : ignored) {
            System.err.println("[白名单] IGNORE " + item.statementId()
                    + "  " + item.file() + ":" + item.line());
        }
    }

    /** --verbose 的解析阶段进度日志（stderr）：骨架与事实摘要 + 解析失败明细。 */
    private static void logParsing(List<ParsedStatement> parsed, int skeletons,
                                   List<SkippedItem> failures) {
        System.err.println("[解析] 成功 " + parsed.size() + " 条（骨架 " + skeletons + " 个），解析失败 "
                + failures.size() + " 条");
        for (ParsedStatement statement : parsed) {
            if (statement.parsed().isEmpty()) {
                System.err.println("[解析] " + statement.source().statementId()
                        + "  含 ${} 且解析失败 → 短路候选（M7 按 M8 定级）");
                continue;
            }
            QueryFacts facts = statement.parsed().get(0).facts();
            System.err.println("[解析] " + statement.source().statementId()
                    + "  kind=" + facts.kind()
                    + " where=" + facts.hasWhere()
                    + " limit=" + facts.hasLimit()
                    + " tables=" + facts.tables());
        }
        for (SkippedItem failure : failures) {
            System.err.println("[解析] SKIP " + failure.statementId() + "  原因: " + failure.reason());
        }
    }

    /** --verbose 的空骨架归口提示（v1.6）：仅命中时输出。 */
    private static void logEmptySkeletonSkips(List<SkippedItem> skipped) {
        for (SkippedItem item : skipped) {
            System.err.println("[规则] SKIP " + item.statementId() + "  " + item.file() + ":" + item.line()
                    + "  原因: " + item.reason());
        }
    }

    /** --verbose 的规则阶段进度日志（stderr）：命中明细。 */
    private static void logRules(List<StatementResult> results) {
        int total = 0;
        int high = 0;
        int medium = 0;
        int low = 0;
        for (StatementResult result : results) {
            for (Finding finding : result.findings()) {
                total++;
                if (finding.level() == RiskLevel.HIGH) {
                    high++;
                } else if (finding.level() == RiskLevel.MEDIUM) {
                    medium++;
                } else {
                    low++;
                }
            }
        }
        System.err.println("[规则] 命中 " + total + " 条 finding（高危 " + high + " | 中危 " + medium + " | 低危 " + low + "）");
        for (StatementResult result : results) {
            for (Finding finding : result.findings()) {
                System.err.println("[规则] [" + finding.ruleId() + "] " + result.meta().statementId()
                        + "  " + finding.message());
            }
        }
    }

    /** 并行执行器（§16）：min(CPU, 8)，守护线程避免阻塞进程退出。 */
    private static ExecutorService createExecutor() {
        int size = Math.min(Runtime.getRuntime().availableProcessors(), MAX_POOL_SIZE);
        return Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "sql-risk-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
