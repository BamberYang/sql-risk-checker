package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.config.RuleSetting;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 正例/反例/边界（关键字表来自配置；大小写与空白不敏感）。 */
class L1RuleTest {

    private final L1Rule rule = new L1Rule();

    @Test
    void configured_keyword_fires_low() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT * FROM t_order WHERE id = 1", null,
                RuleTestSupport.config(Collections.singletonList("SELECT *"), Collections.<String, RuleSetting>emptyMap())));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.LOW, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("SELECT *"));
    }

    @Test
    void no_config_or_no_match_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT * FROM t_order")).isEmpty(), "无配置");
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE id = 1",
                null, RuleTestSupport.config(Collections.singletonList("SELECT *"), Collections.<String, RuleSetting>emptyMap())))
                .isEmpty());
    }

    @Test
    void match_is_case_and_whitespace_insensitive() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "select   *   from t_order where id = 1", null,
                RuleTestSupport.config(Collections.singletonList("SELECT *"), Collections.<String, RuleSetting>emptyMap())));
        assertEquals(1, findings.size());
    }
}
