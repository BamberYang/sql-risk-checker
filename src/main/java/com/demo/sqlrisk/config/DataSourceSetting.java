package com.demo.sqlrisk.config;

import java.util.Objects;

/**
 * 数据库连接配置（详细设计 §6，v1.8/FR-8）：元数据来源；加载一次、只读、加载后即关闭。
 *
 * <p>密码已按优先级（CLI &gt; 环境变量 {@code SQL_RISK_DB_PASSWORD} &gt; yaml）解析为最终值；
 * 日志与错误信息一律脱敏（不含密码）。</p>
 */
public final class DataSourceSetting {

    private final String url;                 // 必填，jdbc: 前缀（配置层已校验）
    private final String username;            // nullable
    private final String password;            // nullable
    private final int connectionTimeoutSeconds;

    public DataSourceSetting(String url, String username, String password, int connectionTimeoutSeconds) {
        this.url = Objects.requireNonNull(url, "url");
        this.username = username;
        this.password = password;
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    public String url() {
        return url;
    }

    /** 可为 null。 */
    public String username() {
        return username;
    }

    /** 可为 null。 */
    public String password() {
        return password;
    }

    public int connectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }
}
