package com.demo.sqlrisk.model;

import java.util.Objects;

/**
 * 单条规则命中（详细设计 §4.2）。
 */
public final class Finding {

    private final String ruleId;
    private final RiskLevel level;
    private final String message;
    private final String snippet;
    private final int skeletonIndex;

    public Finding(String ruleId, RiskLevel level, String message, String snippet, int skeletonIndex) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.level = Objects.requireNonNull(level, "level");
        this.message = Objects.requireNonNull(message, "message");
        this.snippet = Objects.requireNonNull(snippet, "snippet");
        this.skeletonIndex = skeletonIndex;
    }

    public String ruleId() {
        return ruleId;
    }

    public RiskLevel level() {
        return level;
    }

    public String message() {
        return message;
    }

    /** 相关片段（规范化 SQL 截取，上限见 §17 SNIPPET_LIMIT）。 */
    public String snippet() {
        return snippet;
    }

    /** 命中的骨架序号（首个骨架为 0；解释 choose 多骨架/保守折叠场景）。 */
    public int skeletonIndex() {
        return skeletonIndex;
    }
}
