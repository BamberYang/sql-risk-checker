package com.demo.sqlrisk.model;

import java.util.Collection;
import java.util.Optional;

/**
 * 风险级别（详细设计 §4.1）：HIGH &gt; MEDIUM &gt; LOW。
 * SKIP 不是级别——无法分析的语句（解析失败等）由 skipped 清单表达，不计入任何级别。
 */
public enum RiskLevel {

    HIGH(3),
    MEDIUM(2),
    LOW(1);

    private final int weight;

    RiskLevel(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    /** 取集合中的最高级别；集合为空或仅含 null 时返回 {@link Optional#empty()}。 */
    public static Optional<RiskLevel> max(Collection<RiskLevel> levels) {
        RiskLevel best = null;
        if (levels != null) {
            for (RiskLevel level : levels) {
                if (level != null && (best == null || level.weight > best.weight)) {
                    best = level;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
