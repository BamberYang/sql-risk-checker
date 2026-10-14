package com.demo.sqlrisk.cli;

import com.demo.sqlrisk.util.SqlRiskException;

/** 命令行参数错误（详细设计 §5/§15）：exit 2。 */
public final class CliUsageException extends SqlRiskException {

    private static final long serialVersionUID = 1L;

    public CliUsageException(String message) {
        super(message, ExitCode.USAGE_ENV_ERROR);
    }
}
