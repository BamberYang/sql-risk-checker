package com.demo.sqlrisk.extractor.lambda;

import java.util.Objects;

/**
 * 链式调用参数（详细设计 §9.7）：按语义分类保留 M6 渲染所需信息。
 */
public final class LambdaArg {

    public enum Kind {
        /** SFunction 方法引用/λ（如 Order::getUserId）：text = 访问器方法名。 */
        S_FUNCTION,
        /** Class 字面量（如 Order.class）：text = 类型名。 */
        CLASS_LITERAL,
        /** 字符串字面量：text = 值（apply/last/inSql 等原文片段渲染用）。 */
        STRING_LITERAL,
        /** 布尔字面量 true（条件处理规则：纳入）。 */
        BOOLEAN_LITERAL_TRUE,
        /** 其他表达式（值参数等）：text = 源码文本（仅诊断用）。 */
        OTHER
    }

    private final Kind kind;
    private final String text;

    public LambdaArg(Kind kind, String text) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = Objects.requireNonNull(text, "text");
    }

    public Kind kind() {
        return kind;
    }

    public String text() {
        return text;
    }
}
