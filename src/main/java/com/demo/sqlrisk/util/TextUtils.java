package com.demo.sqlrisk.util;

import java.util.Locale;

/**
 * 文本工具（详细设计 §14）：截断、空白折叠、驼峰→下划线、大小写不敏感包含。
 */
public final class TextUtils {

    private static final String ELLIPSIS = "…";

    private TextUtils() {
    }

    /** 超长截断并追加省略号；null 原样返回。 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + ELLIPSIS;
    }

    /** 连续空白（含换行）折叠为单个空格并 trim。 */
    public static String collapseWhitespace(String s) {
        if (s == null) {
            return null;
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * 驼峰→下划线（MP 默认列名转换，§9.8）：userId→user_id、userID→user_id、ID→id、user_id→user_id。
     */
    public static String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && !Character.isUpperCase(camel.charAt(i - 1))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 大小写不敏感包含（L1 关键字判定）。 */
    public static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 按 1 基闭区间 [startLine, endLine] 切片（含首尾行）；统一 '\n' 并去除行尾 '\r'。 */
    public static String sliceLines(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int from = Math.max(1, startLine);
        int to = Math.min(lines.length, endLine);
        for (int i = from; i <= to; i++) {
            String line = lines[i - 1];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
