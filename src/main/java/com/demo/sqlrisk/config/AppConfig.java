package com.demo.sqlrisk.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 运行配置（详细设计 §6）：三层合并（内置默认 &lt; 用户 yaml &lt; CLI）后的不可变结果。
 */
public final class AppConfig {

    private final Map<String, RuleSetting> ruleSettings; // ruleId → 生效设置（含默认值）
    private final String metadataFile;                   // 绝对路径
    private final List<String> lowRiskKeywords;          // L1 关键字表
    private final List<String> whitelistStatements;      // 已校验的模式串

    public AppConfig(Map<String, RuleSetting> ruleSettings,
                     String metadataFile,
                     List<String> lowRiskKeywords,
                     List<String> whitelistStatements) {
        this.ruleSettings = Collections.unmodifiableMap(
                new LinkedHashMap<String, RuleSetting>(Objects.requireNonNull(ruleSettings, "ruleSettings")));
        this.metadataFile = Objects.requireNonNull(metadataFile, "metadataFile");
        this.lowRiskKeywords = Collections.unmodifiableList(
                new ArrayList<String>(Objects.requireNonNull(lowRiskKeywords, "lowRiskKeywords")));
        this.whitelistStatements = Collections.unmodifiableList(
                new ArrayList<String>(Objects.requireNonNull(whitelistStatements, "whitelistStatements")));
    }

    public Map<String, RuleSetting> ruleSettings() {
        return ruleSettings;
    }

    public Optional<RuleSetting> rule(String ruleId) {
        return Optional.ofNullable(ruleSettings.get(ruleId));
    }

    /** 表元数据 JSON 绝对路径（必需输入，加载失败 → exit 2）。 */
    public String metadataFile() {
        return metadataFile;
    }

    public List<String> lowRiskKeywords() {
        return lowRiskKeywords;
    }

    public List<String> whitelistStatements() {
        return whitelistStatements;
    }
}
