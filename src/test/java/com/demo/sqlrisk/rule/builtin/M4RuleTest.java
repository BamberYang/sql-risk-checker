package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4 正例/反例/边界（类型缺失不判定，§15）。 */
class M4RuleTest {

    private final M4Rule rule = new M4Rule();

    @Test
    void numeric_column_vs_string_literal_fires() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = '123'", RuleTestSupport.orderMetadata(), null));
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("隐式类型转换"));
    }

    @Test
    void missing_metadata_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = '123'")).isEmpty(), "类型信息缺失 → 不判定");
    }

    @Test
    void string_column_vs_string_literal_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE order_no = '123'", RuleTestSupport.orderMetadata(), null)).isEmpty());
    }
}
