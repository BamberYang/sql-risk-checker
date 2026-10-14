package com.demo.sqlrisk.cli;

import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import com.demo.sqlrisk.testutil.TestClocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T2.3~T4 自测：入口装配、退出码、ac09 错误分支（CLI 侧：参数/配置/元数据/仓库/commit）。 */
class SqlRiskCheckerMainTest {

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private PrintStream out;
    private PrintStream err;
    private FixtureRepo fixture;

    @BeforeEach
    void setUp() throws Exception {
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        out = new PrintStream(outBuf, true, "UTF-8");
        err = new PrintStream(errBuf, true, "UTF-8");

        Path repoDir = tempDir.resolve("repo");
        fixture = FixtureRepoBuilder.init(repoDir)
                .commit("prod", Collections.singletonMap("mapper/A.xml",
                        "<mapper namespace=\"m\"><select id=\"s\">SELECT v1</select></mapper>"))
                .commit("head", Collections.singletonMap("mapper/A.xml",
                        "<mapper namespace=\"m\"><select id=\"s\">SELECT v2</select></mapper>"))
                .finish();
        Files.write(repoDir.resolve("sql-risk-metadata.json"),
                "{\"version\":\"1.0\",\"tables\":[]}".getBytes(StandardCharsets.UTF_8));
    }

    private int run(String... argv) {
        return SqlRiskCheckerMain.run(argv, out, err, TestClocks.fixed());
    }

    @Test
    void help_prints_usage_and_exits_0() {
        assertEquals(ExitCode.OK, run("--help"));
        assertTrue(outText().contains("用法"));
        assertTrue(errText().isEmpty());
    }

    @Test
    void missing_args_exit_2_with_error_message() {
        // 注意：空 argv 会落入调试默认参数（见 empty_argv_falls_back_to_debug_defaults），
        // 此处用"仅有可选参数缺 --repo"代表缺参场景
        assertEquals(ExitCode.USAGE_ENV_ERROR, run("--verbose"));
        assertTrue(errText().contains("[错误]"));
        assertTrue(errText().contains("--repo"));
    }

    @Test
    void empty_argv_falls_back_to_debug_defaults() throws Exception {
        ByteArrayOutputStream noticeBuf = new ByteArrayOutputStream();
        PrintStream notice = new PrintStream(noticeBuf, true, "UTF-8");
        String[] effective = SqlRiskCheckerMain.effectiveArgv(new String[0], notice);

        assertSame(SqlRiskCheckerMain.DEBUG_DEFAULT_ARGV, effective, "空 argv → 内置调试默认参数");
        String text = new String(noticeBuf.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(text.contains("[调试] argv 为空，使用内置默认参数"), text);
        assertTrue(text.contains(SqlRiskCheckerMain.DEBUG_DEFAULT_ARGV[1]), "提示包含默认仓库路径：" + text);
    }

    @Test
    void non_empty_argv_passes_through_unchanged() {
        String[] argv = {"--help"};
        assertSame(argv, SqlRiskCheckerMain.effectiveArgv(argv, err), "非空参数原样返回");
        assertTrue(errText().isEmpty(), "非空参数不输出调试提示");
    }

    @Test
    void full_mode_runs_and_exits_0() throws Exception {
        Path outDir = tempDir.resolve("out-full");
        assertEquals(ExitCode.OK, run("--repo", fixture.path().toString(), "--output", outDir.toString()));
        assertTrue(outText().contains("mode=FULL"), "未传 --prod-commit 应为 FULL 模式");
        assertTrue(outText().contains("head=" + abbrev(fixture.sha("head"))), "报告展示解析后的 commit");
        assertTrue(outText().contains("高危 0"));

        // M8：JSON 报告落盘（§12.4）+ 控制台输出报告路径
        Path reportFile = outDir.resolve("sql_risk_report.json");
        assertTrue(Files.exists(reportFile), "M8：报告文件应写入 output 目录");
        assertTrue(outText().contains("报告已生成: "), "控制台输出报告路径");
        String reportJson = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        assertTrue(reportJson.contains("\"headCommit\": \"" + fixture.sha("head") + "\""), reportJson);
        assertTrue(reportJson.contains("\"prodCommit\": null"), "FULL 模式 prodCommit 键保留：" + reportJson);
    }

    @Test
    void diff_mode_reported_when_prod_commit_given() {
        assertEquals(ExitCode.OK, run("--repo", fixture.path().toString(),
                "--prod-commit", fixture.sha("prod"), "--output", tempDir.resolve("out-diff").toString()));
        assertTrue(outText().contains("mode=DIFF"));
        assertTrue(outText().contains("prod=" + abbrev(fixture.sha("prod"))));
    }

    @Test
    void whitelist_hit_is_counted_as_ignored_end_to_end() throws Exception {
        // ac07 口径：白名单命中语句不参与分析，进入 ignored[]，不影响退出码
        Path cfg = tempDir.resolve("whitelist.yaml");
        Files.write(cfg, "whitelist:\n  statements:\n    - m.s\n".getBytes(StandardCharsets.UTF_8));
        Path outDir = tempDir.resolve("out-whitelist");
        assertEquals(ExitCode.OK, run("--repo", fixture.path().toString(),
                "--config", cfg.toString(), "--output", outDir.toString()));
        assertTrue(outText().contains("已忽略 1"), "白名单命中计入统计行：" + outText());

        String reportJson = new String(Files.readAllBytes(outDir.resolve("sql_risk_report.json")),
                StandardCharsets.UTF_8);
        assertTrue(reportJson.contains("\"statementId\": \"m.s\""), reportJson);
        assertTrue(reportJson.contains("\"reason\": \"whitelist\""), reportJson);
        assertTrue(reportJson.contains("\"ignored\": 1"), reportJson);
    }

    @Test
    void unwritable_output_dir_exits_2() throws Exception {
        Path blocker = tempDir.resolve("blocker");
        Files.write(blocker, "not a dir".getBytes(StandardCharsets.UTF_8));
        assertEquals(ExitCode.USAGE_ENV_ERROR, run("--repo", fixture.path().toString(),
                "--output", blocker.toString()));
        assertTrue(errText().contains("报告写出失败"), "stderr: " + errText());
    }

    @Test
    void missing_metadata_exits_2() throws Exception {
        Files.delete(fixture.path().resolve("sql-risk-metadata.json"));
        assertEquals(ExitCode.USAGE_ENV_ERROR, run("--repo", fixture.path().toString()));
        assertTrue(errText().contains("元数据文件不存在"));
    }

    @Test
    void explicit_missing_config_file_exits_2() {
        assertEquals(ExitCode.USAGE_ENV_ERROR,
                run("--repo", fixture.path().toString(), "--config", tempDir.resolve("nope.yaml").toString()));
        assertTrue(errText().contains("配置文件不存在"));
    }

    @Test
    void non_git_repo_exits_2() throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        Files.write(plain.resolve("sql-risk-metadata.json"),
                "{\"version\":\"1.0\",\"tables\":[]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ExitCode.USAGE_ENV_ERROR, run("--repo", plain.toString()));
        assertTrue(errText().contains("非 git 仓库"));
    }

    @Test
    void invalid_prod_commit_exits_2() {
        assertEquals(ExitCode.USAGE_ENV_ERROR, run("--repo", fixture.path().toString(),
                "--prod-commit", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));
        assertTrue(errText().contains("不存在"), "stderr: " + errText());
    }

    @Test
    void verbose_prints_extracted_statement_list() throws Exception {
        // 主入口端到端：diff 模式扫描出变动 SQL（mapper/A.xml 的 SELECT v1 → v2）
        PrintStream originalErr = System.err;
        System.setErr(err); // 引擎提取日志走 System.err（进度日志，stderr 契约）
        try {
            assertEquals(ExitCode.OK, run("--repo", fixture.path().toString(),
                    "--prod-commit", fixture.sha("prod"), "--verbose",
                    "--output", tempDir.resolve("out-verbose").toString()));
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(errText().contains("[提取] 变动语句 1 条"), "stderr: " + errText());
        assertTrue(errText().contains("m.s"), "列出 statementId");
        assertTrue(errText().contains("SELECT v2"), "列出语句原文预览");
        assertTrue(errText().contains("[解析] 成功 1 条"), "P5/P6 接入后可见解析摘要（M6）");
    }

    private static String abbrev(String sha) {
        return sha.substring(0, 7);
    }

    private String outText() {
        return new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
    }

    private String errText() {
        return new String(errBuf.toByteArray(), StandardCharsets.UTF_8);
    }
}
