package com.demo.sqlrisk.parser;

import java.util.Objects;

/** 归一化结果（详细设计 §10.1）：#{...}/${...} 均替换为 ?，并保留 ${} 存在标志。 */
public final class NormalizedSql {

    private final String text;
    private final boolean hasDollarPlaceholder;

    public NormalizedSql(String text, boolean hasDollarPlaceholder) {
        this.text = Objects.requireNonNull(text, "text");
        this.hasDollarPlaceholder = hasDollarPlaceholder;
    }

    public String text() {
        return text;
    }

    /** 骨架是否含 ${} 占位（辅助信息；M8 判定基于语句原文，见 §8.5）。 */
    public boolean hasDollarPlaceholder() {
        return hasDollarPlaceholder;
    }
}
