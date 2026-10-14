package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T3.1 自测：三层合并、覆盖语义、已移除键/未知键忽略、错误分支（详设 §6）。 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String content) throws Exception {
        Path file = tempDir.resolve("conf/cfg.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void defaults_loaded_when_no_user_config() {
        AppConfig config = ConfigLoader.load(null, null, tempDir.toString());

        assertEquals(13, config.ruleSettings().size(), "内置默认应含全部 13 条规则");
        assertEquals(RiskLevel.HIGH, config.rule("H1").get().level());
        assertEquals(RiskLevel.MEDIUM, config.rule("M8").get().level());
        assertEquals(RiskLevel.LOW, config.rule("L4").get().level());
        assertTrue(config.rule("H1").get().enabled());
        assertFalse(config.rule("L3").isPresent(), "缺号/已移除规则不应出现");

        assertEquals(Paths.get(tempDir.toString(), "sql-risk-metadata.json").toAbsolutePath().normalize().toString(),
                config.metadataFile(), "内置默认 metadata_file 以仓库根为基准");
        assertEquals(Arrays.asList("SELECT *", "OR 1=1"), config.lowRiskKeywords());
        assertTrue(config.whitelistStatements().isEmpty());
    }

    @Test
    void user_config_overrides_rule_by_id_and_replaces_lists() throws Exception {
        Path cfg = writeConfig("rules:\n"
                + "  - ruleId: H1\n"
                + "    level: LOW\n"
                + "    enabled: false\n"
                + "metadata_file: ./meta.json\n"
                + "low_risk_keywords:\n"
                + "  - \"OR 1=1\"\n"
                + "whitelist:\n"
                + "  statements:\n"
                + "    - \"com.demo.mapper.OrderMapper.selectById\"\n"
                + "    - \"com.demo.mapper.*\"\n");
        AppConfig config = ConfigLoader.load(cfg.toString(), null, tempDir.toString());

        RuleSetting h1 = config.rule("H1").get();
        assertEquals(RiskLevel.LOW, h1.level());
        assertFalse(h1.enabled());
        assertEquals(RiskLevel.HIGH, config.rule("H3").get().level(), "未提及规则保持默认");
        assertEquals(tempDir.resolve("conf/meta.json").toAbsolutePath().normalize().toString(),
                config.metadataFile(), "metadata_file 相对配置文件所在目录解析");
        assertEquals(Collections.singletonList("OR 1=1"), config.lowRiskKeywords());
        assertEquals(2, config.whitelistStatements().size());
    }

    @Test
    void rule_without_level_inherits_default() throws Exception {
        Path cfg = writeConfig("rules:\n  - ruleId: M1\n    enabled: false\n");
        AppConfig config = ConfigLoader.load(cfg.toString(), null, tempDir.toString());
        assertEquals(RiskLevel.MEDIUM, config.rule("M1").get().level());
        assertFalse(config.rule("M1").get().enabled());
    }

    @Test
    void removed_and_unknown_keys_are_ignored() throws Exception {
        Path cfg = writeConfig("conservative_if_always_false: true\n"
                + "bulk_operation_threshold: 1000\n"
                + "some_unknown_key: x\n"
                + "rules:\n  - ruleId: H2\n    enabled: false\n");
        AppConfig config = ConfigLoader.load(cfg.toString(), null, tempDir.toString());
        assertFalse(config.rule("H2").get().enabled(), "有效覆盖应生效");
        assertTrue(config.rule("H1").get().enabled());
    }

    @Test
    void cli_metadata_override_wins_over_config_file() throws Exception {
        Path cfg = writeConfig("metadata_file: ./meta.json\n");
        AppConfig config = ConfigLoader.load(cfg.toString(), "/abs/custom-meta.json", tempDir.toString());
        assertEquals(Paths.get("/abs/custom-meta.json").toAbsolutePath().normalize().toString(),
                config.metadataFile());
    }

    @Test
    void explicit_missing_config_file_fails() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(tempDir.resolve("nope.yaml").toString(), null, tempDir.toString()));
        assertTrue(e.getMessage().contains("配置文件不存在"));
        assertEquals(2, e.exitCode());
    }

    @Test
    void unknown_rule_id_is_ignored_with_warning() throws Exception {
        Path cfg = writeConfig("rules:\n  - ruleId: XX\n    level: HIGH\n");
        AppConfig config = ConfigLoader.load(cfg.toString(), null, tempDir.toString());
        assertEquals(13, config.ruleSettings().size());
        assertFalse(config.rule("XX").isPresent());
    }

    @Test
    void invalid_level_fails() throws Exception {
        Path cfg = writeConfig("rules:\n  - ruleId: H1\n    level: CRITICAL\n");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(cfg.toString(), null, tempDir.toString()));
        assertTrue(e.getMessage().contains("level"));
    }

    @Test
    void invalid_whitelist_pattern_fails() throws Exception {
        Path cfg = writeConfig("whitelist:\n  statements:\n    - \"com.*.mapper\"\n");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(cfg.toString(), null, tempDir.toString()));
        assertTrue(e.getMessage().contains("白名单模式非法"));
    }

    @Test
    void blank_keyword_fails() throws Exception {
        Path cfg = writeConfig("low_risk_keywords:\n  - \"  \"\n");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(cfg.toString(), null, tempDir.toString()));
    }

    @Test
    void broken_yaml_fails() throws Exception {
        Path cfg = writeConfig("rules: [oops\n");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(cfg.toString(), null, tempDir.toString()));
    }
}
