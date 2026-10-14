package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;

import java.util.Objects;

/** 单条规则的级别与开关（详细设计 §6）：配置对内置默认的覆盖项。 */
public final class RuleSetting {

    private final String ruleId;
    private final RiskLevel level;
    private final boolean enabled;

    public RuleSetting(String ruleId, RiskLevel level, boolean enabled) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.level = Objects.requireNonNull(level, "level");
        this.enabled = enabled;
    }

    public String ruleId() {
        return ruleId;
    }

    public RiskLevel level() {
        return level;
    }

    public boolean enabled() {
        return enabled;
    }
}
