package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T3.1/T10.1 自测：三层合并、datasource/dialect 校验、密码优先级、已移除键忽略（详设 §6 v1.8）。 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private static final String DB = "jdbc:h2:mem:cfg-test;DB_CLOSE_DELAY=-1";

    private Path writeConfig(String content) throws Exception {
        Path file = tempDir.resolve("conf/cfg.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private AppConfig load(String configPath, String dbUrl) {
        return ConfigLoader.load(new CliOverrides(configPath, null, dbUrl, "sa", ""));
    }

    private AppConfig loadWithEnv(CliOverrides overrides, Map<String, String> env) {
        return ConfigLoader.load(overrides, env);
    }

    @Test
    void defaults_loaded_when_no_user_config() {
        AppConfig config = load(null, DB);

        assertEquals(13, config.ruleSettings().size(), "内置默认应含全部 13 条规则");
        assertEquals(RiskLevel.HIGH, config.rule("H1").get().level());
        assertEquals(RiskLevel.MEDIUM, config.rule("M8").get().level());
        assertEquals(RiskLevel.LOW, config.rule("L4").get().level());
        assertTrue(config.rule("H1").get().enabled());
        assertFalse(config.rule("L3").isPresent(), "缺号/已移除规则不应出现");

        assertEquals("mysql", config.dialect(), "内置默认方言 mysql");
        assertEquals(DB, config.datasource().url(), "CLI --db-url 覆盖");
        assertEquals("sa", config.datasource().username());
        assertEquals(10, config.datasource().connectionTimeoutSeconds(), "缺省连接超时 10s（§17）");
        assertEquals(Arrays.asList("SELECT *", "OR 1=1"), config.lowRiskKeywords());
        assertTrue(config.whitelistStatements().isEmpty());
    }

    @Test
    void user_config_overrides_rule_by_id_and_replaces_lists() throws Exception {
        Path cfg = writeConfig("rules:\n"
                + "  - ruleId: H1\n"
                + "    level: LOW\n"
                + "    enabled: false\n"
                + "low_risk_keywords:\n"
                + "  - \"OR 1=1\"\n"
                + "whitelist:\n"
                + "  statements:\n"
                + "    - \"com.demo.mapper.OrderMapper.selectById\"\n"
                + "    - \"com.demo.mapper.*\"\n"
                + "datasource:\n"
                + "  url: \"jdbc:mysql://127.0.0.1:3306/union_order\"\n"
                + "  username: yaml_user\n"
                + "  connection_timeout_seconds: 5\n"
                + "dialect: mysql\n");
        // 直接以 yaml 提供 datasource（不经过任何 CLI 覆盖，验证 yaml 通道）
        AppConfig config = ConfigLoader.load(new CliOverrides(cfg.toString(), null, null, null, null));

        RuleSetting h1 = config.rule("H1").get();
        assertEquals(RiskLevel.LOW, h1.level());
        assertFalse(h1.enabled());
        assertEquals(RiskLevel.HIGH, config.rule("H3").get().level(), "未提及规则保持默认");
        assertEquals("jdbc:mysql://127.0.0.1:3306/union_order", config.datasource().url(), "yaml datasource.url");
        assertEquals("yaml_user", config.datasource().username());
        assertEquals(5, config.datasource().connectionTimeoutSeconds());
        assertEquals(Collections.singletonList("OR 1=1"), config.lowRiskKeywords());
        assertEquals(2, config.whitelistStatements().size());
    }

    @Test
    void rule_without_level_inherits_default() throws Exception {
        Path cfg = writeConfig("rules:\n  - ruleId: M1\n    enabled: false\n");
        AppConfig config = load(cfg.toString(), DB);
        assertEquals(RiskLevel.MEDIUM, config.rule("M1").get().level());
        assertFalse(config.rule("M1").get().enabled());
    }

    @Test
    void removed_and_unknown_keys_are_ignored() throws Exception {
        Path cfg = writeConfig("conservative_if_always_false: true\n"
                + "bulk_operation_threshold: 1000\n"
                + "metadata_file: ./meta.json\n"
                + "some_unknown_key: x\n"
                + "rules:\n  - ruleId: H2\n    enabled: false\n");
        AppConfig config = load(cfg.toString(), DB);
        assertFalse(config.rule("H2").get().enabled(), "有效覆盖应生效");
        assertTrue(config.rule("H1").get().enabled());
        assertEquals(DB, config.datasource().url(), "metadata_file 已移除且不影响 datasource（v1.8/D24）");
    }

    @Test
    void cli_datasource_overrides_config_file() throws Exception {
        Path cfg = writeConfig("datasource:\n  url: \"jdbc:mysql://yaml-host:3306/db\"\n"
                + "  username: yaml_user\n");
        AppConfig config = ConfigLoader.load(new CliOverrides(cfg.toString(), null, DB, "cli_user", null));
        assertEquals(DB, config.datasource().url(), "CLI --db-url 优先于 yaml");
        assertEquals("cli_user", config.datasource().username(), "CLI --db-username 优先于 yaml");
    }

    @Test
    void password_precedence_cli_then_env_then_yaml() throws Exception {
        Path cfg = writeConfig("datasource:\n  url: \"jdbc:mysql://h/db\"\n  password: yaml_pwd\n");
        Map<String, String> env = new HashMap<String, String>();
        env.put("SQL_RISK_DB_PASSWORD", "env_pwd");

        AppConfig cliWins = loadWithEnv(
                new CliOverrides(cfg.toString(), null, null, null, "cli_pwd"), env);
        assertEquals("cli_pwd", cliWins.datasource().password(), "CLI > 环境变量 > yaml");

        AppConfig envWins = loadWithEnv(
                new CliOverrides(cfg.toString(), null, null, null, null), env);
        assertEquals("env_pwd", envWins.datasource().password(), "环境变量 > yaml");

        AppConfig yamlOnly = loadWithEnv(
                new CliOverrides(cfg.toString(), null, null, null, null),
                Collections.<String, String>emptyMap());
        assertEquals("yaml_pwd", yamlOnly.datasource().password());
    }

    @Test
    void missing_datasource_url_fails() {
        ConfigException e = assertThrows(ConfigException.class, () -> load(null, null));
        assertTrue(e.getMessage().contains("datasource.url"), e.getMessage());
        assertEquals(2, e.exitCode());
    }

    @Test
    void datasource_url_without_jdbc_prefix_fails() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> load(null, "mysql://127.0.0.1:3306/db"));
        assertTrue(e.getMessage().contains("jdbc:"), e.getMessage());
    }

    @Test
    void unsupported_dialect_fails_without_degrading() throws Exception {
        String path = writeConfig("dialect: oracle\n").toString();
        ConfigException e = assertThrows(ConfigException.class, () -> load(path, DB));
        assertTrue(e.getMessage().contains("dialect 暂不支持"), e.getMessage());
        assertTrue(e.getMessage().contains("oracle"), e.getMessage());
    }

    @Test
    void invalid_timeout_fails() throws Exception {
        Path cfg = writeConfig("datasource:\n  url: \"jdbc:mysql://h/db\"\n"
                + "  connection_timeout_seconds: -1\n");
        String path = cfg.toString();
        assertThrows(ConfigException.class, () -> load(path, null));

        String badPath = writeConfig("datasource:\n  url: \"jdbc:mysql://h/db\"\n"
                + "  connection_timeout_seconds: abc\n").toString();
        assertThrows(ConfigException.class, () -> load(badPath, null));
    }

    @Test
    void explicit_missing_config_file_fails() {
        CliOverrides overrides = new CliOverrides(tempDir.resolve("nope.yaml").toString(),
                null, DB, "sa", "");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(overrides));
        assertTrue(e.getMessage().contains("配置文件不存在"));
        assertEquals(2, e.exitCode());
    }

    @Test
    void unknown_rule_id_is_ignored_with_warning() throws Exception {
        Path cfg = writeConfig("rules:\n  - ruleId: XX\n    level: HIGH\n");
        AppConfig config = load(cfg.toString(), DB);
        assertEquals(13, config.ruleSettings().size());
        assertFalse(config.rule("XX").isPresent());
    }

    @Test
    void invalid_level_fails() throws Exception {
        String path = writeConfig("rules:\n  - ruleId: H1\n    level: CRITICAL\n").toString();
        ConfigException e = assertThrows(ConfigException.class, () -> load(path, DB));
        assertTrue(e.getMessage().contains("level"));
    }

    @Test
    void invalid_whitelist_pattern_fails() throws Exception {
        String path = writeConfig("whitelist:\n  statements:\n    - \"com.*.mapper\"\n").toString();
        ConfigException e = assertThrows(ConfigException.class, () -> load(path, DB));
        assertTrue(e.getMessage().contains("白名单模式非法"));
    }

    @Test
    void blank_keyword_fails() throws Exception {
        String path = writeConfig("low_risk_keywords:\n  - \"  \"\n").toString();
        assertThrows(ConfigException.class, () -> load(path, DB));
    }

    @Test
    void broken_yaml_fails() throws Exception {
        String path = writeConfig("rules: [oops\n").toString();
        assertThrows(ConfigException.class, () -> load(path, DB));
    }
}
