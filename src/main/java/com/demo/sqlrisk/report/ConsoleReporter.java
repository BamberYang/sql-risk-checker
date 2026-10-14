package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.Summary;
import com.demo.sqlrisk.util.TextUtils;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制台摘要（详细设计 §12.2、附录 B 格式）：
 * 运行头 + 数量统计 + 高危明细（中/低危仅 --verbose）+ 报告路径；纯文本输出（无 ANSI 颜色，天然满足非 TTY 约定）。
 */
public final class ConsoleReporter {

    private static final int SQL_DISPLAY_LIMIT = 200; // §17

    private ConsoleReporter() {
    }

    public static void print(ReportModel report, String repoPath, String reportFilePath,
                             PrintStream out, boolean verbose) {
        Summary summary = report.summary();
        out.println("[SQL 风险检查] mode=" + report.mode()
                + "  head=" + abbreviate(report.headCommit())
                + (report.prodCommit() != null ? "  prod=" + abbreviate(report.prodCommit()) : ""));
        out.println("仓库: " + repoPath);

        boolean empty = report.results().isEmpty() && report.ignored().isEmpty() && report.skipped().isEmpty();
        if (empty) {
            out.println(report.mode() == Mode.DIFF ? "无差异语句" : "未提取到 SQL 语句");
        } else {
            out.println("高危 " + summary.high() + " | 中危 " + summary.medium() + " | 低危 " + summary.low()
                    + " | SKIP " + summary.skip() + " | 已忽略 " + summary.ignored());
        }

        printLevelDetail(report, RiskLevel.HIGH, "高危明细", out);
        if (verbose) {
            printLevelDetail(report, RiskLevel.MEDIUM, "中危明细", out);
            printLevelDetail(report, RiskLevel.LOW, "低危明细", out);
        }
        out.println("报告已生成: " + reportFilePath);
    }

    private static void printLevelDetail(ReportModel report, RiskLevel level, String title, PrintStream out) {
        List<StatementResult> levelResults = new ArrayList<StatementResult>();
        for (StatementResult result : report.results()) {
            if (result.level() == level) {
                levelResults.add(result);
            }
        }
        if (levelResults.isEmpty()) {
            return;
        }
        out.println("------------------------- " + title + " -------------------------");
        for (StatementResult result : levelResults) {
            for (Finding finding : result.findings()) {
                if (finding.level() != level) {
                    continue;
                }
                out.println("[" + finding.ruleId() + "] " + result.meta().statementId());
                out.println("     " + result.meta().file() + ":" + result.meta().line()
                        + "  (" + result.meta().changeType()
                        + (result.meta().changedVia() != null
                        ? ", via " + result.meta().changedVia() : "") + ")");
                out.println("     " + TextUtils.truncate(TextUtils.collapseWhitespace(result.sql()), SQL_DISPLAY_LIMIT)
                        + "          ← " + finding.message());
            }
        }
    }

    private static String abbreviate(String commit) {
        return commit != null && commit.length() > 7 ? commit.substring(0, 7) : commit;
    }
}
