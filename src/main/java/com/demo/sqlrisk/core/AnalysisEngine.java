package com.demo.sqlrisk.core;

import com.demo.sqlrisk.diff.ChangeSet;
import com.demo.sqlrisk.diff.CliArgProductionCommitProvider;
import com.demo.sqlrisk.diff.DiffException;
import com.demo.sqlrisk.diff.DiffService;
import com.demo.sqlrisk.diff.GitRepo;
import com.demo.sqlrisk.diff.RepoContentReader;
import com.demo.sqlrisk.diff.RepoContext;
import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.ExtractionOutput;
import com.demo.sqlrisk.extractor.ExtractionService;
import com.demo.sqlrisk.metadata.JdbcMetadataProvider;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.parser.DynamicEvalContext;
import com.demo.sqlrisk.parser.ParseContext;
import com.demo.sqlrisk.parser.ParseOutcome;
import com.demo.sqlrisk.parser.ParsedStatement;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.parser.SqlDialects;
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
import com.demo.sqlrisk.util.Diagnostics;
import com.demo.sqlrisk.util.TextUtils;
import org.eclipse.jgit.revwalk.RevCommit;

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
    private static final String REASON_PREFIX = "  原因: ";

    private final Clock clock;

    public AnalysisEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReportModel execute(RunContext ctx) {
        ExecutorService executor = createExecutor();
        try {
            // P1 配置（已在 cli 层完成）与表元数据加载（v1.8/FR-8：JDBC 一次加载快照，加载后连接即关；
            // 连接失败/权限不足/超时 → MetadataException → exit 2）；metadata 透传至解析与规则阶段
            MetadataProvider metadata = loadMetadata(ctx);

            // P2/P3 模式判定、差异获取与语句提取（数据源恒为提交树：DIFF 两端均提交树 / FULL 遍历 head 提交树，D21/D22）
            RepoExtraction repo = extract(ctx);
            ExtractionOutput extraction = repo.extraction();

            // P5/P6 骨架生成与解析（保守求值 / Lambda 渲染 → 归一化 → Druid → 语句事实）
            List<SkippedItem> skipped = new ArrayList<SkippedItem>(); // P3/P5/P6 的 SKIP
            List<SkippedItem> extractionSkipped = new ArrayList<SkippedItem>();
            List<SkippedItem> parseFailures = new ArrayList<SkippedItem>();
            List<IgnoredItem> ignored = new ArrayList<IgnoredItem>(); // P4 白名单命中
            List<ParsedStatement> parsedStatements = new ArrayList<ParsedStatement>();
            List<ExtractedStatement> extracted = Collections.emptyList();
            int skeletonCount = 0;
            if (extraction != null) {
                extracted = extraction.result().statements();
                extractionSkipped.addAll(extraction.result().skipped());
                skipped.addAll(extractionSkipped);

                // P4 白名单过滤（M8，§13.3）：命中语句不进入 P5 之后任何阶段
                List<ExtractedStatement> accepted =
                        filterWhitelist(extracted, ctx.config().whitelistStatements(), ignored);
                skeletonCount = parseAll(accepted, parseContextOf(extraction, metadata, ctx),
                        parsedStatements, parseFailures);
                skipped.addAll(parseFailures);
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
                    repo.headCommitName(), repo.prodCommitName(), summary, results, ignored, skipped);
        } finally {
            executor.shutdown();
        }
    }

    /** P2/P3 产物：提交名（报告用）+ 提取结果（prod == head 空差异时为 null，§4.3）。 */
    private static final class RepoExtraction {

        private final String headCommitName;
        private final String prodCommitName; // FULL 或未走 DIFF 时为 null
        private final ExtractionOutput extraction; // 空差异时为 null

        RepoExtraction(String headCommitName, String prodCommitName, ExtractionOutput extraction) {
            this.headCommitName = headCommitName;
            this.prodCommitName = prodCommitName;
            this.extraction = extraction;
        }

        String headCommitName() {
            return headCommitName;
        }

        String prodCommitName() {
            return prodCommitName;
        }

        ExtractionOutput extraction() {
            return extraction;
        }
    }

    /** P1：表元数据 JDBC 一次加载（v1.8/FR-8；失败 → MetadataException → exit 2）。 */
    private static MetadataProvider loadMetadata(RunContext ctx) {
        MetadataProvider metadata = JdbcMetadataProvider.load(ctx.config().datasource());
        if (ctx.verbose() && metadata instanceof JdbcMetadataProvider) {
            Diagnostics.warn("[元数据] " + ctx.config().datasource().url() + " 加载 "
                    + ((JdbcMetadataProvider) metadata).tableCount() + " 张表（一次加载，连接已关闭）");
        }
        return metadata;
    }

    /** P2/P3：打开仓库（提交树只读）→ 解析提交 → 按模式提取。 */
    private static RepoExtraction extract(RunContext ctx) {
        GitRepo gitRepo = GitRepo.open(ctx.repoPath());
        try {
            RevCommit head = gitRepo.resolveCommit(ctx.effectiveHeadCommitArg());
            RepoContentReader reader = gitRepo.contentReader(head);
            if (ctx.mode() == Mode.DIFF) {
                return extractDiff(gitRepo, ctx, reader, head);
            }
            ExtractionOutput extraction = ExtractionService.run(Mode.FULL, null,
                    loadCandidates(reader, ".xml"), loadCandidates(reader, ".java"), ctx.config());
            return new RepoExtraction(head.getName(), null, extraction);
        } finally {
            gitRepo.close();
        }
    }

    /** DIFF：生产 commit 由 SPI 提供（FR-1 预留）；prod == head → 空差异（0 条语句，§4.3）。 */
    private static RepoExtraction extractDiff(GitRepo gitRepo, RunContext ctx,
                                              RepoContentReader reader, RevCommit head) {
        String prodArg = new CliArgProductionCommitProvider(ctx.prodCommitArg())
                .provide(new RepoContext(ctx.repoPath()))
                .orElseThrow(() -> new DiffException("DIFF 模式缺少 --prod-commit"));
        RevCommit prod = gitRepo.resolveCommit(prodArg);
        if (prod.getName().equals(head.getName())) {
            return new RepoExtraction(head.getName(), prod.getName(), null);
        }
        ChangeSet changeSet = DiffService.diff(gitRepo, prod, head);
        ExtractionOutput extraction = ExtractionService.run(Mode.DIFF, changeSet,
                loadCandidates(reader, ".xml"), loadCandidates(reader, ".java"), ctx.config());
        return new RepoExtraction(head.getName(), prod.getName(), extraction);
    }

    /** P4 白名单（M8，§13.3）：仅 mapper.xml / 注解参与，Lambda 不参与校验；命中计入 ignored[]。 */
    private static List<ExtractedStatement> filterWhitelist(List<ExtractedStatement> extracted,
                                                            List<String> whitelist,
                                                            List<IgnoredItem> ignored) {
        List<ExtractedStatement> accepted = new ArrayList<ExtractedStatement>();
        for (ExtractedStatement statement : extracted) {
            if (WhitelistFilter.isIgnored(statement, whitelist)) {
                ignored.add(WhitelistFilter.toIgnored(statement));
            } else {
                accepted.add(statement);
            }
        }
        return accepted;
    }

    /** P5/P6：解析全部入选语句；返回骨架总数，解析失败计入 failures。 */
    private static int parseAll(List<ExtractedStatement> accepted, ParseContext parseContext,
                                List<ParsedStatement> parsed, List<SkippedItem> failures) {
        int skeletons = 0;
        for (ExtractedStatement statement : accepted) {
            ParseOutcome outcome = StatementParser.parse(statement, parseContext);
            if (outcome.parsed() != null) {
                parsed.add(outcome.parsed());
                skeletons += outcome.parsed().parsed().size();
            } else {
                failures.add(outcome.skipped());
            }
        }
        return skeletons;
    }

    private static ParseContext parseContextOf(ExtractionOutput extraction, MetadataProvider metadata,
                                               RunContext ctx) {
        return new ParseContext(
                new DynamicEvalContext(extraction.xmlIndex(), extraction.xmlContents()),
                extraction.entityIndex(), metadata,
                SqlDialects.of(ctx.config().dialect())); // v1.8：方言经配置校验后此处取实现
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
        Diagnostics.warn("[提取] 变动语句 " + statements.size() + " 条，SKIP " + skipped.size() + " 条");
        for (ExtractedStatement statement : statements) {
            Diagnostics.warn("[提取] " + statement.statementId()
                    + "  " + statement.file() + ":" + statement.line()
                    + "  (" + statement.changeType()
                    + (statement.changedVia() != null ? ", via " + statement.changedVia() : "") + ")");
            Diagnostics.warn("        " + TextUtils.truncate(
                    TextUtils.collapseWhitespace(statement.rawText()), 100));
        }
        for (SkippedItem item : skipped) {
            Diagnostics.warn("[提取] SKIP " + item.file() + REASON_PREFIX + item.reason());
        }
    }

    /** --verbose 的白名单阶段提示（M8）：仅命中时输出。 */
    private static void logWhitelist(List<IgnoredItem> ignored) {
        if (ignored.isEmpty()) {
            return;
        }
        Diagnostics.warn("[白名单] 忽略 " + ignored.size() + " 条");
        for (IgnoredItem item : ignored) {
            Diagnostics.warn("[白名单] IGNORE " + item.statementId()
                    + "  " + item.file() + ":" + item.line());
        }
    }

    /** --verbose 的解析阶段进度日志（stderr）：骨架与事实摘要 + 解析失败明细。 */
    private static void logParsing(List<ParsedStatement> parsed, int skeletons,
                                   List<SkippedItem> failures) {
        Diagnostics.warn("[解析] 成功 " + parsed.size() + " 条（骨架 " + skeletons + " 个），解析失败 "
                + failures.size() + " 条");
        for (ParsedStatement statement : parsed) {
            if (statement.parsed().isEmpty()) {
                Diagnostics.warn("[解析] " + statement.source().statementId()
                        + "  含 ${} 且解析失败 → 短路候选（M7 按 M8 定级）");
                continue;
            }
            QueryFacts facts = statement.parsed().get(0).facts();
            Diagnostics.warn("[解析] " + statement.source().statementId()
                    + "  kind=" + facts.kind()
                    + " where=" + facts.hasWhere()
                    + " limit=" + facts.hasLimit()
                    + " tables=" + facts.tables());
        }
        for (SkippedItem failure : failures) {
            Diagnostics.warn("[解析] SKIP " + failure.statementId() + REASON_PREFIX + failure.reason());
        }
    }

    /** --verbose 的空骨架归口提示（v1.6）：仅命中时输出。 */
    private static void logEmptySkeletonSkips(List<SkippedItem> skipped) {
        for (SkippedItem item : skipped) {
            Diagnostics.warn("[规则] SKIP " + item.statementId() + "  " + item.file() + ":" + item.line()
                    + REASON_PREFIX + item.reason());
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
        Diagnostics.warn("[规则] 命中 " + total + " 条 finding（高危 " + high + " | 中危 " + medium + " | 低危 " + low + "）");
        for (StatementResult result : results) {
            for (Finding finding : result.findings()) {
                Diagnostics.warn("[规则] [" + finding.ruleId() + "] " + result.meta().statementId()
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
