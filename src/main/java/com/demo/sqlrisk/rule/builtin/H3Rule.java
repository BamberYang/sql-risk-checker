package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/**
 * H3 — SELECT 全表扫描（详细设计 §11.4）：无 WHERE 且无 LIMIT；
 * 覆盖 {@code SELECT *} 且无过滤的无限制场景（D19）；无表的语句（如 {@code SELECT 1}）不判定。
 */
public final class H3Rule implements Rule {

    @Override
    public String id() {
        return "H3";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public int priority() {
        return 12;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (facts.kind() != QueryFacts.StatementKind.SELECT || facts.hasWhere() || facts.hasLimit()) {
            return RuleSupport.none();
        }
        if (facts.tables().isEmpty()) {
            return RuleSupport.none(); // 无表无从谈全表扫描（如 SELECT 1）
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "SELECT 无有效过滤条件（无 WHERE 且无 LIMIT），存在全表扫描风险"
                        + RuleSupport.conservativeNote(ctx), ctx));
    }
}
