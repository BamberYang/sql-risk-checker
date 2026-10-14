package com.demo.sqlrisk.parser;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 语句事实（详细设计 §10.5，原则 P6）：规则唯一读取的结构化事实，由 {@link QueryFactsBuilder} 构建。
 *
 * <ul>
 *   <li>谓词列归属歧义统一记为 {@link #TABLE_UNRESOLVED}（DD-10，不参与 M1/M6 判定）；</li>
 *   <li>{@code orBranchColumns}：每个 OR 叶分支的列名集合（无归属；M6 以"任一表任一索引命中"近似判定）；</li>
 *   <li>{@code droppedBranches}/{@code uncertain}：由骨架透传（H1/H2/H3 提示附注用）。</li>
 * </ul>
 */
public final class QueryFacts {

    public enum StatementKind { SELECT, UPDATE, DELETE, INSERT, OTHER }

    public static final String TABLE_UNRESOLVED = "UNRESOLVED";

    private final StatementKind kind;
    private final boolean hasWhere;
    private final boolean hasLimit;
    private final boolean selectAll;
    private final Set<String> tables;
    private final Map<String, Set<String>> predicateColumnsByTable;
    private final Map<String, Set<String>> joinOnColumnsByTable;
    private final List<Set<String>> orBranchColumns;
    private final boolean hasOr;
    private final boolean hasJoinWithoutOn;
    private final boolean hasCommaJoin;
    private final boolean hasCrossTableWhereLink;
    private final boolean hasFunctionWrappedColumn;
    private final boolean hasLeadingWildcardLike;
    private final boolean hasImplicitCast;
    private final List<DroppedBranch> droppedBranches;
    private final boolean uncertain;

    QueryFacts(QueryFactsBuilder.Builder builder) {
        this.kind = builder.kind;
        this.hasWhere = builder.hasWhere;
        this.hasLimit = builder.hasLimit;
        this.selectAll = builder.selectAll;
        this.tables = Collections.unmodifiableSet(new TreeSet<String>(builder.tables));
        this.predicateColumnsByTable = immutableCopy(builder.predicateColumnsByTable);
        this.joinOnColumnsByTable = immutableCopy(builder.joinOnColumnsByTable);
        this.orBranchColumns = builder.orBranchColumns;
        this.hasOr = builder.hasOr;
        this.hasJoinWithoutOn = builder.hasJoinWithoutOn;
        this.hasCommaJoin = builder.hasCommaJoin;
        this.hasCrossTableWhereLink = builder.hasCrossTableWhereLink;
        this.hasFunctionWrappedColumn = builder.hasFunctionWrappedColumn;
        this.hasLeadingWildcardLike = builder.hasLeadingWildcardLike;
        this.hasImplicitCast = builder.hasImplicitCast;
        this.droppedBranches = builder.droppedBranches;
        this.uncertain = builder.uncertain;
    }

    private static Map<String, Set<String>> immutableCopy(Map<String, Set<String>> source) {
        java.util.Map<String, Set<String>> copy = new java.util.TreeMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(new TreeSet<String>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public StatementKind kind() {
        return kind;
    }

    public boolean hasWhere() {
        return hasWhere;
    }

    public boolean hasLimit() {
        return hasLimit;
    }

    public boolean selectAll() {
        return selectAll;
    }

    public Set<String> tables() {
        return tables;
    }

    public Map<String, Set<String>> predicateColumnsByTable() {
        return predicateColumnsByTable;
    }

    public Map<String, Set<String>> joinOnColumnsByTable() {
        return joinOnColumnsByTable;
    }

    public List<Set<String>> orBranchColumns() {
        return orBranchColumns;
    }

    public boolean hasOr() {
        return hasOr;
    }

    public boolean hasJoinWithoutOn() {
        return hasJoinWithoutOn;
    }

    public boolean hasCommaJoin() {
        return hasCommaJoin;
    }

    /** WHERE 关联列是否跨 ≥2 张表（M5 逗号连接判定的输入）。 */
    public boolean hasCrossTableWhereLink() {
        return hasCrossTableWhereLink;
    }

    public boolean hasFunctionWrappedColumn() {
        return hasFunctionWrappedColumn;
    }

    public boolean hasLeadingWildcardLike() {
        return hasLeadingWildcardLike;
    }

    public boolean hasImplicitCast() {
        return hasImplicitCast;
    }

    public List<DroppedBranch> droppedBranches() {
        return droppedBranches;
    }

    public boolean uncertain() {
        return uncertain;
    }

    /** 表 t 的 JOIN ON 关联列（可为空集）。 */
    public Set<String> joinOnColumnsOf(String table) {
        Set<String> columns = joinOnColumnsByTable.get(table);
        return columns == null ? Collections.<String>emptySet() : columns;
    }

    /** 表 t 的过滤列（谓词列 ∪ JOIN ON 列）；不含 UNRESOLVED。 */
    public Set<String> candidateColumnsOf(String table) {
        Set<String> out = new LinkedHashSet<String>();
        Set<String> predicates = predicateColumnsByTable.get(table);
        if (predicates != null) {
            out.addAll(predicates);
        }
        Set<String> joins = joinOnColumnsByTable.get(table);
        if (joins != null) {
            out.addAll(joins);
        }
        return out;
    }

    /** 是否存在任何可判定归属的过滤列（M1 不适用判定：无过滤职责的语句）。 */
    public boolean hasAttributedFilterColumns() {
        for (Map.Entry<String, Set<String>> entry : predicateColumnsByTable.entrySet()) {
            if (!TABLE_UNRESOLVED.equals(entry.getKey()) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        for (Map.Entry<String, Set<String>> entry : joinOnColumnsByTable.entrySet()) {
            if (!TABLE_UNRESOLVED.equals(entry.getKey()) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 谓词列中出现的全部列名（诊断用）。 */
    public Collection<String> allPredicateColumns() {
        Set<String> out = new TreeSet<String>();
        for (Set<String> columns : predicateColumnsByTable.values()) {
            out.addAll(columns);
        }
        return out;
    }
}
