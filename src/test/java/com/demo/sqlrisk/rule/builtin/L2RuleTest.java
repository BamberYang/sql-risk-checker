package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2 正例/反例/边界（"已有过滤"要求，与 H3 天然互斥）。 */
class L2RuleTest {

    private final L2Rule rule = new L2Rule();

    @Test
    void select_star_with_filter_fires_low() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("SELECT * FROM t_order WHERE id = 1"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.LOW, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("显式列出字段"));
    }

    @Test
    void explicit_columns_do_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT id FROM t_order WHERE id = 1")).isEmpty());
    }

    @Test
    void select_star_without_filter_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT * FROM t_order")).isEmpty(), "无过滤场景由 H3 覆盖");
    }

    @Test
    void limit_counts_as_filter() {
        assertEquals(1, rule.check(RuleTestSupport.ctx("SELECT * FROM t_order LIMIT 10")).size());
    }
}
