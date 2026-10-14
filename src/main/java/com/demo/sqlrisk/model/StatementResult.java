package com.demo.sqlrisk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单条语句的分析结论（详细设计 §4.2）。
 * 未命中任何规则时 {@code level} 为 null（语句仍进入 results，但计入任何级别）。
 */
public final class StatementResult {

    private final StatementMeta meta;
    private final String sql;
    private final List<Finding> findings;
    private final RiskLevel level; // nullable：命中规则的最高级别
    private final boolean uncertain;

    public StatementResult(StatementMeta meta, String sql, List<Finding> findings, RiskLevel level, boolean uncertain) {
        this.meta = Objects.requireNonNull(meta, "meta");
        this.sql = Objects.requireNonNull(sql, "sql");
        this.findings = Collections.unmodifiableList(new ArrayList<Finding>(
                Objects.requireNonNull(findings, "findings")));
        this.level = level;
        this.uncertain = uncertain;
    }

    public StatementMeta meta() {
        return meta;
    }

    /** 报告展示的 SQL 文本：命中时输出命中骨架，未命中输出骨架 #0。 */
    public String sql() {
        return sql;
    }

    public List<Finding> findings() {
        return findings;
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    /** 可为 null（无命中）。 */
    public RiskLevel level() {
        return level;
    }

    /** 骨架/还原不确定（Lambda 还原不完整、骨架截断、unknown 片段等）。 */
    public boolean uncertain() {
        return uncertain;
    }
}
