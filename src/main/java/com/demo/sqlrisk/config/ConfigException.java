package com.demo.sqlrisk.config;

import com.demo.sqlrisk.util.SqlRiskException;

/** 配置错误（详细设计 §6/§15）：配置文件缺失/语法错误/校验失败 → exit 2。 */
public final class ConfigException extends SqlRiskException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message, 2); // = ExitCode.USAGE_ENV_ERROR（config 不依赖 cli 包）
    }
}
