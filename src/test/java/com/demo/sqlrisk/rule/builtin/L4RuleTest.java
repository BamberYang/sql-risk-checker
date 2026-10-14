package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L4 正例/反例/边界（无 WHERE 场景由 H 组覆盖）。 */
class L4RuleTest {

    private final L4Rule rule = new L4Rule();

    @Test
    void select_with_where_without_limit_fires_low() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("SELECT id FROM t_order WHERE id = 1"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.LOW, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("LIMIT"));
    }

    @Test
    void with_limit_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT id FROM t_order WHERE id = 1 LIMIT 10")).isEmpty());
    }

    @Test
    void without_where_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT id FROM t_order")).isEmpty(), "无 WHERE 由 H3 覆盖");
    }
}
