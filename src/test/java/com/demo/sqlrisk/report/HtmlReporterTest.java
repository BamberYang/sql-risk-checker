package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.model.Summary;
import com.demo.sqlrisk.testutil.ReportFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 交付后增补（详设 §12.5）：HTML 阅读层——单文件离线自包含、结构/转义/确定性/写出语义。 */
class HtmlReporterTest {

    @TempDir
    Path tempDir;

    @Test
    void renders_single_file_structure_with_counts_and_sections() {
        String html = HtmlReporter.toHtml(ReportFixtures.sampleReport());

        assertTrue(html.startsWith("<!DOCTYPE html>"), "标准文档头");
        assertTrue(html.contains("<html lang=\"zh-CN\">"));
        assertTrue(html.contains("<title>SQL 风险检查报告 — DIFF a32658d</title>"), "标题含模式与 head 缩略");
        // 运行头
        assertTrue(html.contains("mode=DIFF") || html.contains(">DIFF<"), html);
        assertTrue(html.contains("prod") && html.contains("b7c1d2e"), "DIFF 展示 prod 缩略");
        assertTrue(html.contains("2026-10-14 08:00:00.000"), "生成时间（上海时区 yyyy-MM-dd HH:mm:ss.SSS）");
        // 统计与恒等式（含"通过"数量）
        assertTrue(html.contains("高危") && html.contains("中危") && html.contains("低危")
                && html.contains("SKIP") && html.contains("已忽略"), html);
        assertTrue(html.contains("<span class=\"lbl\">通过</span>"), "头部统计含通过数量：" + html);
        assertTrue(html.contains("提取总数 7 = 参与分析 4 + SKIP 2 + 已忽略 1"), "口径恒等式：" + html);
        assertTrue(html.contains(" + 通过 1；"), "恒等式行采用通过口径：" + html);
        // 筛选栏
        assertTrue(html.contains("id=\"f-rule\"") && html.contains("id=\"f-file\"") && html.contains("id=\"f-q\""));
        assertTrue(html.contains("class=\"f-level active\" data-level=\"ALL\""), "级别按钮");
        assertTrue(html.contains("<option value=\"H1\">H1</option>"), "规则下拉含命中规则");
        assertTrue(html.contains("<option value=\"src/main/resources/mapper/OrderMapper.xml\">"),
                "文件下拉含来源文件");
    }

    @Test
    void renders_cards_skips_ignored_and_inline_script() {
        String html = HtmlReporter.toHtml(ReportFixtures.sampleReport());

        // 分区与卡片
        assertTrue(html.contains("com.demo.mapper.OrderMapper.updateStatus"), "卡片展示 statementId");
        assertTrue(html.contains("高危明细（1）") && html.contains("中危明细（1）")
                && html.contains("低危明细（1）") && html.contains("通过（1）"), html);
        assertTrue(html.contains("data-level=\"HIGH\""), "卡片级别属性（筛选联动）");
        assertTrue(html.contains("data-rules=\"H1\""));
        assertTrue(html.contains("via com.demo.mapper.Common.baseColumns"), "片段派生命中标注 changedVia");
        assertTrue(html.contains("还原不确定"), "uncertain 标记");
        assertTrue(html.contains(ReportFixtures.H1_MESSAGE), "finding 消息");
        assertTrue(html.contains("骨架 #0"));
        // SKIP / 已忽略
        assertTrue(html.contains("SKIP（2）"), html);
        assertTrue(html.contains("PARSE_FAILED") && html.contains("UNSUPPORTED_CARRIER"));
        assertTrue(html.contains("已忽略（1）") && html.contains("whitelist"));
        // 页脚与交互
        assertTrue(html.contains("sql_risk_report.json"), "页脚注明 JSON 契约");
        assertTrue(html.contains("<script>") && html.contains("f-level"), "内联原生 JS 筛选");
        assertFalse(html.contains("http://") || html.contains("https://"), "离线自包含：无外部链接");
    }

    @Test
    void escapes_dynamic_text_including_raw_xml_sql() {
        Finding finding = new Finding("M8", RiskLevel.MEDIUM, "危险 <script>alert(1)</script>",
                "${w}", 0);
        StatementResult result = new StatementResult(
                StatementMeta.from(new ExtractedStatement("m.w", StatementSource.MAPPER_XML,
                        ChangeType.MODIFIED, "W.xml", 1, "raw", new Object(), null)),
                "<select id=\"x\">SELECT a < 1 AND b > 2</select>",
                Collections.singletonList(finding), RiskLevel.MEDIUM, false);
        ReportModel report = new ReportModel(ReportFixtures.generatedAt(), Mode.DIFF,
                ReportFixtures.HEAD_COMMIT, ReportFixtures.PROD_COMMIT,
                new Summary(1, 0, 1, 0, 0, 0), Collections.singletonList(result),
                Collections.<IgnoredItem>emptyList(), Collections.<com.demo.sqlrisk.model.SkippedItem>emptyList());

        String html = HtmlReporter.toHtml(report);
        assertTrue(html.contains("&lt;select id=&quot;x&quot;&gt;"), "XML 原文转义：" + html);
        assertTrue(html.contains("a &lt; 1 AND b &gt; 2"), "比较符转义");
        assertTrue(html.contains("危险 &lt;script&gt;alert(1)&lt;/script&gt;"), "消息转义");
        assertFalse(html.contains("<script>alert"), "不得出现未转义的注入片段");
    }

    @Test
    void full_mode_omits_prod_and_is_deterministic() {
        ReportModel full = ReportFixtures.sampleReport(Mode.FULL, null);
        String first = HtmlReporter.toHtml(full);
        String second = HtmlReporter.toHtml(full);
        assertEquals(first, second, "同一 ReportModel 输出逐字节一致");
        assertTrue(first.contains("prod —"), "FULL 模式 prod 占位");
    }

    @Test
    void write_creates_file_and_failure_exits_2() throws Exception {
        Path outDir = tempDir.resolve("nested/out");
        Path file = HtmlReporter.write(ReportFixtures.sampleReport(), outDir.toString());
        assertEquals(outDir.resolve("sql_risk_report.html").toAbsolutePath().normalize(), file);
        assertEquals(HtmlReporter.toHtml(ReportFixtures.sampleReport()),
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8), "文件内容 = HTML 序列化（UTF-8）");

        Path blocker = tempDir.resolve("blocker");
        Files.write(blocker, "not a dir".getBytes(StandardCharsets.UTF_8));
        ReportModel report = ReportFixtures.sampleReport();
        String blockerPath = blocker.toString();
        ReportException e = assertThrows(ReportException.class, () -> HtmlReporter.write(report, blockerPath));
        assertEquals(2, e.exitCode());
        assertTrue(e.getMessage().contains("HTML 报告写出失败"), e.getMessage());
    }

    @Test
    void escape_handles_all_special_characters() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlReporter.escape("&<>\"'"));
        assertEquals("", HtmlReporter.escape(null));
        assertEquals("中文 abc", HtmlReporter.escape("中文 abc"));
    }
}
