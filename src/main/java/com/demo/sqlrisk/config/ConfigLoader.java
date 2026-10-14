package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.util.Diagnostics;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配置加载（详细设计 §6，v1.8）：三层合并——内置默认（resources/default-config.yaml）
 * &lt; 用户 yaml（--config）&lt; CLI 覆盖（dialect / db-url / db-username / db-password）。
 *
 * <ul>
 *   <li>已移除键（bulk_operation_threshold / conservative_if_always_false / <b>metadata_file</b>）与未知键 → stderr 告警并忽略；</li>
 *   <li>rules[] 按 ruleId 覆盖；level 缺省继承既有值，enabled 缺省 true；未知 ruleId → 告警忽略；</li>
 *   <li>low_risk_keywords / whitelist.statements 提供时整体替换；</li>
 *   <li>datasource 段承载数据库连接（元数据来源，FR-8）；密码优先级 CLI &gt; 环境变量
 *       {@code SQL_RISK_DB_PASSWORD} &gt; yaml（D26）；</li>
 *   <li>datasource.url 必填且须以 {@code jdbc:} 开头；dialect 必须为已支持值（D25）。</li>
 * </ul>
 *
 * <p>签名以 {@link CliOverrides} 携带 CLI 值，保持 config 包不反向依赖 cli 包（§3.3）。</p>
 */
public final class ConfigLoader {

    private static final String DEFAULT_RESOURCE = "/default-config.yaml";
    private static final String PASSWORD_ENV = "SQL_RISK_DB_PASSWORD";
    private static final String KEY_DIALECT = "dialect";
    private static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 10; // §17
    private static final List<String> REMOVED_KEYS =
            Arrays.asList("bulk_operation_threshold", "conservative_if_always_false", "metadata_file");
    private static final List<String> KNOWN_KEYS =
            Arrays.asList("rules", "datasource", KEY_DIALECT, "low_risk_keywords", "whitelist");

    private ConfigLoader() {
    }

    public static AppConfig load(CliOverrides overrides) {
        return load(overrides, System.getenv());
    }

    /**
     * @param overrides CLI 覆盖值（可空字段 = 未提供）
     * @param env       环境变量视图（测试可注入；密码读取 {@code SQL_RISK_DB_PASSWORD}）
     */
    public static AppConfig load(CliOverrides overrides, Map<String, String> env) {
        RawConfig merged = parseResourceDefaults();
        applyRuleOverridesFromDefaults(merged);

        if (overrides.configPath() != null && !overrides.configPath().trim().isEmpty()) {
            Path configFile = Paths.get(overrides.configPath());
            if (!Files.isRegularFile(configFile)) {
                throw new ConfigException("配置文件不存在: " + overrides.configPath());
            }
            RawConfig user = parseYamlFile(configFile, "用户配置 " + overrides.configPath());
            mergeUser(merged, user);
        }

        // CLI 覆盖（D26）
        if (nonBlank(overrides.dialect())) {
            merged.dialect = overrides.dialect().trim();
        }
        if (nonBlank(overrides.dbUrl())) {
            merged.dbUrl = overrides.dbUrl().trim();
        }
        if (nonBlank(overrides.dbUsername())) {
            merged.dbUsername = overrides.dbUsername().trim();
        }
        String password = firstNonBlank(overrides.dbPassword(),
                env == null ? null : env.get(PASSWORD_ENV), merged.dbPassword);

        // 校验（FR-8/D25/D26）
        if (!nonBlank(merged.dbUrl)) {
            throw new ConfigException("datasource.url 未配置（元数据来源为数据库，必需；"
                    + "可用 --db-url 或 yaml datasource.url 提供）");
        }
        if (!merged.dbUrl.startsWith("jdbc:")) {
            throw new ConfigException("datasource.url 必须以 jdbc: 开头: " + merged.dbUrl);
        }
        String dialect = nonBlank(merged.dialect) ? merged.dialect.trim() : "mysql";
        ConfigValidator.validateDialect(dialect);
        int timeout = merged.connectionTimeoutSeconds == null
                ? DEFAULT_CONNECTION_TIMEOUT_SECONDS : merged.connectionTimeoutSeconds;
        if (timeout <= 0) {
            throw new ConfigException("datasource.connection_timeout_seconds 必须为正整数: " + timeout);
        }

        ConfigValidator.validateWhitelistPatterns(merged.whitelistStatements);
        ConfigValidator.validateKeywords(merged.lowRiskKeywords);
        return new AppConfig(merged.rules,
                new DataSourceSetting(merged.dbUrl, merged.dbUsername, password, timeout),
                dialect, merged.lowRiskKeywords, merged.whitelistStatements);
    }

    // ---------- 解析 ----------

    private static RawConfig parseResourceDefaults() {
        InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("内置默认配置缺失: " + DEFAULT_RESOURCE);
        }
        return parseYaml(in, "内置默认配置");
    }

    private static RawConfig parseYamlFile(Path file, String source) {
        try (InputStream in = Files.newInputStream(file)) {
            return parseYaml(in, source);
        } catch (IOException e) {
            throw new ConfigException("配置文件读取失败: " + file + "（" + e.getMessage() + "）");
        }
    }

    @SuppressWarnings("unchecked")
    private static RawConfig parseYaml(InputStream in, String source) {
        Object rootObj;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            rootObj = yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (RuntimeException e) { // YAMLException 等
            throw new ConfigException(source + " 解析失败: " + e.getMessage());
        }
        RawConfig cfg = new RawConfig();
        if (rootObj == null) {
            return cfg; // 空文件 = 无内容
        }
        if (!(rootObj instanceof Map)) {
            throw new ConfigException(source + " 结构非法：顶层必须是映射（键值对）");
        }
        Map<String, Object> root = (Map<String, Object>) rootObj;
        for (String key : root.keySet()) {
            if (REMOVED_KEYS.contains(key)) {
                warn(source + " 中的配置项已移除，忽略: " + key);
            } else if (!KNOWN_KEYS.contains(key)) {
                warn(source + " 中的未知配置项，忽略: " + key);
            }
        }
        cfg.ruleOverrides = parseRules(root.get("rules"), source);
        parseDatasource(root.get("datasource"), cfg, source);
        if (root.get(KEY_DIALECT) != null) {
            cfg.dialect = root.get(KEY_DIALECT).toString().trim();
        }
        parseKeywords(root.get("low_risk_keywords"), source)
                .ifPresent(keywords -> cfg.lowRiskKeywords = keywords);
        parseWhitelist(root.get("whitelist"), source)
                .ifPresent(statements -> cfg.whitelistStatements = statements);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static void parseDatasource(Object value, RawConfig cfg, String source) {
        if (value == null) {
            return; // 未提供 = 不覆盖
        }
        if (!(value instanceof Map)) {
            throw new ConfigException(source + "：datasource 必须是映射");
        }
        Map<String, Object> datasource = (Map<String, Object>) value;
        if (datasource.get("url") != null) {
            cfg.dbUrl = datasource.get("url").toString().trim();
        }
        if (datasource.get("username") != null) {
            cfg.dbUsername = datasource.get("username").toString().trim();
        }
        if (datasource.get("password") != null) {
            cfg.dbPassword = datasource.get("password").toString();
        }
        if (datasource.get("connection_timeout_seconds") != null) {
            Object timeout = datasource.get("connection_timeout_seconds");
            try {
                cfg.connectionTimeoutSeconds = Integer.valueOf(timeout.toString().trim());
            } catch (NumberFormatException e) {
                throw new ConfigException(source + "：datasource.connection_timeout_seconds 必须是整数: " + timeout);
            }
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (nonBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean nonBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<RuleOverride> parseRules(Object rulesObj, String source) {
        List<RuleOverride> out = new ArrayList<RuleOverride>();
        if (rulesObj == null) {
            return out;
        }
        if (!(rulesObj instanceof List)) {
            throw new ConfigException(source + "：rules 必须是列表");
        }
        for (Object item : (List<Object>) rulesObj) {
            if (!(item instanceof Map)) {
                throw new ConfigException(source + "：rules 元素必须是映射");
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            Object idObj = entry.get("ruleId");
            if (idObj == null || idObj.toString().trim().isEmpty()) {
                throw new ConfigException(source + "：rules[].ruleId 缺失");
            }
            String ruleId = idObj.toString().trim();
            RuleOverride override = new RuleOverride();
            override.ruleId = ruleId;
            if (entry.get("level") != null) {
                override.level = ConfigValidator.parseLevel(ruleId, entry.get("level").toString());
            }
            if (entry.get("enabled") != null) {
                Object enabled = entry.get("enabled");
                if (!(enabled instanceof Boolean)) {
                    throw new ConfigException(source + "：规则 " + ruleId + " 的 enabled 必须是布尔");
                }
                override.enabled = (Boolean) enabled;
            }
            out.add(override);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<String>> parseKeywords(Object value, String source) {
        if (value == null) {
            return Optional.empty(); // 未提供 = 不覆盖
        }
        if (!(value instanceof List)) {
            throw new ConfigException(source + "：low_risk_keywords 必须是列表");
        }
        List<String> out = new ArrayList<String>();
        for (Object item : (List<Object>) value) {
            if (item == null) {
                throw new ConfigException(source + "：low_risk_keywords 元素不能为空");
            }
            out.add(item.toString());
        }
        return Optional.of(out);
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<String>> parseWhitelist(Object value, String source) {
        if (value == null) {
            return Optional.empty(); // 未提供 = 不覆盖
        }
        if (!(value instanceof Map)) {
            throw new ConfigException(source + "：whitelist 必须是映射");
        }
        Object statements = ((Map<String, Object>) value).get("statements");
        if (statements == null) {
            return Optional.empty();
        }
        if (!(statements instanceof List)) {
            throw new ConfigException(source + "：whitelist.statements 必须是列表");
        }
        List<String> out = new ArrayList<String>();
        for (Object item : (List<Object>) statements) {
            if (item == null) {
                throw new ConfigException(source + "：whitelist.statements 元素不能为空");
            }
            out.add(item.toString().trim());
        }
        return Optional.of(out);
    }

    // ---------- 合并 ----------

    private static void applyRuleOverridesFromDefaults(RawConfig merged) {
        for (RuleOverride override : merged.ruleOverrides) {
            if (override.level == null) {
                throw new ConfigException("内置默认配置：规则 " + override.ruleId + " 缺少 level");
            }
            merged.rules.put(override.ruleId,
                    new RuleSetting(override.ruleId, override.level, override.enabled == null || override.enabled));
        }
    }

    private static void mergeUser(RawConfig merged, RawConfig user) {
        for (RuleOverride override : user.ruleOverrides) {
            RuleSetting base = merged.rules.get(override.ruleId);
            if (base == null) {
                warn("未知 ruleId，忽略: " + override.ruleId);
                continue;
            }
            merged.rules.put(override.ruleId, new RuleSetting(override.ruleId,
                    override.level != null ? override.level : base.level(),
                    override.enabled != null ? override.enabled : base.enabled()));
        }
        if (user.dbUrl != null) {
            merged.dbUrl = user.dbUrl;
        }
        if (user.dbUsername != null) {
            merged.dbUsername = user.dbUsername;
        }
        if (user.dbPassword != null) {
            merged.dbPassword = user.dbPassword;
        }
        if (user.connectionTimeoutSeconds != null) {
            merged.connectionTimeoutSeconds = user.connectionTimeoutSeconds;
        }
        if (user.dialect != null) {
            merged.dialect = user.dialect;
        }
        if (user.lowRiskKeywords != null) {
            merged.lowRiskKeywords = user.lowRiskKeywords;
        }
        if (user.whitelistStatements != null) {
            merged.whitelistStatements = user.whitelistStatements;
        }
    }

    private static void warn(String message) {
        Diagnostics.warn("[警告] " + message);
    }

    /** 解析中间态：默认配置与用户配置共用（用户侧字段为 null 表示"未提供"）。 */
    private static final class RawConfig {
        List<RuleOverride> ruleOverrides = new ArrayList<RuleOverride>();
        final Map<String, RuleSetting> rules = new LinkedHashMap<String, RuleSetting>();
        String dbUrl;                        // null = 未提供
        String dbUsername;                   // null = 未提供
        String dbPassword;                   // null = 未提供（最低优先级）
        Integer connectionTimeoutSeconds;    // null = 未提供（缺省 10，§17）
        String dialect;                      // null = 未提供（缺省 mysql）
        List<String> lowRiskKeywords;        // null = 未提供（默认提供）
        List<String> whitelistStatements;    // null = 未提供（默认提供空列表）
    }

    private static final class RuleOverride {
        String ruleId;
        RiskLevel level;      // null = 继承既有
        Boolean enabled;      // null = 继承既有（缺省 true）
    }
}
