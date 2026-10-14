package com.demo.sqlrisk.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T2.1 自测：参数解析与校验矩阵（详设 §5）。 */
class CliArgsTest {

    @Test
    void parses_all_options() {
        CliArgs args = CliArgs.parse(new String[]{
                "--repo", "/repo", "--head-commit", "h1", "--prod-commit", "p1",
                "--config", "/c.yaml", "--output", "/out",
                "--dialect", "mysql", "--db-url", "jdbc:mysql://h/db",
                "--db-username", "u", "--db-password", "p",
                "--format", "json", "--verbose"});
        assertEquals("/repo", args.repoPath());
        assertEquals("h1", args.headCommit());
        assertEquals("p1", args.prodCommit());
        assertEquals("/c.yaml", args.configPath());
        assertEquals("/out", args.outputDir());
        assertEquals("mysql", args.dialect());
        assertEquals("jdbc:mysql://h/db", args.dbUrl());
        assertEquals("u", args.dbUsername());
        assertEquals("p", args.dbPassword());
        assertTrue(args.verbose());
        assertFalse(args.helpRequested());
    }

    @Test
    void defaults_apply_when_only_repo_given() {
        CliArgs args = CliArgs.parse(new String[]{"--repo", "/repo"});
        assertEquals(".", args.outputDir());
        assertFalse(args.verbose());
        assertNull(args.headCommit());
        assertNull(args.prodCommit());
        assertNull(args.configPath());
        assertNull(args.dialect());
        assertNull(args.dbUrl());
        assertNull(args.dbUsername());
        assertNull(args.dbPassword());
    }

    @Test
    void help_wins_even_without_repo() {
        CliArgs args = CliArgs.parse(new String[]{"--help"});
        assertTrue(args.helpRequested());
        assertEquals(0, ExitCode.OK);
    }

    @Test
    void missing_repo_fails() {
        CliUsageException e = assertThrows(CliUsageException.class, () -> CliArgs.parse(new String[]{"--verbose"}));
        assertTrue(e.getMessage().contains("--repo"));
        assertEquals(ExitCode.USAGE_ENV_ERROR, e.exitCode());
    }

    @Test
    void unknown_option_fails() {
        CliUsageException e = assertThrows(CliUsageException.class,
                () -> CliArgs.parse(new String[]{"--repo", "/repo", "--unknown", "x"}));
        assertTrue(e.getMessage().contains("未知参数"));
    }

    @Test
    void duplicate_option_fails() {
        CliUsageException e = assertThrows(CliUsageException.class,
                () -> CliArgs.parse(new String[]{"--repo", "a", "--repo", "b"}));
        assertTrue(e.getMessage().contains("重复参数"));
    }

    @Test
    void missing_value_fails() {
        CliUsageException e = assertThrows(CliUsageException.class,
                () -> CliArgs.parse(new String[]{"--repo"}));
        assertTrue(e.getMessage().contains("缺少值"));
    }

    @Test
    void format_must_be_json() {
        CliUsageException e = assertThrows(CliUsageException.class,
                () -> CliArgs.parse(new String[]{"--repo", "/repo", "--format", "xml"}));
        assertTrue(e.getMessage().contains("--format"));
    }

    @Test
    void usage_text_lists_options_and_exit_codes() {
        String usage = CliArgs.usage();
        assertTrue(usage.contains("--repo"));
        assertTrue(usage.contains("--db-url"));
        assertTrue(usage.contains("--dialect"));
        assertTrue(usage.contains("退出码"));
    }

    @Test
    void removed_metadata_option_fails_with_guidance() {
        CliUsageException e = assertThrows(CliUsageException.class,
                () -> CliArgs.parse(new String[]{"--repo", "/repo", "--metadata", "/m.json"}));
        assertTrue(e.getMessage().contains("已移除"), e.getMessage());
        assertTrue(e.getMessage().contains("--db-url"), e.getMessage());
    }
}
