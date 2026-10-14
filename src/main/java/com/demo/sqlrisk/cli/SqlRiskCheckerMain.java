package com.demo.sqlrisk.cli;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.config.ConfigLoader;
import com.demo.sqlrisk.core.AnalysisEngine;
import com.demo.sqlrisk.core.RunContext;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.report.ConsoleReporter;
import com.demo.sqlrisk.report.ReportWriter;
import com.demo.sqlrisk.util.SqlRiskException;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;

/**
 * 程序入口（详细设计 §5）：
 * 参数解析 → 配置加载 → RunContext 装配 → AnalysisEngine 执行 → P9 报告输出（控制台摘要 + JSON 文件）→ 退出码。
 *
 * <p>顶层统一捕获：受控异常（{@link SqlRiskException}）取异常自带退出码；
 * 未知异常兜底 exit 2（--verbose 时打印堆栈），绝不裸抛。</p>
 *
 * <p>调试便利：argv 为空时使用 {@link #DEBUG_DEFAULT_ARGV}（IDE 直接运行 main 即可调试）。
 * 正式运行（CI/脚本）请显式传参，避免落入调试默认值。</p>
 */
public final class SqlRiskCheckerMain {

    /**
     * 调试默认参数（仅当 argv 为空时启用）：指向本地验证仓库与占位元数据，报告输出到 /tmp 避免污染工作目录。
     * 按需修改此处即可改变默认调试目标。
     */
    static final String[] DEBUG_DEFAULT_ARGV = {
            "--repo", "/Users/loafer/Documents/WorkSpace/union/union-order-app",
            "--metadata", "/tmp/union-meta.json",
            "--output", "/tmp/sql-risk-debug",
            "--verbose",
    };

    private SqlRiskCheckerMain() {
    }

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err, Clock.systemDefaultZone()));
    }

    /**
     * argv 为空 → 返回调试默认参数并向 stderr 打印提示（不静默）；非空原样返回。
     */
    static String[] effectiveArgv(String[] argv, PrintStream err) {
        if (argv == null || argv.length == 0) {
            err.println("[调试] argv 为空，使用内置默认参数：" + String.join(" ", DEBUG_DEFAULT_ARGV));
            return DEBUG_DEFAULT_ARGV;
        }
        return argv;
    }

    /** 可测试入口：不调用 System.exit；输出经传入流，时钟可注入。 */
    static int run(String[] argv, PrintStream out, PrintStream err, Clock clock) {
        boolean verbose = false;
        try {
            CliArgs args = CliArgs.parse(effectiveArgv(argv, err));
            if (args.helpRequested()) {
                out.println(CliArgs.usage());
                return ExitCode.OK;
            }
            verbose = args.verbose();
            AppConfig config = ConfigLoader.load(args.configPath(), args.metadataPath(), args.repoPath());
            RunContext ctx = new RunContext(args.repoPath(), args.headCommit(), args.prodCommit(), config,
                    args.outputDir(), args.verbose(), clock);
            ReportModel report = new AnalysisEngine(clock).execute(ctx);

            // P9 报告输出（M8）：JSON 先落盘（写失败 → exit 2），再输出控制台摘要（含报告路径）
            Path reportFile = ReportWriter.write(report, args.outputDir());
            ConsoleReporter.print(report, args.repoPath(), reportFile.toString(), out, args.verbose());
            return report.summary().high() > 0 ? ExitCode.HIGH_RISK : ExitCode.OK;
        } catch (SqlRiskException e) {
            err.println("[错误] " + e.getMessage());
            return e.exitCode();
        } catch (Throwable t) {
            err.println("[未知错误] " + t);
            if (verbose) {
                t.printStackTrace(err);
            }
            return ExitCode.USAGE_ENV_ERROR;
        }
    }
}
