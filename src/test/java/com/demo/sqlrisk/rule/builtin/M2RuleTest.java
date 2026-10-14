package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2 正例/反例/边界。 */
class M2RuleTest {

    private final M2Rule rule = new M2Rule();

    @Test
    void function_wrapped_column_fires() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE DATE(create_time) = '2026-01-01'"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("函数包裹"));
    }

    @Test
    void plain_column_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE create_time = '2026-01-01'")).isEmpty());
    }

    @Test
    void function_without_column_arg_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE id = LENGTH('abc')")).isEmpty(), "函数实参无列引用");
    }
}
