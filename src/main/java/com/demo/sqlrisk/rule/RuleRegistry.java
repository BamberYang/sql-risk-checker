package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.config.RuleSetting;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.rule.builtin.H1Rule;
import com.demo.sqlrisk.rule.builtin.H2Rule;
import com.demo.sqlrisk.rule.builtin.H3Rule;
import com.demo.sqlrisk.rule.builtin.L1Rule;
import com.demo.sqlrisk.rule.builtin.L2Rule;
import com.demo.sqlrisk.rule.builtin.L4Rule;
import com.demo.sqlrisk.rule.builtin.M1Rule;
import com.demo.sqlrisk.rule.builtin.M2Rule;
import com.demo.sqlrisk.rule.builtin.M3Rule;
import com.demo.sqlrisk.rule.builtin.M4Rule;
import com.demo.sqlrisk.rule.builtin.M5Rule;
import com.demo.sqlrisk.rule.builtin.M6Rule;
import com.demo.sqlrisk.rule.builtin.M8Rule;
import com.demo.sqlrisk.util.Diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则注册与配置覆盖（详细设计 §11.3）：内置 13 条 + {@link ServiceLoader} 外部规则；
 * level/enabled 由配置覆盖；执行顺序 = priority 升序（同优先级按 ID）——硬编码不可配置。
 */
public final class RuleRegistry {

    private final List<Rule> rules = new ArrayList<Rule>();
    private final Map<String, Rule> byId = new LinkedHashMap<String, Rule>();
    private final AppConfig config; // nullable

    private RuleRegistry(AppConfig config) {
        this.config = config;
    }

    public static RuleRegistry create(AppConfig config) {
        RuleRegistry registry = new RuleRegistry(config);
        registry.register(new H1Rule());
        registry.register(new H2Rule());
        registry.register(new H3Rule());
        registry.register(new M1Rule());
        registry.register(new M2Rule());
        registry.register(new M3Rule());
        registry.register(new M4Rule());
        registry.register(new M5Rule());
        registry.register(new M6Rule());
        registry.register(new M8Rule());
        registry.register(new L1Rule());
        registry.register(new L2Rule());
        registry.register(new L4Rule());

        for (RuleProvider provider : ServiceLoader.load(RuleProvider.class)) {
            for (Rule rule : provider.rules()) {
                if (registry.byId.containsKey(rule.id())) {
                    Diagnostics.warn("[警告] 外部规则与内置规则 ID 冲突，忽略: " + rule.id());
                } else {
                    registry.register(rule);
                }
            }
        }
        Collections.sort(registry.rules, Comparator.comparingInt(Rule::priority).thenComparing(Rule::id));
        return registry;
    }

    private void register(Rule rule) {
        rules.add(rule);
        byId.put(rule.id(), rule);
    }

    /** 启用规则（priority 升序；被配置禁用的除外）。 */
    public List<Rule> enabledRules() {
        return rules.stream().filter(rule -> isEnabled(rule.id())).collect(Collectors.toList());
    }

    public boolean isEnabled(String ruleId) {
        if (config == null) {
            return true;
        }
        Optional<RuleSetting> setting = config.rule(ruleId);
        return !setting.isPresent() || setting.get().enabled();
    }

    /** 生效级别：配置覆盖默认（§11.3）。 */
    public RiskLevel effectiveLevel(Rule rule) {
        if (config != null) {
            Optional<RuleSetting> setting = config.rule(rule.id());
            if (setting.isPresent()) {
                return setting.get().level();
            }
        }
        return rule.defaultLevel();
    }

    public Set<String> knownRuleIds() {
        return Collections.unmodifiableSet(byId.keySet());
    }
}
