package com.demo.sqlrisk.config;

/**
 * CLI 覆盖值（详细设计 §6，v1.8）：由 cli 层装配后传入 {@link ConfigLoader}，
 * 保持 config 包不反向依赖 cli 包（依赖方向约束 §3.3）。各字段可为 null（= 未提供）。
 */
public final class CliOverrides {

    private final String configPath;
    private final String dialect;
    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;

    public CliOverrides(String configPath, String dialect, String dbUrl,
                        String dbUsername, String dbPassword) {
        this.configPath = configPath;
        this.dialect = dialect;
        this.dbUrl = dbUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    public String configPath() {
        return configPath;
    }

    /** 可为 null。 */
    public String dialect() {
        return dialect;
    }

    /** 可为 null。 */
    public String dbUrl() {
        return dbUrl;
    }

    /** 可为 null。 */
    public String dbUsername() {
        return dbUsername;
    }

    /** 可为 null；优先级最高（CLI &gt; 环境变量 &gt; 配置文件）。 */
    public String dbPassword() {
        return dbPassword;
    }
}
