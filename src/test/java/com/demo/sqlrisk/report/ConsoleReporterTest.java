package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.testutil.ReportFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T8.3：控制台摘要（详细设计 §12.2）：运行头、统计行、高危明细、--verbose、空结果提示。 */
class ConsoleReporterTest {

    private ByteArrayOutputStream buf;
    private PrintStream out;

    @BeforeEach
    void setUp() throws Exception {
        buf = new ByteArrayOutputStream();
        out = new PrintStream(buf, true, "UTF-8");
    }

    private String render(ReportModel report, boolean verbose) {
        buf.reset();
        ConsoleReporter.print(report, ReportFixtures.REPO_PATH, ReportFixtures.REPORT_PATH, out, verbose);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void header_counts_and_high_details() {
        String text = render(ReportFixtures.sampleReport(), false);
        assertTrue(text.contains("[SQL 风险检查] mode=DIFF  head=a32658d  prod=b7c1d2e"), text);
        assertTrue(text.contains("仓库: /repo/demo"), text);
        assertTrue(text.contains("高危 1 | 中危 1 | 低危 1 | SKIP 2 | 已忽略 1"), text);
        assertTrue(text.contains("高危明细"), text);
        assertTrue(text.contains("[H1] com.demo.mapper.OrderMapper.updateStatus"), text);
        assertTrue(text.contains("src/main/resources/mapper/OrderMapper.xml:42"
                + "  (MODIFIED, via com.demo.mapper.Common.baseColumns)"), "片段派生命中标注 changedVia：" + text);
        assertTrue(text.contains("← " + ReportFixtures.H1_MESSAGE), "SQL 行附命中原因：" + text);
        assertTrue(text.contains("报告已生成: " + ReportFixtures.REPORT_PATH), text);
    }

    @Test
    void non_verbose_hides_medium_and_low_sections() {
        String text = render(ReportFixtures.sampleReport(), false);
        assertFalse(text.contains("中危明细"), text);
        assertFalse(text.contains("低危明细"), text);
        assertFalse(text.contains("[M1]"), text);
        assertFalse(text.contains("[L2]"), text);
    }

    @Test
    void verbose_prints_medium_and_low_sections() {
        String text = render(ReportFixtures.sampleReport(), true);
        assertTrue(text.contains("中危明细"), text);
        assertTrue(text.contains("[M1] com.demo.mapper.OrderMapper.findByName"), text);
        assertTrue(text.contains("低危明细"), text);
        assertTrue(text.contains("[L2] com.demo.mapper.OrderMapper.find"), text);
    }

    @Test
    void full_mode_omits_prod_segment() {
        String text = render(ReportFixtures.sampleReport(Mode.FULL, null), false);
        assertTrue(text.contains("[SQL 风险检查] mode=FULL  head=a32658d"), text);
        assertFalse(text.contains("prod="), text);
    }

    @Test
    void empty_diff_prints_no_diff_message() {
        String text = render(ReportFixtures.emptyReport(Mode.DIFF), false);
        assertTrue(text.contains("无差异语句"), text);
        assertFalse(text.contains("高危 0 |"), "空差异不输出统计行：" + text);
    }

    @Test
    void empty_full_prints_none_extracted_message() {
        String text = render(ReportFixtures.emptyReport(Mode.FULL), false);
        assertTrue(text.contains("未提取到 SQL 语句"), text);
    }

    @Test
    void ignored_only_report_still_prints_counts_line() {
        // 仅白名单命中的场景：results 为空但 ignored 非空 → 不属于"空结果"，输出统计行
        ReportModel sample = ReportFixtures.sampleReport();
        ReportModel ignoredOnly = new ReportModel(sample.generatedAt(), sample.mode(), sample.headCommit(),
                sample.prodCommit(), new com.demo.sqlrisk.model.Summary(0, 0, 0, 0, 0, 1),
                java.util.Collections.<com.demo.sqlrisk.model.StatementResult>emptyList(),
                sample.ignored(), java.util.Collections.<com.demo.sqlrisk.model.SkippedItem>emptyList());
        String text = render(ignoredOnly, false);
        assertTrue(text.contains("高危 0 | 中危 0 | 低危 0 | SKIP 0 | 已忽略 1"), text);
        assertFalse(text.contains("无差异语句"), text);
    }
}
