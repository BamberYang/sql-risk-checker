package com.demo.sqlrisk.cli;

/** 进程退出码约定（详细设计 §5、FR-7）。 */
public final class ExitCode {

    /** 无高危（正常结束，含"无语句可分析"）。 */
    public static final int OK = 0;

    /** 存在高危（summary.high > 0）。SKIP/解析失败不触发。 */
    public static final int HIGH_RISK = 1;

    /** 参数/环境错误：仓库、commit、配置、元数据、报告写出等受控错误与未知错误。 */
    public static final int USAGE_ENV_ERROR = 2;

    private ExitCode() {
    }
}
