package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.util.Diagnostics;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 配置加载（详细设计 §6，v1.8；v1.11 起配置格式为 properties）：三层合并——内置默认
 * （resources/default-config.properties）&lt; 用户配置（--config）&lt; CLI 覆盖
 * （dialect / db-url / db-username / db-password）。
 *
 * <ul>
 *   <li>解析用 {@link Properties#load(java.io.Reader)}（UTF-8 字符流，不用 ISO-8859-1 字节流）；</li>
 *   <li>已移除键（bulk_operation_threshold / conservative_if_always_false / <b>metadata_file</b>）与未知键
 *       → stderr 告警并忽略；旧 yaml 后缀文件 → 明确报错（格式已变更）；</li>
 *   <li>规则键 {@code rules.<ruleId>.level} / {@code rules.<ruleId>.enabled}：level 缺省继承既有值，
 *       enabled 缺省 true（须为 true/false）；未知 ruleId → 告警忽略；</li>
 *   <li>列表键（low_risk_keywords / whitelist.statements）支持单值键与下标键两种写法，二选一，提供时整体替换；</li>
 *   <li>datasource.url / username / password / connection_timeout_seconds 承载数据库连接（元数据来源，FR-8）；
 *      密码优先级 CLI &gt; 环境变量 {@code SQL_RISK_DB_PASSWORD} &gt; 配置文件（D26）；</li>
 *   <li>datasource.url 必填且须以 {@code jdbc:} 开头；dialect 必须为已支持值（D25）。</li>
 * </ul>
 *
 * <p>签名以 {@link CliOverrides} 携带 CLI 值，保持 config 包不反向依赖 cli 包（§3.3）。</p>
 */
public final class ConfigLoader {

    private static final String DEFAULT_RESOURCE = "/default-config.properties";
    private static final String PASSWORD_ENV = "SQL_RISK_DB_PASSWORD";
    private static final String DOT = ".";
    private static final String COLON = "：";
    private static final String KEY_DIALECT = "dialect";
    private static final String KEY_RULES_PREFIX = "rules.";
    private static final String FIELD_LEVEL = "level";
    private static final String FIELD_ENABLED = "enabled";
    private static final String KEY_DATASOURCE_URL = "datasource.url";
    private static final String KEY_DATASOURCE_USERNAME = "datasource.username";
    private static final String KEY_DATASOURCE_PASSWORD = "datasource.password";
    private static final String KEY_DATASOURCE_TIMEOUT = "datasource.connection_timeout_seconds";
    private static final String KEY_KEYWORDS = "low_risk_keywords";
    private static final String KEY_WHITELIST = "whitelist.statements";
    private static final String TEXT_TRUE = "true";
    private static final String TEXT_FALSE = "false";
    private static final String YAML_SUFFIX = ".yaml";
    private static final String YML_SUFFIX = ".yml";
    private static final String MSG_REMOVED = " 中的配置项已移除，忽略: ";
    private static final String MSG_UNKNOWN = " 中的未知配置项，忽略: ";
    private static final String MSG_MIXED_LIST = " 不能同时使用单值键与下标键: ";
    private static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 10; // §17
    private static final List<String> REMOVED_KEYS =
            Arrays.asList("bulk_operation_threshold", "conservative_if_always_false", "metadata_file");
    private static final List<String> KNOWN_KEYS = Arrays.asList(KEY_DIALECT, KEY_DATASOURCE_URL,
            KEY_DATASOURCE_USERNAME, KEY_DATASOURCE_PASSWORD, KEY_DATASOURCE_TIMEOUT,
            KEY_KEYWORDS, KEY_WHITELIST);

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
            rejectLegacyYamlPath(configFile);
            RawConfig user = parsePropertiesFile(configFile, "用户配置 " + overrides.configPath());
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
                    + "可用 --db-url 或配置文件的 datasource.url 提供）");
        }
        if (!merged.dbUrl.startsWith("jdbc:")) {
            throw new ConfigException("datasource.url 必须以 jdbc: 开头: " + merged.dbUrl);
        }
        String dialect = nonBlank(merged.dialect) ? merged.dialect.trim() : "mysql";
        ConfigValidator.validateDialect(dialect);
        int timeout = merged.connectionTimeoutSeconds == null
                ? DEFAULT_CONNECTION_TIMEOUT_SECONDS : merged.connectionTimeoutSeconds;
        if (timeout <= 0) {
            throw new ConfigException(KEY_DATASOURCE_TIMEOUT + " 必须为正整数: " + timeout);
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
        return parseProperties(in, "内置默认配置");
    }

    private static RawConfig parsePropertiesFile(Path file, String source) {
        try (InputStream in = Files.newInputStream(file)) {
            return parseProperties(in, source);
        } catch (IOException e) {
            throw new ConfigException("配置文件读取失败: " + file + "（" + e.getMessage() + "）");
        }
    }

    /** 配置格式已由 yaml 改为 properties：旧后缀直接报错，避免被当作一堆未知键静默忽略。 */
    private static void rejectLegacyYamlPath(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(YAML_SUFFIX) || name.endsWith(YML_SUFFIX)) {
            throw new ConfigException("配置文件格式已由 yaml 改为 properties，请使用 .properties 文件: " + file);
        }
    }

    private static RawConfig parseProperties(InputStream in, String source) {
        Properties props = new Properties();
        try {
            // 字符流 + UTF-8：Properties.load(InputStream) 固定按 ISO-8859-1 解码，中文注释/关键字会乱码
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException(source + " 读取失败: " + e.getMessage());
        } catch (RuntimeException e) { // 非法 Unicode 转义等
            throw new ConfigException(source + " 解析失败: " + e.getMessage());
        }
        RawConfig cfg = new RawConfig();
        warnUnknownKeys(props, source);
        cfg.ruleOverrides = parseRules(props, source);
        parseDatasource(props, cfg, source);
        if (props.containsKey(KEY_DIALECT)) {
            cfg.dialect = props.getProperty(KEY_DIALECT).trim();
        }
        parseList(props, KEY_KEYWORDS, source).ifPresent(list -> cfg.lowRiskKeywords = list);
        parseList(props, KEY_WHITELIST, source).ifPresent(list -> cfg.whitelistStatements = list);
        return cfg;
    }

    private static void warnUnknownKeys(Properties props, String source) {
        for (String key : props.stringPropertyNames()) {
            if (REMOVED_KEYS.contains(key)) {
                warn(source + MSG_REMOVED + key);
            } else if (!isKnownKey(key)) {
                warn(source + MSG_UNKNOWN + key);
            }
        }
    }

    private static boolean isKnownKey(String key) {
        if (KNOWN_KEYS.contains(key)) {
            return true;
        }
        if (isListKey(key, KEY_KEYWORDS) || isListKey(key, KEY_WHITELIST)) {
            return true;
        }
        return key.startsWith(KEY_RULES_PREFIX) && isRuleField(key);
    }

    /** {@code key} 或 {@code key.<n>} 形状（下标形式由 {@link #indexOfKey} 判定，形状不符按未知键处理）。 */
    private static boolean isListKey(String key, String base) {
        return base.equals(key) || indexOfKey(key, base) != null;
    }

    /** {@code rules.<ruleId>.<level|enabled>} 形状。 */
    private static boolean isRuleField(String key) {
        if (key.length() <= KEY_RULES_PREFIX.length()) {
            return false;
        }
        int lastDot = key.lastIndexOf('.');
        if (lastDot < KEY_RULES_PREFIX.length()) {
            return false;
        }
        String field = key.substring(lastDot + 1);
        return (FIELD_LEVEL.equals(field) || FIELD_ENABLED.equals(field))
                && !key.substring(KEY_RULES_PREFIX.length(), lastDot).isEmpty();
    }

    private static void parseDatasource(Properties props, RawConfig cfg, String source) {
        if (props.containsKey(KEY_DATASOURCE_URL)) {
            cfg.dbUrl = props.getProperty(KEY_DATASOURCE_URL).trim();
        }
        if (props.containsKey(KEY_DATASOURCE_USERNAME)) {
            cfg.dbUsername = props.getProperty(KEY_DATASOURCE_USERNAME).trim();
        }
        if (props.containsKey(KEY_DATASOURCE_PASSWORD)) {
            cfg.dbPassword = props.getProperty(KEY_DATASOURCE_PASSWORD); // 密码不 trim：首尾空格可能是密码本身
        }
        if (props.containsKey(KEY_DATASOURCE_TIMEOUT)) {
            String timeout = props.getProperty(KEY_DATASOURCE_TIMEOUT).trim();
            try {
                cfg.connectionTimeoutSeconds = Integer.valueOf(timeout);
            } catch (NumberFormatException e) {
                throw new ConfigException(source + COLON + KEY_DATASOURCE_TIMEOUT + " 必须是整数: " + timeout);
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

    /**
     * 规则键解析：按 ruleId 聚合 {@code rules.<ruleId>.level|enabled}。
     * ruleId 用 TreeMap 排序，保证 Properties（无序 Hashtable）下的结果确定性。
     */
    private static List<RuleOverride> parseRules(Properties props, String source) {
        Map<String, RuleOverride> byRuleId = new TreeMap<String, RuleOverride>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(KEY_RULES_PREFIX) || !isRuleField(key)) {
                continue;
            }
            int lastDot = key.lastIndexOf('.');
            String ruleId = key.substring(KEY_RULES_PREFIX.length(), lastDot);
            String field = key.substring(lastDot + 1);
            RuleOverride override = byRuleId.get(ruleId);
            if (override == null) {
                override = new RuleOverride();
                override.ruleId = ruleId;
                byRuleId.put(ruleId, override);
            }
            String value = props.getProperty(key);
            if (FIELD_LEVEL.equals(field)) {
                override.level = ConfigValidator.parseLevel(ruleId, value);
            } else {
                override.enabled = parseEnabled(ruleId, value, source);
            }
        }
        return new ArrayList<RuleOverride>(byRuleId.values());
    }

    private static Boolean parseEnabled(String ruleId, String value, String source) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (TEXT_TRUE.equals(text)) {
            return Boolean.TRUE;
        }
        if (TEXT_FALSE.equals(text)) {
            return Boolean.FALSE;
        }
        // 不用 Boolean.parseBoolean：非法文本会被静默当作 false
        throw new ConfigException(source + COLON + "规则 " + ruleId + " 的 enabled 必须是 true/false: " + value);
    }

    /**
     * 列表键解析：单值键（{@code key=}）与下标键（{@code key.<n>=}）二选一。
     * 都未出现 = 未提供（{@link Optional#empty()}，合并阶段不覆盖）；单值键空值 = 空列表（整体替换为空）。
     */
    private static Optional<List<String>> parseList(Properties props, String key, String source) {
        TreeMap<Integer, String> indexed = new TreeMap<Integer, String>();
        boolean hasSingleValueKey = false;
        for (String propKey : props.stringPropertyNames()) {
            if (key.equals(propKey)) {
                hasSingleValueKey = true;
            } else {
                Integer index = indexOfKey(propKey, key);
                if (index != null) {
                    indexed.put(index, props.getProperty(propKey));
                }
            }
        }
        if (!hasSingleValueKey && indexed.isEmpty()) {
            return Optional.empty();
        }
        if (hasSingleValueKey && !indexed.isEmpty()) {
            throw new ConfigException(source + MSG_MIXED_LIST + key);
        }
        if (!hasSingleValueKey) {
            return Optional.of(new ArrayList<String>(indexed.values())); // TreeMap 已按下标升序
        }
        List<String> single = new ArrayList<String>();
        String value = props.getProperty(key).trim();
        if (!value.isEmpty()) {
            single.add(value);
        }
        return Optional.of(single);
    }

    /** {@code base.<n>} 形状匹配：返回下标 n；形状不符或超出 int 范围 → null（按未知键归口告警）。 */
    private static Integer indexOfKey(String propKey, String base) {
        String prefix = base + DOT;
        if (!propKey.startsWith(prefix)) {
            return null;
        }
        String suffix = propKey.substring(prefix.length());
        if (suffix.isEmpty()) {
            return null;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(suffix);
        } catch (NumberFormatException e) {
            return null; // 合法数字串但超出 int：归口未知键
        }
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
