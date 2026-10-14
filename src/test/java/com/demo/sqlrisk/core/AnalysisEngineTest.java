package com.demo.sqlrisk.core;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.config.CliOverrides;
import com.demo.sqlrisk.config.ConfigLoader;
import com.demo.sqlrisk.diff.DiffException;
import com.demo.sqlrisk.metadata.MetadataException;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import com.demo.sqlrisk.testutil.TestClocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T2.2~T4 自测：空管线报告、commit 解析、P1/P2 阶段行为与错误分支（详设 §13）。 */
class AnalysisEngineTest {

    @TempDir
    Path tempDir;

    private FixtureRepo fixture;
    private Path repoDir;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.resolve("repo");
        fixture = FixtureRepoBuilder.init(repoDir)
                .commit("prod", Collections.singletonMap("mapper/A.xml", xml("v1")))
                .commit("head", Collections.singletonMap("mapper/A.xml", xml("v2")))
                .finish();
    }

    private static String xml(String version) {
        return "<mapper namespace=\"m\"><select id=\"s\">SELECT " + version + "</select></mapper>";
    }

    private RunContext context(String headArg, String prodArg, Path targetDir) {
        return context(headArg, prodArg, targetDir, "jdbc:h2:mem:engine-test;DB_CLOSE_DELAY=-1");
    }

    private RunContext context(String headArg, String prodArg, Path targetDir, String dbUrl) {
        AppConfig config = ConfigLoader.load(new CliOverrides(null, null, dbUrl, "sa", ""), targetDir.toString());
        return new RunContext(targetDir.toString(), headArg, prodArg, config, ".", false, TestClocks.fixed());
    }

    @Test
    void full_mode_produces_empty_report_with_resolved_head() {
        ReportModel report = new AnalysisEngine(TestClocks.fixed()).execute(context(null, null, repoDir));
        assertEquals(Mode.FULL, report.mode());
        assertEquals(fixture.sha("head"), report.headCommit(), "报告 head 为解析后的提交 sha");
        assertNull(report.prodCommit());
        assertEquals(OffsetDateTime.parse("2026-10-14T08:00:00+08:00"), report.generatedAt(),
                "generatedAt 来自注入时钟（固定 +08:00）");
        assertEquals(1, report.summary().total(), "M7 起 results 含全部已分析语句");
        assertEquals(0, report.summary().high() + report.summary().medium() + report.summary().low(), "无规则命中");
        assertEquals(0, report.summary().skip());
        assertEquals(0, report.summary().ignored());
        assertEquals(1, report.results().size());
        assertNull(report.results().get(0).level(), "无命中：level 为空");
        assertTrue(report.results().get(0).findings().isEmpty());
        assertTrue(report.ignored().isEmpty());
        assertTrue(report.skipped().isEmpty());
    }

    @Test
    void diff_mode_resolves_both_commits() {
        ReportModel report = new AnalysisEngine(TestClocks.fixed())
                .execute(context(null, fixture.sha("prod"), repoDir));
        assertEquals(Mode.DIFF, report.mode());
        assertEquals(fixture.sha("head"), report.headCommit());
        assertEquals(fixture.sha("prod"), report.prodCommit());
    }

    @Test
    void prod_equals_head_yields_empty_diff_without_error() {
        ReportModel report = new AnalysisEngine(TestClocks.fixed())
                .execute(context(fixture.sha("head"), fixture.sha("head"), repoDir));
        assertEquals(Mode.DIFF, report.mode());
        assertEquals(0, report.summary().total(), "空差异：0 条语句正常结束");
    }

    @Test
    void explicit_head_commit_selects_that_tree() {
        ReportModel report = new AnalysisEngine(TestClocks.fixed())
                .execute(context(fixture.sha("prod"), null, repoDir));
        assertEquals(Mode.FULL, report.mode());
        assertEquals(fixture.sha("prod"), report.headCommit(), "指定 head-commit 后按该提交树全量");
    }

    @Test
    void invalid_prod_commit_fails_with_exit_2() {
        DiffException e = assertThrows(DiffException.class, () -> new AnalysisEngine(TestClocks.fixed())
                .execute(context(null, "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef", repoDir)));
        assertEquals(2, e.exitCode());
    }

    @Test
    void metadata_load_failure_fails_fast_with_exit_2() {
        // v1.8/FR-8：数据库不可用（未知驱动/连接失败）→ MetadataException → exit 2
        MetadataException e = assertThrows(MetadataException.class, () -> new AnalysisEngine(TestClocks.fixed())
                .execute(context(null, null, repoDir, "jdbc:unknown-subprotocol://127.0.0.1/none")));
        assertEquals(2, e.exitCode());
        assertTrue(e.getMessage().contains("数据库元数据加载失败"), e.getMessage());
    }

    @Test
    void non_git_repo_fails_with_exit_2() throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        DiffException e = assertThrows(DiffException.class, () -> new AnalysisEngine(TestClocks.fixed())
                .execute(context(null, null, plain)));
        assertTrue(e.getMessage().contains("非 git 仓库"));
    }

    @Test
    void extraction_wires_statements_and_skipped_into_report() throws Exception {
        // 提交树中包含结构错误的 xml → P3 提取产生 SKIP（骨架期 summary.skip 已真实）
        Path dir = tempDir.resolve("broken-repo");
        FixtureRepoBuilder.init(dir)
                .commit("prod", Collections.singletonMap("mapper/Broken.xml",
                        "<mapper namespace=\"x\"><select id=\"a\">SELECT 1"))
                .commit("head", Collections.singletonMap("mapper/Ok.xml",
                        "<mapper namespace=\"m\"><select id=\"s\">SELECT 1</select></mapper>"))
                .finish();
        ReportModel report = new AnalysisEngine(TestClocks.fixed()).execute(context(null, null, dir));
        assertEquals(1, report.summary().skip(), "解析失败的 XML → SKIP 计数");
        assertEquals(1, report.skipped().size());
        assertTrue(report.skipped().get(0).reason().contains("解析失败"));
        assertEquals("mapper/Broken.xml", report.skipped().get(0).file());
    }

    @Test
    void statement_parse_failures_are_wired_as_parse_failed_skips() throws Exception {
        // 语句结构完整但 SQL 非法 → P5/P6 解析失败 → SKIP(PARSE_FAILED)
        Path dir = tempDir.resolve("sql-fail-repo");
        FixtureRepoBuilder.init(dir)
                .commit("prod", Collections.singletonMap("mapper/Bad.xml",
                        "<mapper namespace=\"m\"><select id=\"bad\">THIS IS NOT SQL</select></mapper>"))
                .finish();
        ReportModel report = new AnalysisEngine(TestClocks.fixed()).execute(context(null, null, dir));
        assertEquals(1, report.summary().skip());
        assertEquals(1, report.skipped().size());
        assertEquals(SkippedItem.Kind.PARSE_FAILED, report.skipped().get(0).kind());
        assertTrue(report.skipped().get(0).reason().startsWith("解析失败"), report.skipped().get(0).reason());
        assertEquals("m.bad", report.skipped().get(0).statementId());
    }

    @Test
    void rules_produce_findings_end_to_end() throws Exception {
        // UPDATE 无 WHERE → H1 高危 → summary.high=1，退出码将=1（端到端 M7 验收）
        Path dir = tempDir.resolve("rules-repo");
        FixtureRepoBuilder.init(dir)
                .commit("prod", Collections.singletonMap("mapper/U.xml",
                        "<mapper namespace=\"m\"><update id=\"u\">UPDATE t_order SET status = 1</update></mapper>"))
                .finish();
        ReportModel report = new AnalysisEngine(TestClocks.fixed()).execute(context(null, null, dir));
        assertEquals(1, report.summary().total());
        assertEquals(1, report.summary().high());
        assertEquals(0, report.summary().skip());
        assertEquals(RiskLevel.HIGH, report.results().get(0).level());
        assertEquals("H1", report.results().get(0).findings().get(0).ruleId());
        assertTrue(report.results().get(0).findings().get(0).message().contains("缺少 WHERE"));
    }
}
