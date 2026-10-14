package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/**
 * M5 — 笛卡尔积（详细设计 §11.4）：表数 ≥2 ∧（JOIN 无 ON ∨ 逗号连接且 WHERE 无跨表关联列，
 * CROSS JOIN 视为真笛卡尔）。
 */
public final class M5Rule implements Rule {

    @Override
    public String id() {
        return "M5";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return 24;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (facts.tables().size() < 2) {
            return RuleSupport.none();
        }
        boolean cartesian = facts.hasJoinWithoutOn()
                || (facts.hasCommaJoin() && !facts.hasCrossTableWhereLink());
        if (!cartesian) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "多表连接缺少关联条件，可能产生笛卡尔积：" + facts.tables(), ctx));
    }
}
