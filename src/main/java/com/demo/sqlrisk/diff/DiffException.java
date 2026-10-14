package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.util.SqlRiskException;

/** 仓库/差异获取错误（详细设计 §8.1/§15）：非 git 仓库、非法 commit、读取失败 → exit 2。 */
public final class DiffException extends SqlRiskException {

    private static final long serialVersionUID = 1L;

    public DiffException(String message) {
        super(message, 2); // = ExitCode.USAGE_ENV_ERROR（diff 不依赖 cli 包）
    }

    public DiffException(String message, Throwable cause) {
        super(message, 2, cause);
    }
}
