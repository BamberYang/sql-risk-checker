package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/** L4 — 未限制返回行数（详细设计 §11.4）：SELECT 有 WHERE 无 LIMIT；无 WHERE 场景由 H 组覆盖。 */
public final class L4Rule implements Rule {

    @Override
    public String id() {
        return "L4";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public int priority() {
        return 32;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (facts.kind() != QueryFacts.StatementKind.SELECT || facts.hasLimit() || !facts.hasWhere()) {
            return RuleSupport.none();
        }
        if (facts.tables().isEmpty()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "未限制返回行数，建议添加 LIMIT", ctx));
    }
}
