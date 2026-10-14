package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/** M2 — 索引列被函数包裹（详细设计 §11.4）：比较表达式任一侧函数实参含列引用。 */
public final class M2Rule implements Rule {

    @Override
    public String id() {
        return "M2";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return 21;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        if (!ctx.facts().hasFunctionWrappedColumn()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "索引列被函数包裹，无法走索引", ctx));
    }
}
