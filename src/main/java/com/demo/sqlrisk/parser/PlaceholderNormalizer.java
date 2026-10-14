package com.demo.sqlrisk.parser;

import java.util.regex.Pattern;

/**
 * 占位符归一化（详细设计 §10.1）：{@code #{...}} → {@code ?}；
 * {@code ${...}} → {@code ?} 并置 {@link NormalizedSql#hasDollarPlaceholder()}。
 *
 * <p>仅在骨架文本上执行；M8 短路判定使用语句原文（含被丢弃分支，§8.5），两者互不替代。</p>
 */
public final class PlaceholderNormalizer {

    private static final Pattern HASH_PLACEHOLDER = Pattern.compile("#\\{[^}]*\\}");
    private static final Pattern DOLLAR_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*\\}");

    private PlaceholderNormalizer() {
    }

    public static NormalizedSql normalize(String sqlText) {
        if (sqlText == null) {
            return new NormalizedSql("", false);
        }
        boolean hasDollar = DOLLAR_PLACEHOLDER.matcher(sqlText).find();
        String text = HASH_PLACEHOLDER.matcher(sqlText).replaceAll("?");
        text = DOLLAR_PLACEHOLDER.matcher(text).replaceAll("?");
        return new NormalizedSql(text, hasDollar);
    }
}
