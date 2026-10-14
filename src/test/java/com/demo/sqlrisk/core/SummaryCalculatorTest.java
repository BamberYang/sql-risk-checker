package com.demo.sqlrisk.core;

import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.model.Summary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T8.2：汇总口径（详细设计 §13.4）：最高级别互斥归类、skip/ignored 独立计数、恒等式。 */
class SummaryCalculatorTest {

    private static StatementResult result(String id, RiskLevel level) {
        StatementMeta meta = StatementMeta.from(new ExtractedStatement(id, StatementSource.MAPPER_XML,
                ChangeType.MODIFIED, "A.xml", 1, "raw", new Object(), null));
        return new StatementResult(meta, "SELECT 1", Collections.emptyList(), level, false);
    }

    @Test
    void counts_by_highest_level_exclusively() {
        List<StatementResult> results = Arrays.asList(
                result("a", RiskLevel.HIGH),
                result("b", RiskLevel.HIGH),
                result("c", RiskLevel.MEDIUM),
                result("d", RiskLevel.LOW),
                result("e", null)); // 无命中：计入 total，不进入任何级别桶
        Summary summary = SummaryCalculator.calc(results,
                Collections.<IgnoredItem>emptyList(), Collections.<SkippedItem>emptyList());
        assertEquals(5, summary.total());
        assertEquals(2, summary.high());
        assertEquals(1, summary.medium());
        assertEquals(1, summary.low());
        assertEquals(0, summary.skip());
        assertEquals(0, summary.ignored());
        assertTrue(summary.high() + summary.medium() + summary.low() <= summary.total(),
                "级别互斥：三桶之和 ≤ total（无命中语句不落入任何桶）");
    }

    @Test
    void skip_and_ignored_counted_independently_from_results() {
        List<StatementResult> results = Collections.singletonList(result("a", RiskLevel.LOW));
        List<IgnoredItem> ignored = Arrays.asList(
                new IgnoredItem("x", "A.xml", 1, "whitelist"),
                new IgnoredItem("y", "A.xml", 2, "whitelist"),
                new IgnoredItem("z", "A.xml", 3, "whitelist"));
        List<SkippedItem> skipped = Arrays.asList(
                new SkippedItem("p", "B.xml", 1, SkippedItem.Kind.PARSE_FAILED, "解析失败：x"),
                new SkippedItem(null, "C.java", 0, SkippedItem.Kind.READ_ERROR, "编码失败"));
        Summary summary = SummaryCalculator.calc(results, ignored, skipped);
        assertEquals(1, summary.total());
        assertEquals(1, summary.low());
        assertEquals(3, summary.ignored());
        assertEquals(2, summary.skip());
    }

    @Test
    void identity_holds_extracted_total_equals_total_plus_skip_plus_ignored() {
        // 模拟 10 条提取语句：5 进入分析（2H/2M/1L）+ 3 白名单 + 2 解析失败
        List<StatementResult> results = new ArrayList<StatementResult>();
        results.add(result("h1", RiskLevel.HIGH));
        results.add(result("h2", RiskLevel.HIGH));
        results.add(result("m1", RiskLevel.MEDIUM));
        results.add(result("m2", RiskLevel.MEDIUM));
        results.add(result("l1", RiskLevel.LOW));
        List<IgnoredItem> ignored = Arrays.asList(
                new IgnoredItem("i1", "A.xml", 1, "whitelist"),
                new IgnoredItem("i2", "A.xml", 2, "whitelist"),
                new IgnoredItem("i3", "A.xml", 3, "whitelist"));
        List<SkippedItem> skipped = Arrays.asList(
                new SkippedItem("s1", "B.xml", 1, SkippedItem.Kind.PARSE_FAILED, "解析失败：x"),
                new SkippedItem("s2", "B.xml", 2, SkippedItem.Kind.PARSE_FAILED, "解析失败：y"));

        Summary summary = SummaryCalculator.calc(results, ignored, skipped);
        int extractedTotal = results.size() + ignored.size() + skipped.size();
        assertEquals(10, extractedTotal);
        assertEquals(extractedTotal, summary.total() + summary.skip() + summary.ignored(),
                "恒等式：提取总数 = total + skip + ignored（§13.4）");
    }

    @Test
    void empty_inputs_yield_zero_everywhere() {
        Summary summary = SummaryCalculator.calc(Collections.<StatementResult>emptyList(),
                Collections.<IgnoredItem>emptyList(), Collections.<SkippedItem>emptyList());
        assertEquals(0, summary.total());
        assertEquals(0, summary.high());
        assertEquals(0, summary.medium());
        assertEquals(0, summary.low());
        assertEquals(0, summary.skip());
        assertEquals(0, summary.ignored());
    }
}
