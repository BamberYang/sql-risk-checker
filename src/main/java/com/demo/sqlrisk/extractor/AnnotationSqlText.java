package com.demo.sqlrisk.extractor;

import java.util.Objects;

/**
 * 注解式 SQL 载体（详细设计 §5.5.1 carrier）：非 &lt;script&gt; 的纯注解文本
 * （含 #{}/${} 占位符，由 M6 归一化后解析）。
 */
public final class AnnotationSqlText {

    private final String sqlText;

    public AnnotationSqlText(String sqlText) {
        this.sqlText = Objects.requireNonNull(sqlText, "sqlText");
    }

    public String sqlText() {
        return sqlText;
    }
}
