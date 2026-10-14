package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.ReportModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 报告文件写出（详细设计 §12.4）：{outputDir}/sql_risk_report.json；
 * 目录不存在则创建、同名覆盖、UTF-8 + 结尾换行；失败 → {@link ReportException}（exit 2）。
 */
public final class ReportWriter {

    public static final String REPORT_FILE_NAME = "sql_risk_report.json"; // §17

    private ReportWriter() {
    }

    /** @return 报告文件绝对路径 */
    public static Path write(ReportModel report, String outputDir) {
        Path file = Paths.get(outputDir).resolve(REPORT_FILE_NAME);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, (JsonReporter.toJson(report) + "\n").getBytes(StandardCharsets.UTF_8));
            return file.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new ReportException("报告写出失败: " + file + "（" + e.getMessage() + "）");
        }
    }
}
