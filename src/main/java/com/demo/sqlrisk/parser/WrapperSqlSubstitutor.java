package com.demo.sqlrisk.parser;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper SQL 段代换（详细设计 §10.6，v1.4）：
 * 注解语句中将根名匹配 {@code wrapperSqlParams}（§9.5-6）的 {@code ${name…}} 占位符代换为中性谓词，
 * 使语句可正常解析（条件来自运行期 Wrapper，骨架标记未知片段）。代换文本按上下文确定：
 * 占位符前非空白内容以 {@code WHERE/AND/OR/NOT/ON/(} 结尾（占位符提供"条件表达式"）→ {@code 1 = 1}；
 * 否则（占位符自带 WHERE 的 {@code customSqlSegment} 惯用法）→ {@code WHERE 1 = 1}。
 */
public final class WrapperSqlSubstitutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)\\}");

    private WrapperSqlSubstitutor() {
    }

    /** 代换结果：{@code applied=true} 表示至少代换了一个占位符。 */
    public static final class Result {

        private final String text;
        private final boolean applied;

        Result(String text, boolean applied) {
            this.text = text;
            this.applied = applied;
        }

        public String text() {
            return text;
        }

        public boolean applied() {
            return applied;
        }
    }

    public static Result substitute(String sql, List<String> wrapperParams) {
        if (sql == null || wrapperParams == null || wrapperParams.isEmpty()) {
            return new Result(sql, false);
        }
        Matcher matcher = PLACEHOLDER.matcher(sql);
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int last = 0;
        boolean applied = false;
        while (matcher.find()) {
            out.append(sql, last, matcher.start());
            last = matcher.end();
            String root = rootOf(matcher.group(1));
            if (root != null && wrapperParams.contains(root)) {
                applied = true;
                appendNeutralPredicate(out);
            } else {
                out.append(matcher.group());
            }
        }
        out.append(sql, last, sql.length());
        return new Result(out.toString(), applied);
    }

    static String rootOf(String placeholderContent) {
        String content = placeholderContent == null ? "" : placeholderContent.trim();
        int dot = content.indexOf('.');
        String root = dot >= 0 ? content.substring(0, dot) : content;
        return root.isEmpty() ? null : root;
    }

    /** 依上下文追加中性谓词（保持与前文的可解析衔接；`(` 后不加空格）。 */
    private static void appendNeutralPredicate(StringBuilder out) {
        char last = out.length() == 0 ? ' ' : out.charAt(out.length() - 1);
        if (!Character.isWhitespace(last) && last != '(') {
            out.append(' ');
        }
        out.append(conditionSlot(out) ? "1 = 1" : "WHERE 1 = 1");
    }

    /** 占位符处于"条件表达式槽位"：前文（去尾空白后）为空或以 {@code (} 结尾，或以连接/引导关键字结尾。 */
    private static boolean conditionSlot(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0 && Character.isWhitespace(out.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return true; // 语句以占位符开头（非常规）：按条件槽位处理
        }
        if (out.charAt(i) == '(') {
            return true;
        }
        int end = i + 1;
        while (i >= 0 && (Character.isLetter(out.charAt(i)) || out.charAt(i) == '_')) {
            i--;
        }
        String word = out.substring(i + 1, end).toUpperCase(java.util.Locale.ROOT);
        return "WHERE".equals(word) || "AND".equals(word) || "OR".equals(word)
                || "NOT".equals(word) || "ON".equals(word);
    }
}
