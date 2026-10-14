package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5 正例/反例/边界（JOIN 无 ON、逗号连接跨表关联判定）。 */
class M5RuleTest {

    private final M5Rule rule = new M5Rule();

    @Test
    void join_without_on_fires() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("SELECT * FROM a JOIN b"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("笛卡尔积"));
    }

    @Test
    void join_with_on_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT * FROM a JOIN b ON a.id = b.a_id")).isEmpty());
    }

    @Test
    void comma_join_without_cross_table_link_fires() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT * FROM a, b WHERE a.id = 1")).size() == 1);
    }

    @Test
    void comma_join_with_cross_table_link_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT * FROM a, b WHERE a.id = b.a_id")).isEmpty());
    }

    @Test
    void single_table_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT * FROM t_order")).isEmpty());
    }
}
