package com.demo.sqlrisk.util;

/**
 * 诊断输出统一出口（CLI 契约的集中豁免点，Sonar S106）。
 *
 * <p>本工具是 CLI 程序：诊断/警告/进度信息按契约写入 stderr（stdout 仅承载报告与控制台摘要），
 * 且刻意不引入日志实现（slf4j-nop，避免污染使用方环境与输出契约）——因此 {@code System.err}
 * 的合法使用集中在本类一处，其余生产代码一律经本类输出。</p>
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** 诊断/警告/进度输出（stderr）。 */
    public static void warn(String message) {
        System.err.println(message);
    }
}
