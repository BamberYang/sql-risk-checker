package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.DroppedBranch;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H1 正例/反例/边界 + 保守折叠提示附注（D19）。 */
class H1RuleTest {

    private final H1Rule rule = new H1Rule();

    @Test
    void update_without_where_fires_high() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("UPDATE t_order SET status = 1"));
        assertEquals(1, findings.size());
        assertEquals("H1", findings.get(0).ruleId());
        assertEquals(RiskLevel.HIGH, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("全表更新"));
    }

    @Test
    void update_with_where_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("UPDATE t_order SET status = 1 WHERE id = 1")).isEmpty());
    }

    @Test
    void select_kind_is_not_h1() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT * FROM t_order")).isEmpty());
    }

    @Test
    void conservative_drop_adds_note() {
        List<Finding> findings = rule.check(
                RuleTestSupport.ctxWithDropped("UPDATE t_order SET status = 1", DroppedBranch.Role.CONDITION));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("动态分支内，按保守策略视为不成立"), findings.get(0).message());
    }
}
