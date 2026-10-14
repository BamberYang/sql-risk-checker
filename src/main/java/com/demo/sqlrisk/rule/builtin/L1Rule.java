package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;
import com.demo.sqlrisk.util.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** L1 — 不推荐写法关键字（详细设计 §11.4）：语句原文规范化后包含配置关键字（大小写不敏感）。 */
public final class L1Rule implements Rule {

    @Override
    public String id() {
        return "L1";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        AppConfig config = ctx.config();
        if (config == null || config.lowRiskKeywords().isEmpty()) {
            return RuleSupport.none();
        }
        String normalized = TextUtils.collapseWhitespace(ctx.rawText()).toUpperCase(Locale.ROOT);
        List<Finding> findings = new ArrayList<Finding>();
        for (String keyword : config.lowRiskKeywords()) {
            String needle = TextUtils.collapseWhitespace(keyword).toUpperCase(Locale.ROOT);
            if (!needle.isEmpty() && normalized.contains(needle)) {
                findings.add(RuleSupport.finding(id(), defaultLevel(),
                        "使用了不推荐写法：" + keyword, ctx));
            }
        }
        return findings;
    }
}
