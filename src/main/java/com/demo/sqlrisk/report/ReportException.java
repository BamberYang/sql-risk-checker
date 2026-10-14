package com.demo.sqlrisk.report;

import com.demo.sqlrisk.util.SqlRiskException;

/** 报告写出错误（详细设计 §12.4/§15）：目录不可创建/写入失败 → exit 2。 */
public final class ReportException extends SqlRiskException {

    private static final long serialVersionUID = 1L;

    public ReportException(String message) {
        super(message, 2);
    }

    public ReportException(String message, Throwable cause) {
        super(message, 2, cause);
    }
}
