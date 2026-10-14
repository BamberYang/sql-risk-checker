package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
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

/**
 * 配置加载（详细设计 §6）：三层合并——内置默认（resources/default-config.yaml）
 * &lt; 用户 yaml（--config）&lt; CLI 参数（--metadata）。
 *
 * <ul>
 *   <li>已移除键（bulk_operation_threshold / conservative_if_always_false）与未知键 → stderr 告警并忽略；</li>
 *   <li>rules[] 按 ruleId 覆盖；level 缺省继承既有值，enabled 缺省 true；未知 ruleId → 告警忽略；</li>
 *   <li>low_risk_keywords / whitelist.statements 提供时整体替换；</li>
 *   <li>metadata_file 相对路径以【所在配置文件目录】解析（内置默认以【仓库根】为基准），
 *       CLI --metadata 最终覆盖（按当前工作目录解析）。</li>
 * </ul>
 *
 * <p>注：详设 §6 示意签名为 {@code load(CliArgs)}，此处改为原始输入参数，
 * 保持 config 包不反向依赖 cli 包（依赖方向约束 §3.3）。</p>
 */
public final class ConfigLoader {

    private static final String DEFAULT_RESOURCE = "/default-config.yaml";
    private static final List<String> REMOVED_KEYS =
            Arrays.asList("bulk_operation_threshold", "conservative_if_always_false");
    private static final List<String> KNOWN_KEYS =
            Arrays.asList("rules", "metadata_file", "low_risk_keywords", "whitelist");

    private ConfigLoader() {
    }

    /**
     * @param configPath  用户配置路径，可空（缺省用内置默认）
     * @param metadataArg CLI --metadata 覆盖值，可空
     * @param repoPath    目标仓库路径（内置默认配置下 metadata_file 的解析基准 = 仓库根）
     */
    public static AppConfig load(String configPath, String metadataArg, String repoPath) {
        Path repoRoot = Paths.get(repoPath).toAbsolutePath().normalize();

        RawConfig merged = parseResourceDefaults(repoRoot);
        applyRuleOverridesFromDefaults(merged);

        if (configPath != null && !configPath.trim().isEmpty()) {
            Path configFile = Paths.get(configPath);
            if (!Files.isRegularFile(configFile)) {
                throw new ConfigException("配置文件不存在: " + configPath);
            }
            Path baseDir = configFile.toAbsolutePath().normalize().getParent();
            RawConfig user = parseYamlFile(configFile, baseDir, "用户配置 " + configPath);
            mergeUser(merged, user);
        }

        if (metadataArg != null && !metadataArg.trim().isEmpty()) {
            merged.metadataFile = Paths.get(metadataArg).toAbsolutePath().normalize().toString();
        }
        if (merged.metadataFile == null || merged.metadataFile.trim().isEmpty()) {
            throw new ConfigException("metadata_file 未配置（表元数据为必需输入，缺失报错）");
        }

        ConfigValidator.validateWhitelistPatterns(merged.whitelistStatements);
        ConfigValidator.validateKeywords(merged.lowRiskKeywords);
        return new AppConfig(merged.rules, merged.metadataFile, merged.lowRiskKeywords, merged.whitelistStatements);
    }

    // ---------- 解析 ----------

    private static RawConfig parseResourceDefaults(Path repoRoot) {
        InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("内置默认配置缺失: " + DEFAULT_RESOURCE);
        }
        return parseYaml(in, repoRoot, "内置默认配置");
    }

    private static RawConfig parseYamlFile(Path file, Path baseDir, String source) {
        try (InputStream in = Files.newInputStream(file)) {
            return parseYaml(in, baseDir, source);
        } catch (IOException e) {
            throw new ConfigException("配置文件读取失败: " + file + "（" + e.getMessage() + "）");
        }
    }

    @SuppressWarnings("unchecked")
    private static RawConfig parseYaml(InputStream in, Path baseDir, String source) {
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
        cfg.metadataFile = parseMetadataFile(root.get("metadata_file"), baseDir, source);
        cfg.lowRiskKeywords = parseKeywords(root.get("low_risk_keywords"), source);
        cfg.whitelistStatements = parseWhitelist(root.get("whitelist"), source);
        return cfg;
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

    private static String parseMetadataFile(Object value, Path baseDir, String source) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            throw new ConfigException(source + "：metadata_file 不能为空");
        }
        return baseDir.resolve(text).toAbsolutePath().normalize().toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseKeywords(Object value, String source) {
        if (value == null) {
            return null; // 未提供 = 不覆盖
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
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseWhitelist(Object value, String source) {
        if (value == null) {
            return null; // 未提供 = 不覆盖
        }
        if (!(value instanceof Map)) {
            throw new ConfigException(source + "：whitelist 必须是映射");
        }
        Object statements = ((Map<String, Object>) value).get("statements");
        if (statements == null) {
            return null;
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
        return out;
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
        if (user.metadataFile != null) {
            merged.metadataFile = user.metadataFile;
        }
        if (user.lowRiskKeywords != null) {
            merged.lowRiskKeywords = user.lowRiskKeywords;
        }
        if (user.whitelistStatements != null) {
            merged.whitelistStatements = user.whitelistStatements;
        }
    }

    private static void warn(String message) {
        System.err.println("[警告] " + message);
    }

    /** 解析中间态：默认配置与用户配置共用（用户侧字段为 null 表示"未提供"）。 */
    private static final class RawConfig {
        List<RuleOverride> ruleOverrides = new ArrayList<RuleOverride>();
        final Map<String, RuleSetting> rules = new LinkedHashMap<String, RuleSetting>();
        String metadataFile;                 // 绝对路径；null = 未提供
        List<String> lowRiskKeywords;        // null = 未提供（默认提供）
        List<String> whitelistStatements;    // null = 未提供（默认提供空列表）
    }

    private static final class RuleOverride {
        String ruleId;
        RiskLevel level;      // null = 继承既有
        Boolean enabled;      // null = 继承既有（缺省 true）
    }
}
