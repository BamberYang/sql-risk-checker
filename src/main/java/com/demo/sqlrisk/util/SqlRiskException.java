package com.demo.sqlrisk.util;

/**
 * 受控失败基类（详细设计 §15）：携带退出码，由入口层统一捕获转换为进程退出码。
 * 放置于 util 层（所有模块均可依赖），避免污染 model 值对象层。
 */
public abstract class SqlRiskException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    protected SqlRiskException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    protected SqlRiskException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
