package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.config.RuleSetting;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import com.demo.sqlrisk.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T7.1/§18.1：规则注册表——13 条内置、priority 执行顺序（M8 先行）、level/enabled 配置覆盖。 */
class RuleRegistryTest {

    private static final List<String> BUILTIN_IDS = Arrays.asList(
            "H1", "H2", "H3", "M1", "M2", "M3", "M4", "M5", "M6", "M8", "L1", "L2", "L4");

    @Test
    void registers_thirteen_builtin_rules() {
        RuleRegistry registry = RuleRegistry.create(null);
        assertEquals(13, registry.knownRuleIds().size());
        assertEquals(new java.util.LinkedHashSet<String>(BUILTIN_IDS), registry.knownRuleIds());
        assertEquals(13, registry.enabledRules().size(), "无配置时全部启用");
    }

    @Test
    void execution_order_is_priority_ascending_with_m8_first() {
        RuleRegistry registry = RuleRegistry.create(null);
        List<String> order = new ArrayList<String>();
        for (Rule rule : registry.enabledRules()) {
            order.add(rule.id());
        }
        assertEquals("M8", order.get(0), "M8 优先级 -100 必须先行（语句级短路）");
        assertEquals(Arrays.asList("H1", "H2", "H3"), order.subList(1, 4), "H 组 10~12");
        assertEquals(Arrays.asList("M1", "M2", "M3", "M4", "M5", "M6"), order.subList(4, 10), "M 组 20~25");
        assertEquals(Arrays.asList("L1", "L2", "L4"), order.subList(10, 13), "L 组 30~32");
    }

    @Test
    void config_can_disable_and_override_level() {
        Map<String, RuleSetting> settings = new LinkedHashMap<String, RuleSetting>();
        settings.put("H1", new RuleSetting("H1", RiskLevel.MEDIUM, true));
        settings.put("M1", new RuleSetting("M1", RiskLevel.MEDIUM, false));
        RuleRegistry registry = RuleRegistry.create(RuleTestSupport.config(
                Collections.<String>emptyList(), settings));

        assertFalse(registry.isEnabled("M1"), "配置禁用生效");
        List<String> enabled = new ArrayList<String>();
        for (Rule rule : registry.enabledRules()) {
            enabled.add(rule.id());
        }
        assertFalse(enabled.contains("M1"));
        assertEquals(12, enabled.size());

        Rule h1 = null;
        for (Rule rule : registry.enabledRules()) {
            if ("H1".equals(rule.id())) {
                h1 = rule;
            }
        }
        assertEquals(RiskLevel.HIGH, h1.defaultLevel(), "规则默认级别不变（硬编码）");
        assertEquals(RiskLevel.MEDIUM, registry.effectiveLevel(h1), "生效级别 = 配置覆盖");
    }

    @Test
    void uncovered_rules_keep_defaults() {
        RuleRegistry registry = RuleRegistry.create(null);
        for (Rule rule : registry.enabledRules()) {
            assertTrue(registry.isEnabled(rule.id()));
            assertEquals(rule.defaultLevel(), registry.effectiveLevel(rule));
        }
    }
}
