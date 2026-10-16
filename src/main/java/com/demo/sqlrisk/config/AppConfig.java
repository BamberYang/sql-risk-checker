package com.demo.sqlrisk.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 运行配置（详细设计 §6）：三层合并（内置默认 &lt; 用户配置文件 &lt; CLI）后的不可变结果。
 */
public final class AppConfig {

    private final Map<String, RuleSetting> ruleSettings; // ruleId → 生效设置（含默认值）
    private final DataSourceSetting datasource;          // v1.8：数据库连接（元数据来源，FR-8）
    private final String dialect;                        // v1.8：SQL 方言（已校验为已支持值）
    private final List<String> lowRiskKeywords;          // L1 关键字表
    private final List<String> whitelistStatements;      // 已校验的模式串

    public AppConfig(Map<String, RuleSetting> ruleSettings,
                     DataSourceSetting datasource,
                     String dialect,
                     List<String> lowRiskKeywords,
                     List<String> whitelistStatements) {
        this.ruleSettings = Collections.unmodifiableMap(
                new LinkedHashMap<String, RuleSetting>(Objects.requireNonNull(ruleSettings, "ruleSettings")));
        this.datasource = Objects.requireNonNull(datasource, "datasource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
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

    /** 数据库连接（元数据来源；v1.8/D24）。 */
    public DataSourceSetting datasource() {
        return datasource;
    }

    /** SQL 方言名（v1.8/D25；v1 仅 mysql）。 */
    public String dialect() {
        return dialect;
    }

    public List<String> lowRiskKeywords() {
        return lowRiskKeywords;
    }

    public List<String> whitelistStatements() {
        return whitelistStatements;
    }
}
