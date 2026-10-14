package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/**
 * L2 — SELECT *（详细设计 §11.4）：要求"已有过滤"（WHERE / LIMIT / JOIN），
 * 与 H3"无过滤"天然互斥；无表语句不判定。
 */
public final class L2Rule implements Rule {

    @Override
    public String id() {
        return "L2";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public int priority() {
        return 31;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (facts.kind() != QueryFacts.StatementKind.SELECT || !facts.selectAll()) {
            return RuleSupport.none();
        }
        if (facts.tables().isEmpty()) {
            return RuleSupport.none();
        }
        boolean filtered = facts.hasWhere() || facts.hasLimit() || facts.tables().size() > 1;
        if (!filtered) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "SELECT * 且已有过滤，建议显式列出字段", ctx));
    }
}
