package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/** M4 — 隐式类型转换（详细设计 §11.4）：依据表元数据字段类型判定；类型缺失 → 不判定（§15）。 */
public final class M4Rule implements Rule {

    @Override
    public String id() {
        return "M4";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return 23;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        if (!ctx.facts().hasImplicitCast()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "存在隐式类型转换，可能无法命中索引", ctx));
    }
}
