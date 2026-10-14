package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;

/** H1 — UPDATE 无 WHERE（详细设计 §11.4）：保守折叠致 WHERE 整体消失时同样命中（D19，提示附注）。 */
public final class H1Rule implements Rule {

    @Override
    public String id() {
        return "H1";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (facts.kind() != QueryFacts.StatementKind.UPDATE || facts.hasWhere()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "UPDATE 语句缺少 WHERE，将全表更新，高危" + RuleSupport.conservativeNote(ctx), ctx));
    }
}
