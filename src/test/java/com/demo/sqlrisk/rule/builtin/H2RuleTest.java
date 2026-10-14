package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2 正例/反例/边界。 */
class H2RuleTest {

    private final H2Rule rule = new H2Rule();

    @Test
    void delete_without_where_fires_high() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("DELETE FROM t_order"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.HIGH, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("全表删除"));
    }

    @Test
    void delete_with_where_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("DELETE FROM t_order WHERE id = 1")).isEmpty());
    }

    @Test
    void update_kind_is_not_h2() {
        assertTrue(rule.check(RuleTestSupport.ctx("UPDATE t_order SET status = 1")).isEmpty());
    }
}
