package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M8 正例/反例/边界（短路标记、原文扫描含 MP 惯用法）。 */
class M8RuleTest {

    private final M8Rule rule = new M8Rule();

    @Test
    void short_circuit_rule_metadata() {
        assertEquals(-100, rule.priority(), "语句级先行（§17 优先级表）");
        assertTrue(rule.shortCircuit());
    }

    @Test
    void dollar_placeholder_fires_medium() {
        List<Finding> findings = rule.check(RuleTestSupport.statementCtx(
                "SELECT SUM(amount) FROM t_order ${ew.customSqlSegment}"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("不再继续判断"));
    }

    @Test
    void hash_placeholder_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.statementCtx(
                "SELECT * FROM t_order WHERE id = #{id}")).isEmpty());
    }

    @Test
    void unterminated_dollar_is_not_matched() {
        assertTrue(rule.check(RuleTestSupport.statementCtx("SELECT ${broken FROM t")).isEmpty());
    }
}
