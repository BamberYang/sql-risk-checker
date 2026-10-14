package com.demo.sqlrisk.core;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.config.ConfigLoader;
import com.demo.sqlrisk.diff.GitRepo;
import com.demo.sqlrisk.metadata.JsonMetadataProvider;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.testutil.CaseInput;
import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import com.demo.sqlrisk.testutil.GoldenCase;
import com.demo.sqlrisk.testutil.GoldenCaseRunner;
import com.demo.sqlrisk.testutil.TestClocks;
import com.demo.sqlrisk.util.SqlRiskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 端到端黄金样例（详细设计 §18.1/§18.2/§18.3，任务 T9.1/T9.2）：
 * 固定 Clock 执行完整管线（配置/元数据/diff/提取/白名单/解析/规则/汇总 → JSON），
 * 与 expected/report.json 全量比对；另覆盖 ac09 错误码、确定性、FULL 工作区隔离。
 */
class AnalysisEngineIT {

    private static final Path CASES_DIR = Paths.get("src/test/resources/cases");

    /** §18.3 用例清单（ac09 为错误分支、无报告产物，由本类专项测试覆盖）。 */
    static final List<String> CASE_IDS = Collections.unmodifiableList(Arrays.asList(
            "ac01-diff-added-modified",
            "ac02-full-mode",
            "ac03-high-rules",
            "ac04-choose-skeletons",
            "ac05-xml-dynamic-tags",
            "ac05b-fragment-propagation",
            "ac06-annotation-lambda",
            "ac07-whitelist",
            "ac08-report-shape",
            "ac11-dollar-shortcircuit",
            "ac12-annotation-wrapper",
            "ac13-lambda-branches",
            "m1-metadata-semantics",
            "skip-parse-failed"));

    static Stream<String> caseIds() throws IOException {
        List<String> dirs = new ArrayList<String>();
        try (Stream<Path> stream = Files.list(CASES_DIR)) {
            Stream<Path> filtered = stream.filter(Files::isDirectory);
            for (Path p : (Iterable<Path>) filtered::iterator) {
                dirs.add(p.getFileName().toString());
            }
        }
        Collections.sort(dirs);
        assertEquals(new TreeSet<String>(CASE_IDS), new TreeSet<String>(dirs), "用例目录与 §18.3 清单一致");
        return dirs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseIds")
    void golden_case(String caseId) throws Exception {
        GoldenCaseRunner.runCase(CASES_DIR.resolve(caseId), AnalysisEngineIT::runPipeline);
    }

    /** 黄金管线：被测全链路（不经 cli），返回 JSON 报告文本。 */
    public static String runPipeline(CaseInput input) throws Exception {
        GoldenCase goldenCase = input.goldenCase();
        Path repoPath = input.repoPath();

        // 元数据为必需输入：用例提供则落盘到仓库根默认路径（引擎自文件系统读取，与提交树无关）
        String metadataJson = goldenCase.metadataJson() != null
                ? goldenCase.metadataJson() : "{\"version\":\"1.0\",\"tables\":[]}";
        Path metadataFile = repoPath.resolve("sql-risk-metadata.json");
        Files.write(metadataFile, metadataJson.getBytes(StandardCharsets.UTF_8));

        String configPath = null;
        if (goldenCase.configYaml() != null) {
            Path configFile = repoPath.resolve("case-config.yaml");
            Files.write(configFile, goldenCase.configYaml().getBytes(StandardCharsets.UTF_8));
            configPath = configFile.toString();
        }

        AppConfig config = ConfigLoader.load(configPath, metadataFile.toString(), repoPath.toString());
        RunContext ctx = new RunContext(repoPath.toString(), null, prodCommitArg(goldenCase, input), config,
                repoPath.toString(), false, TestClocks.fixed());
        ReportModel report = new AnalysisEngine(TestClocks.fixed()).execute(ctx);
        assertSummaryConsistency(report);
        return com.demo.sqlrisk.report.JsonReporter.toJson(report);
    }

    /** args 文件（可含 {prod}/{head} 占位）：仅取 --prod-commit；缺省 → null → FULL 模式。 */
    private static String prodCommitArg(GoldenCase goldenCase, CaseInput input) {
        String args = goldenCase.argsText();
        if (args == null || args.trim().isEmpty()) {
            return null;
        }
        List<String> tokens = Arrays.asList(args.trim().split("\\s+"));
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if ("--prod-commit".equals(tokens.get(i))) {
                return tokens.get(i + 1).replace("{prod}", input.prodSha());
            }
        }
        return null;
    }

    /** 全用例强断言的口径恒等式（§13.4）：total=results.length、级别互斥计数、skip/ignored 对齐。 */
    private static void assertSummaryConsistency(ReportModel report) {
        int high = 0;
        int medium = 0;
        int low = 0;
        for (StatementResult result : report.results()) {
            if (result.level() == RiskLevel.HIGH) {
                high++;
            } else if (result.level() == RiskLevel.MEDIUM) {
                medium++;
            } else if (result.level() == RiskLevel.LOW) {
                low++;
            }
        }
        assertEquals(report.results().size(), report.summary().total(), "total = results.length");
        assertEquals(high, report.summary().high(), "high 计数");
        assertEquals(medium, report.summary().medium(), "medium 计数");
        assertEquals(low, report.summary().low(), "low 计数");
        assertEquals(report.skipped().size(), report.summary().skip(), "skip = skipped.length");
        assertEquals(report.ignored().size(), report.summary().ignored(), "ignored = ignored.length");
    }

    // ---------- ac09：错误码（AC-9，引擎边界；CLI 映射见 SqlRiskCheckerMainTest） ----------

    @Test
    void ac09_error_paths_all_exit_2() throws Exception {
        Path plain = Files.createTempDirectory("ac09-plain-");
        try {
            // 非 git 仓库
            assertExit2(assertThrows(SqlRiskException.class,
                    () -> GitRepo.open(plain.toString())), "非 git 仓库");
            // 元数据缺失
            assertExit2(assertThrows(SqlRiskException.class,
                    () -> JsonMetadataProvider.load(plain.resolve("nope.json"))), "元数据缺失");
        } finally {
            GoldenCaseRunner.deleteRecursively(plain);
        }

        // 元数据格式错误
        Path badMeta = Files.createTempFile("ac09-meta-", ".json");
        Files.write(badMeta, "{ not json".getBytes(StandardCharsets.UTF_8));
        try {
            assertExit2(assertThrows(SqlRiskException.class,
                    () -> JsonMetadataProvider.load(badMeta)), "元数据格式错误");
        } finally {
            Files.deleteIfExists(badMeta);
        }

        // 坏配置（非法白名单模式：含通配符段） + 非法 commit（需真实仓库）
        GoldenCase goldenCase = GoldenCase.load(CASES_DIR.resolve("ac01-diff-added-modified"));
        Path repoDir = Files.createTempDirectory("ac09-repo-");
        try {
            FixtureRepo repo = FixtureRepoBuilder.init(repoDir)
                    .commit("prod", goldenCase.initialFiles())
                    .commit("head", goldenCase.changedFiles(), goldenCase.deletedFiles())
                    .finish();
            Path badConfig = repoDir.resolve("bad-config.yaml");
            Files.write(badConfig, "whitelist:\n  statements:\n    - \"com.*.mapper\"\n"
                    .getBytes(StandardCharsets.UTF_8));
            assertExit2(assertThrows(SqlRiskException.class,
                    () -> ConfigLoader.load(badConfig.toString(), null, repo.path().toString())),
                    "坏配置：非法白名单模式");
            try (GitRepo gitRepo = GitRepo.open(repo.path().toString())) {
                assertExit2(assertThrows(SqlRiskException.class,
                        () -> gitRepo.resolveCommit("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")),
                        "非法 commit");
            }
        } finally {
            GoldenCaseRunner.deleteRecursively(repoDir);
        }
    }

    private static void assertExit2(SqlRiskException e, String scenario) {
        assertEquals(2, e.exitCode(), scenario + " → exit 2：" + e.getMessage());
    }

    // ---------- T9.2 确定性 + ac02 工作区隔离（D21/D22） ----------

    @Test
    void deterministic_report_across_runs_and_fixture_locations(@TempDir Path tempDir) throws Exception {
        GoldenCase goldenCase = GoldenCase.load(CASES_DIR.resolve("ac05-xml-dynamic-tags"));
        Path repoA = tempDir.resolve("repoA");
        Path repoB = tempDir.resolve("repoB");
        FixtureRepo fixtureA = buildFixture(repoA, goldenCase);
        FixtureRepo fixtureB = buildFixture(repoB, goldenCase);

        assertEquals(fixtureA.sha("head"), fixtureB.sha("head"), "同内容夹具 → 提交 SHA 相同（固定提交身份/时间）");
        String first = runPipeline(new CaseInput(fixtureA, goldenCase));
        String second = runPipeline(new CaseInput(fixtureA, goldenCase));
        String third = runPipeline(new CaseInput(fixtureB, goldenCase));
        assertEquals(first, second, "同一夹具两次运行 → 报告逐字节一致");
        assertEquals(first, third, "不同目录的同内容夹具 → 报告一致（仓库路径不进入报告）");
    }

    @Test
    void working_tree_changes_do_not_affect_diff_or_full_mode(@TempDir Path tempDir) throws Exception {
        // DIFF 模式：未提交改动不影响结果
        Path repoDiffDir = tempDir.resolve("diff");
        GoldenCase diffCase = GoldenCase.load(CASES_DIR.resolve("ac01-diff-added-modified"));
        FixtureRepo diffRepo = buildFixture(repoDiffDir, diffCase);
        String diffBefore = runPipeline(new CaseInput(diffRepo, diffCase));
        writeWorkingTreeChanges(repoDiffDir);
        String diffAfter = runPipeline(new CaseInput(diffRepo, diffCase));
        assertEquals(diffBefore, diffAfter, "DIFF：工作区未提交改动不参与（D21）");

        // FULL 模式：未提交改动不影响结果
        Path repoFullDir = tempDir.resolve("full");
        GoldenCase fullCase = GoldenCase.load(CASES_DIR.resolve("ac02-full-mode"));
        FixtureRepo fullRepo = buildFixture(repoFullDir, fullCase);
        String fullBefore = runPipeline(new CaseInput(fullRepo, fullCase));
        writeWorkingTreeChanges(repoFullDir);
        String fullAfter = runPipeline(new CaseInput(fullRepo, fullCase));
        assertEquals(fullBefore, fullAfter, "FULL：数据源为提交树，不含工作区（D22）");
    }

    private static FixtureRepo buildFixture(Path repoDir, GoldenCase goldenCase) throws Exception {
        return FixtureRepoBuilder.init(repoDir)
                .commit("prod", goldenCase.initialFiles())
                .commit("head", goldenCase.changedFiles(), goldenCase.deletedFiles())
                .finish();
    }

    /** 写入未跟踪/未提交的工作区改动（修改已跟踪文件 + 新增 mapper 文件）。 */
    private static void writeWorkingTreeChanges(Path repoDir) throws IOException {
        Path mapper = repoDir.resolve("src/main/resources/mapper/WorkingCopy.xml");
        Files.createDirectories(mapper.getParent());
        Files.write(mapper, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<mapper namespace=\"com.demo.mapper.WorkingCopy\">\n"
                + "  <update id=\"dropAll\">UPDATE t_order SET status = 0</update>\n"
                + "</mapper>\n").getBytes(StandardCharsets.UTF_8));
        for (Path tracked : Arrays.asList(
                repoDir.resolve("src/main/resources/mapper/OrderMapper.xml"),
                repoDir.resolve("src/main/resources/mapper/A.xml"),
                repoDir.resolve("src/main/resources/mapper/M1.xml"))) {
            if (Files.isRegularFile(tracked)) {
                String content = new String(Files.readAllBytes(tracked), StandardCharsets.UTF_8);
                Files.write(tracked, content.replace("SELECT", "SELECT /* work-in-progress */")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
