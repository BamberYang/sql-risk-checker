package com.demo.sqlrisk.rule.builtin;

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
 * M2 — 索引列被函数包裹（详细设计 §11.4、§8.7）：比较表达式一侧为函数调用且实参含列引用，
 * 且该列**确为某索引的前缀列**（与 M1/M6 同口径）——索引本可用、被函数包裹后失效。
 *
 * <p>包裹列无索引前缀可失时不判定：该表"无索引可用"的判定归 M1（表不在元数据中同理），
 * 避免与 M1 重复报同一条语句（v1.13 修正：原实现不查元数据，无索引也照报）。</p>
 */
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
        QueryFacts facts = ctx.facts();
        if (!facts.hasFunctionWrappedColumn()) {
            return RuleSupport.none(); // 无函数包裹列：与元数据无关的快速通路
        }
        List<String> wrapped = new ArrayList<String>();
        for (String table : facts.tables()) {
            String note = wrappedNote(ctx, facts, table);
            if (note != null) {
                wrapped.add(note);
            }
        }
        if (wrapped.isEmpty()) {
            return RuleSupport.none();
        }
        return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                "索引列被函数包裹，无法走索引：" + String.join("；", wrapped), ctx));
    }

    /** 表级描述；（null = 该表无函数包裹列，或包裹列无索引前缀可失）。 */
    private static String wrappedNote(RuleContext ctx, QueryFacts facts, String table) {
        Set<String> columns = facts.functionWrappedColumnsOf(table);
        if (columns.isEmpty()) {
            return null;
        }
        Optional<TableMetadata> metadata = ctx.metadata() == null
                ? Optional.<TableMetadata>empty() : ctx.metadata().find(table);
        if (!metadata.isPresent()) {
            return null; // 表不在元数据中 = 无索引可失，该判定由 M1 承担（D13 同理）
        }
        List<String> prefixHit = new ArrayList<String>();
        for (String column : columns) {
            if (metadata.get().hasIndexStartingWith(column)) {
                prefixHit.add(column);
            }
        }
        return prefixHit.isEmpty() ? null : table + "(" + String.join(", ", prefixHit) + ")";
    }
}
