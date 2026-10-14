package com.demo.sqlrisk.metadata;

import com.demo.sqlrisk.util.SqlRiskException;

/** 表元数据错误（详细设计 §7/§15）：文件缺失（含默认路径）/格式错误/校验失败 → exit 2。 */
public final class MetadataException extends SqlRiskException {

    private static final long serialVersionUID = 1L;

    public MetadataException(String message) {
        super(message, 2); // = ExitCode.USAGE_ENV_ERROR（metadata 不依赖 cli 包）
    }
}
