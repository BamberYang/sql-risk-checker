package com.demo.sqlrisk.parser;

/** 骨架解析失败（详细设计 §10.6）：由语句解析编排转为 SKIP(PARSE_FAILED)，不中断整体。 */
public final class SqlParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sql;

    public SqlParseException(String message, String sql, Throwable cause) {
        super(message, cause);
        this.sql = sql;
    }

    /** 解析失败的骨架文本。 */
    public String sql() {
        return sql;
    }
}
