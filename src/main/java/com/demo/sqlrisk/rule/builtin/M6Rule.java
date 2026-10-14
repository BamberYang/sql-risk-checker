package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.metadata.TableMetadata;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M6 — OR 连接非索引条件（详细设计 §11.4）：
 * 存在 OR 叶分支不满足"含至少一个命中索引的列"（空分支如 1=1 视为不满足）；
 * 索引信息同 M1（表不在元数据 → 未命中）。
 */
public final class M6Rule implements Rule {

    @Override
    public String id() {
        return "M6";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return 25;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (!facts.hasOr()) {
            return RuleSupport.none();
        }
        for (Set<String> branch : facts.orBranchColumns()) {
            if (!branchHitsAnyIndex(facts, ctx, branch)) {
                return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                        "OR 连接包含未命中索引的条件，可能全表扫描", ctx));
            }
        }
        return RuleSupport.none();
    }

    private static boolean branchHitsAnyIndex(QueryFacts facts, RuleContext ctx, Set<String> branch) {
        if (branch.isEmpty()) {
            return false; // 无列分支（如 1=1）→ 不满足
        }
        for (String table : facts.tables()) {
            Optional<TableMetadata> metadata = ctx.metadata() == null
                    ? Optional.<TableMetadata>empty() : ctx.metadata().find(table);
            if (!metadata.isPresent()) {
                continue; // 表不在元数据 → 视为无索引（不满足）
            }
            for (String column : branch) {
                if (metadata.get().hasIndexStartingWith(column)) {
                    return true;
                }
            }
        }
        return false;
    }
}
