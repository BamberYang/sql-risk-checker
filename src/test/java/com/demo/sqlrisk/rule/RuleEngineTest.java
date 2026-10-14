package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.config.RuleSetting;
import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.parser.ParsedSql;
import com.demo.sqlrisk.parser.ParsedStatement;
import com.demo.sqlrisk.parser.SqlSkeleton;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T7.1 自测：语句级短路（M8）、多骨架首命中即停（AC-4）、空骨架归 SKIP（v1.6）、禁用与级别覆盖（§11.3）。 */
class RuleEngineTest {

    @Test
    void m8_short_circuits_whole_statement() {
        ParsedStatement statement = RuleTestSupport.parsedStatement(
                "SELECT * FROM t_order WHERE id = ${column}");
        RuleRegistry registry = RuleRegistry.create(null);

        RuleEngine.Outcome outcome = RuleEngine.run(
                Collections.singletonList(statement), registry, null, null);

        List<StatementResult> results = outcome.results();
        assertEquals(1, results.size());
        StatementResult result = results.get(0);
        assertEquals(RiskLevel.MEDIUM, result.level());
        assertEquals(1, result.findings().size(), "短路：不再输出其他规则命中（无 L2 等）");
        assertEquals("M8", result.findings().get(0).ruleId());
        assertTrue(outcome.skipped().isEmpty(), "短路命中 → 不归 SKIP");
    }

    @Test
    void empty_skeleton_without_short_circuit_hit_goes_to_skip() {
        // 空骨架短路候选（含 ${} 解析失败，§8.5 结构性保证的产物形态）；禁用 M8（短路不命中）
        // → 归口 SKIP（v1.6 §11.2-④）——不再作为 level=null 结果输出
        ExtractedStatement source = new ExtractedStatement("m.s", StatementSource.ANNOTATION,
                ChangeType.MODIFIED, "A.java", 3,
                "SELECT * FROM t_order WHERE id = 1 ${ew.sqlSegment}",
                new AnnotationSqlText("SELECT * FROM t_order WHERE id = 1 ${ew.sqlSegment}"), null);
        ParsedStatement statement = new ParsedStatement(source, Collections.<ParsedSql>emptyList());
        Map<String, RuleSetting> settings = new LinkedHashMap<String, RuleSetting>();
        settings.put("M8", new RuleSetting("M8", RiskLevel.MEDIUM, false));
        com.demo.sqlrisk.config.AppConfig config = RuleTestSupport.config(
                Collections.<String>emptyList(), settings);
        RuleRegistry registry = RuleRegistry.create(config);

        RuleEngine.Outcome outcome = RuleEngine.run(
                Collections.singletonList(statement), registry, null, config);

        assertTrue(outcome.results().isEmpty(), "空骨架未命中短路 → 不进入 results（无法分析 ≠ 无风险）");
        assertEquals(1, outcome.skipped().size());
        SkippedItem item = outcome.skipped().get(0);
        assertEquals(SkippedItem.Kind.PARSE_FAILED, item.kind());
        assertTrue(item.reason().startsWith("解析失败："), item.reason());
        assertTrue(item.reason().contains("无可用 SQL 骨架"), item.reason());
        assertEquals("m.s", item.statementId());
        assertEquals("A.java", item.file());
        assertEquals(3, item.line());
    }

    @Test
    void skeleton_loop_stops_at_first_hit() {
        // 骨架 #0：L4 命中（LOW）；骨架 #1：若被评估将命中 H1（HIGH）——引擎必须在 #0 停止（AC-4）
        ParsedSql skeleton0 = RuleTestSupport.parsedSql(
                RuleTestSupport.orderMetadata(), SqlSkeleton.base("SELECT id FROM t_order WHERE id = 1"));
        ParsedSql skeleton1 = RuleTestSupport.parsedSql(
                RuleTestSupport.orderMetadata(), SqlSkeleton.base("UPDATE t_order SET status = 1"));
        ParsedStatement statement = RuleTestSupport.parsedStatement(Arrays.asList(skeleton0, skeleton1),
                "raw-container");
        RuleRegistry registry = RuleRegistry.create(null);

        List<StatementResult> results = RuleEngine.run(
                Collections.singletonList(statement), registry, RuleTestSupport.orderMetadata(), null).results();

        StatementResult result = results.get(0);
        assertEquals(RiskLevel.LOW, result.level(), "首个命中骨架为 #0（LOW），不得升级为 #1 的 H1");
        assertEquals(1, result.findings().size());
        assertEquals("L4", result.findings().get(0).ruleId());
        assertEquals("SELECT id FROM t_order WHERE id = 1", result.sql());
        assertEquals(0, result.findings().get(0).skeletonIndex());
    }

    @Test
    void disabled_rule_is_skipped() {
        ParsedStatement statement = RuleTestSupport.parsedStatement("UPDATE t_order SET status = 1");
        Map<String, RuleSetting> settings = new LinkedHashMap<String, RuleSetting>();
        settings.put("H1", new RuleSetting("H1", RiskLevel.HIGH, false));
        com.demo.sqlrisk.config.AppConfig config = RuleTestSupport.config(
                Collections.<String>emptyList(), settings);
        RuleRegistry registry = RuleRegistry.create(config);

        List<StatementResult> results = RuleEngine.run(
                Collections.singletonList(statement), registry, null, config).results();

        assertEquals(1, results.size());
        assertNull(results.get(0).level(), "H1 被禁用且无其他规则命中");
        assertTrue(results.get(0).findings().isEmpty());
    }

    @Test
    void configured_level_override_applies() {
        ParsedStatement statement = RuleTestSupport.parsedStatement("UPDATE t_order SET status = 1");
        Map<String, RuleSetting> settings = new LinkedHashMap<String, RuleSetting>();
        settings.put("H1", new RuleSetting("H1", RiskLevel.LOW, true));
        com.demo.sqlrisk.config.AppConfig config = RuleTestSupport.config(
                Collections.<String>emptyList(), settings);
        RuleRegistry registry = RuleRegistry.create(config);

        List<StatementResult> results = RuleEngine.run(
                Collections.singletonList(statement), registry, null, config).results();

        assertEquals(RiskLevel.LOW, results.get(0).level(), "级别覆盖生效（§11.3）");
        assertEquals(RiskLevel.LOW, results.get(0).findings().get(0).level());
    }
}
