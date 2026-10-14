package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/**
 * M3 — 前导通配符 LIKE（详细设计 §11.4）：
 * 字面量模式以 % 开头；Lambda {@code likeLeft()} 的确定性前导 % 亦命中；非字面量不判定。
 */
public final class M3Rule implements Rule {

    @Override
    public String id() {
        return "M3";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return 22;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        if (!ctx.facts().hasLeadingWildcardLike()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "前导通配符 LIKE 无法走索引", ctx));
    }
}
