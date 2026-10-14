package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.DroppedBranch;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H3 正例/反例/边界（覆盖 SELECT * 无过滤场景 D19、无表语句不判定）。 */
class H3RuleTest {

    private final H3Rule rule = new H3Rule();

    @Test
    void select_without_where_and_limit_fires_high() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("SELECT * FROM t_order"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.HIGH, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("全表扫描"));
    }

    @Test
    void filtered_select_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT id FROM t_order WHERE id = 1")).isEmpty());
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT id FROM t_order LIMIT 10")).isEmpty());
    }

    @Test
    void tableless_select_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT 1")).isEmpty(), "无表无从谈全表扫描");
    }

    @Test
    void conservative_drop_adds_note() {
        List<Finding> findings = rule.check(
                RuleTestSupport.ctxWithDropped("SELECT * FROM t_order", DroppedBranch.Role.CONDITION));
        assertTrue(findings.get(0).message().contains("动态分支内"), findings.get(0).message());
    }
}
