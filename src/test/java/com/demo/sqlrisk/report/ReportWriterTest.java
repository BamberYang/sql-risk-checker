package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.testutil.ReportFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** T8.4：报告写出（详细设计 §12.4）：固定文件名、目录自动创建、覆盖写、写失败 exit 2。 */
class ReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writes_fixed_file_name_under_output_dir() throws Exception {
        Path outDir = tempDir.resolve("nested/out");
        Path file = ReportWriter.write(ReportFixtures.sampleReport(), outDir.toString());
        assertEquals(outDir.resolve("sql_risk_report.json").toAbsolutePath().normalize(), file);
        assertTrue(Files.exists(file), "目录不存在时自动创建");
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals(JsonReporter.toJson(ReportFixtures.sampleReport()) + "\n", content,
                "文件内容 = JSON 序列化 + 结尾换行（UTF-8）");
    }

    @Test
    void overwrites_existing_report() throws Exception {
        Path outDir = tempDir.resolve("out");
        ReportWriter.write(ReportFixtures.sampleReport(), outDir.toString());
        ReportWriter.write(ReportFixtures.emptyReport(Mode.FULL), outDir.toString());
        String content = new String(Files.readAllBytes(outDir.resolve("sql_risk_report.json")),
                StandardCharsets.UTF_8);
        assertTrue(content.contains("\"mode\": \"FULL\""), "同名覆盖写：" + content);
        assertTrue(content.contains("\"results\": []"), content);
    }

    @Test
    void write_failure_throws_report_exception_with_exit_2() throws Exception {
        Path blocker = tempDir.resolve("blocker");
        Files.write(blocker, "not a dir".getBytes(StandardCharsets.UTF_8));
        try {
            ReportWriter.write(ReportFixtures.sampleReport(), blocker.toString());
            fail("应抛出 ReportException");
        } catch (ReportException e) {
            assertEquals(2, e.exitCode());
            assertTrue(e.getMessage().contains("报告写出失败"), e.getMessage());
        }
    }
}
