package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.metadata.TableMetadata;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M1 — 条件列未命中索引（详细设计 §11.4、§8.7）：
 * 过滤列（谓词 ∪ JOIN ON）非空 ∧ 存在表无任何索引前缀列命中；
 * 表不在元数据中 → 默认无索引（宁可误报，D13）；无归属过滤列的语句不判定。
 */
public final class M1Rule implements Rule {

    @Override
    public String id() {
        return "M1";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean shortCircuit() {
        return false;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (!facts.hasAttributedFilterColumns()) {
            return RuleSupport.none(); // 无过滤职责（如无 WHERE 语句）：由 H 组覆盖
        }
        List<String> unindexed = new ArrayList<String>();
        for (String table : facts.tables()) {
            Set<String> columns = facts.candidateColumnsOf(table);
            if (columns.isEmpty()) {
                continue;
            }
            String columnList = "(" + String.join(", ", columns) + ")";
            Optional<TableMetadata> metadata = ctx.metadata() == null
                    ? Optional.<TableMetadata>empty() : ctx.metadata().find(table);
            if (!metadata.isPresent()) {
                unindexed.add(table + columnList + "（表不在元数据中，默认无索引）");
                continue;
            }
            boolean hit = false;
            for (String column : columns) {
                if (metadata.get().hasIndexStartingWith(column)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                unindexed.add(table + columnList);
            }
        }
        if (unindexed.isEmpty()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "条件列未命中索引：" + String.join("；", unindexed), ctx));
    }
}
