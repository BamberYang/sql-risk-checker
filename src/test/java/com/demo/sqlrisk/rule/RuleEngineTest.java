package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.config.RuleSetting;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.StatementResult;
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

/** T7.1 自测：语句级短路（M8）、多骨架首命中即停（AC-4）、禁用与级别覆盖（§11.3）。 */
class RuleEngineTest {

    @Test
    void m8_short_circuits_whole_statement() {
        ParsedStatement statement = RuleTestSupport.parsedStatement(
                "SELECT * FROM t_order WHERE id = ${column}");
        RuleRegistry registry = RuleRegistry.create(null);

        List<StatementResult> results = RuleEngine.run(
                Collections.singletonList(statement), registry, null, null);

        assertEquals(1, results.size());
        StatementResult result = results.get(0);
        assertEquals(RiskLevel.MEDIUM, result.level());
        assertEquals(1, result.findings().size(), "短路：不再输出其他规则命中（无 L2 等）");
        assertEquals("M8", result.findings().get(0).ruleId());
    }

    @Test
    void skeleton_loop_stops_at_first_hit() {
        // 骨架 #0：L4 命中（LOW）；骨架 #1：若被评估将命中 H1（HIGH）——引擎必须在 #0 停止（AC-4）
        ParsedSql skeleton0 = RuleTestSupport.parsedSql("SELECT id FROM t_order WHERE id = 1",
                RuleTestSupport.orderMetadata(), SqlSkeleton.base("SELECT id FROM t_order WHERE id = 1"));
        ParsedSql skeleton1 = RuleTestSupport.parsedSql("UPDATE t_order SET status = 1",
                RuleTestSupport.orderMetadata(), SqlSkeleton.base("UPDATE t_order SET status = 1"));
        ParsedStatement statement = RuleTestSupport.parsedStatement(Arrays.asList(skeleton0, skeleton1),
                "raw-container");
        RuleRegistry registry = RuleRegistry.create(null);

        List<StatementResult> results = RuleEngine.run(
                Collections.singletonList(statement), registry, RuleTestSupport.orderMetadata(), null);

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
                Collections.singletonList(statement), registry, null, config);

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
                Collections.singletonList(statement), registry, null, config);

        assertEquals(RiskLevel.LOW, results.get(0).level(), "级别覆盖生效（§11.3）");
        assertEquals(RiskLevel.LOW, results.get(0).findings().get(0).level());
    }
}
