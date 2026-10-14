package com.demo.sqlrisk.parser;

/** 保守求值失败（详细设计 §10.2）：include 无法解析/深度超限等 → 语句整体 SKIP(PARSE_FAILED)。 */
public final class DynamicEvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DynamicEvaluationException(String message) {
        super(message);
    }
}
