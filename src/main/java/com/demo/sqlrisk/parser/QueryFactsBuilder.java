package com.demo.sqlrisk.parser;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNotExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLUnaryExpr;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.demo.sqlrisk.metadata.FieldMeta;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.metadata.TableMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 语句事实提取（详细设计 §10.5，DD-10；v1.8 方言化）：自定义 AST 遍历（不依赖 SchemaStatVisitor，行为可控可测）；
 * {@code hasLimit} 由 {@link SqlDialect#hasRowLimit} 提供（缺省 MySQL，行为与迁移前一致）。
 *
 * <p>限制（保守方向已注明）：UNION 等无单一 queryBlock 的查询简化为"有过滤 + uncertain"；
 * 子查询（IN/EXISTS/派生表）内容隔离，不参与本语句事实。</p>
 */
public final class QueryFactsBuilder {

    private static final List<String> NUMERIC_TYPES = Arrays.asList(
            "BIGINT", "INT", "TINYINT", "SMALLINT", "MEDIUMINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "BIT");
    private static final List<String> STRING_TYPES = Arrays.asList(
            "CHAR", "VARCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "ENUM", "SET");

    private QueryFactsBuilder() {
    }

    public static QueryFacts build(SQLStatement ast, MetadataProvider metadata, SqlSkeleton skeleton) {
        return build(ast, metadata, skeleton, null);
    }

    /** @param dialect 可为 null（缺省 MySQL 方言）；分页事实（hasLimit）由方言识别 */
    public static QueryFacts build(SQLStatement ast, MetadataProvider metadata, SqlSkeleton skeleton,
                                   SqlDialect dialect) {
        Builder b = new Builder(metadata, dialect == null ? new MysqlDialect() : dialect);
        if (ast instanceof SQLSelectStatement) {
            buildSelect((SQLSelectStatement) ast, b);
        } else if (ast instanceof SQLUpdateStatement) {
            SQLUpdateStatement update = (SQLUpdateStatement) ast;
            b.kind = QueryFacts.StatementKind.UPDATE;
            collectTableSource(update.getTableSource(), b);
            b.hasWhere = update.getWhere() != null;
            if (update.getWhere() != null) {
                walkCondition(update.getWhere(), b);
            }
            b.hasLimit = b.dialect.hasRowLimit(ast);
        } else if (ast instanceof SQLDeleteStatement) {
            SQLDeleteStatement delete = (SQLDeleteStatement) ast;
            b.kind = QueryFacts.StatementKind.DELETE;
            collectTableSource(delete.getTableSource(), b);
            b.hasWhere = delete.getWhere() != null;
            if (delete.getWhere() != null) {
                walkCondition(delete.getWhere(), b);
            }
            b.hasLimit = b.dialect.hasRowLimit(ast);
        } else if (ast instanceof SQLInsertStatement) {
            b.kind = QueryFacts.StatementKind.INSERT;
            collectTableSource(((SQLInsertStatement) ast).getTableSource(), b);
        } else {
            b.kind = QueryFacts.StatementKind.OTHER;
            b.uncertain = true;
        }

        b.droppedBranches = skeleton.droppedBranches();
        b.hasLeadingWildcardLike = b.hasLeadingWildcardLike || skeleton.leadingWildcardLike();
        b.uncertain = b.uncertain || skeleton.isUncertain();
        b.finalizeCrossTableLink();
        return new QueryFacts(b);
    }

    // ---------- SELECT ----------

    private static void buildSelect(SQLSelectStatement select, Builder b) {
        b.kind = QueryFacts.StatementKind.SELECT;
        SQLSelectQueryBlock block = select.getSelect().getQueryBlock();
        if (block == null) {
            // UNION 等：保守取"有过滤"避免误报 H3，并标记 uncertain（限制见类注释）
            b.hasWhere = true;
            b.uncertain = true;
            return;
        }
        b.selectAll = hasSelectAll(block);
        collectTableSource(block.getFrom(), b);
        collectJoins(block.getFrom(), b);
        b.hasWhere = block.getWhere() != null;
        if (block.getWhere() != null) {
            walkCondition(block.getWhere(), b);
        }
        b.hasLimit = b.dialect.hasRowLimit(select); // 分页语义完全归方言（MySQL 与迁移前逐字节一致；PG 纯 OFFSET 不算限制）
    }

    private static boolean hasSelectAll(SQLSelectQueryBlock block) {
        for (SQLSelectItem item : block.getSelectList()) {
            SQLExpr expr = item.getExpr();
            if (expr instanceof SQLAllColumnExpr) {
                return true;
            }
            if (expr instanceof SQLPropertyExpr && "*".equals(((SQLPropertyExpr) expr).getName())) {
                return true;
            }
        }
        return false;
    }

    // ---------- 表与 JOIN ----------

    private static void collectTableSource(SQLTableSource source, Builder b) {
        if (source == null) {
            return;
        }
        if (source instanceof SQLJoinTableSource) {
            collectTableSource(((SQLJoinTableSource) source).getLeft(), b);
            collectTableSource(((SQLJoinTableSource) source).getRight(), b);
            return;
        }
        if (source instanceof SQLExprTableSource) {
            SQLExprTableSource table = (SQLExprTableSource) source;
            if (table.getName() != null) {
                String name = table.getName().getSimpleName();
                b.tables.add(name);
                b.aliasToTable.put(name.toLowerCase(Locale.ROOT), name);
                if (table.getAlias() != null && !table.getAlias().isEmpty()) {
                    b.aliasToTable.put(table.getAlias().toLowerCase(Locale.ROOT), name);
                }
            }
        }
        // 派生表（SQLSubqueryTableSource）隔离：不收集
    }

    private static void collectJoins(SQLTableSource source, Builder b) {
        if (!(source instanceof SQLJoinTableSource)) {
            return;
        }
        SQLJoinTableSource join = (SQLJoinTableSource) source;
        if (join.getJoinType() == SQLJoinTableSource.JoinType.COMMA) {
            b.hasCommaJoin = true;
        } else if (join.getCondition() == null) {
            b.hasJoinWithoutOn = true; // INNER/LEFT/CROSS 等无 ON → 关联缺失（CROSS 即笛卡尔）
        }
        if (join.getCondition() != null) {
            recordColumns(join.getCondition(), b.joinOnColumnsByTable, b, null);
        }
        collectJoins(join.getLeft(), b);
        collectJoins(join.getRight(), b);
    }

    // ---------- WHERE ----------

    private static void walkCondition(SQLExpr expr, Builder b) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            if (binary.getOperator() == SQLBinaryOperator.BooleanOr) {
                b.hasOr = true;
                List<SQLExpr> leaves = new ArrayList<SQLExpr>();
                flattenOr(binary, leaves);
                for (SQLExpr leaf : leaves) {
                    Set<String> branchColumns = new TreeSet<String>();
                    recordLeaf(leaf, branchColumns, b);
                    b.orBranchColumns.add(Collections.unmodifiableSet(branchColumns));
                }
                return;
            }
            if (binary.getOperator() == SQLBinaryOperator.BooleanAnd) {
                walkCondition(binary.getLeft(), b);
                walkCondition(binary.getRight(), b);
                return;
            }
            recordLeaf(binary, null, b);
            return;
        }
        recordLeaf(expr, null, b);
    }

    private static void flattenOr(SQLExpr expr, List<SQLExpr> out) {
        if (expr instanceof SQLBinaryOpExpr
                && ((SQLBinaryOpExpr) expr).getOperator() == SQLBinaryOperator.BooleanOr) {
            flattenOr(((SQLBinaryOpExpr) expr).getLeft(), out);
            flattenOr(((SQLBinaryOpExpr) expr).getRight(), out);
        } else {
            out.add(expr);
        }
    }

    private static void recordLeaf(SQLExpr expr, Set<String> branchColumns, Builder b) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            recordBinaryLeaf((SQLBinaryOpExpr) expr, branchColumns, b);
            return;
        }
        if (expr instanceof SQLInListExpr) {
            recordColumns(((SQLInListExpr) expr).getExpr(), b.predicateColumnsByTable, b, branchColumns);
            return;
        }
        if (expr instanceof SQLBetweenExpr) {
            recordColumns(((SQLBetweenExpr) expr).getTestExpr(), b.predicateColumnsByTable, b, branchColumns);
            return;
        }
        if (expr instanceof SQLNotExpr) {
            recordLeaf(((SQLNotExpr) expr).getExpr(), branchColumns, b);
            return;
        }
        if (expr instanceof SQLInSubQueryExpr || expr instanceof SQLExistsExpr
                || expr instanceof SQLQueryExpr) {
            return; // 子查询隔离
        }
        recordColumns(expr, b.predicateColumnsByTable, b, branchColumns);
    }

    /** 二元叶：布尔连接递归展开；比较运算符 → 函数包裹 / 前导通配符 / 隐式转换判定。 */
    private static void recordBinaryLeaf(SQLBinaryOpExpr binary, Set<String> branchColumns, Builder b) {
        SQLBinaryOperator op = binary.getOperator();
        if (op == SQLBinaryOperator.BooleanAnd || op == SQLBinaryOperator.BooleanOr) {
            recordLeaf(binary.getLeft(), branchColumns, b);
            recordLeaf(binary.getRight(), branchColumns, b);
            return;
        }
        recordColumns(binary.getLeft(), b.predicateColumnsByTable, b, branchColumns);
        recordColumns(binary.getRight(), b.predicateColumnsByTable, b, branchColumns);
        if (!isComparison(op)) {
            return;
        }
        recordFunctionWrapped(binary.getLeft(), b);
        recordFunctionWrapped(binary.getRight(), b);
        if (isLeadingWildcardLike(binary)) {
            b.hasLeadingWildcardLike = true;
        }
        checkImplicitCast(binary.getLeft(), binary.getRight(), b);
        checkImplicitCast(binary.getRight(), binary.getLeft(), b);
    }

    /** LIKE/NOT LIKE 且右侧字面量以 % 开头（前导通配符，M3 判定输入）。 */
    private static boolean isLeadingWildcardLike(SQLBinaryOpExpr binary) {
        SQLBinaryOperator op = binary.getOperator();
        return (op == SQLBinaryOperator.Like || op == SQLBinaryOperator.NotLike)
                && binary.getRight() instanceof SQLCharExpr
                && ((SQLCharExpr) binary.getRight()).getText().startsWith("%");
    }

    private static boolean isComparison(SQLBinaryOperator op) {
        switch (op) {
            case Equality:
            case NotEqual:
            case LessThan:
            case LessThanOrEqual:
            case GreaterThan:
            case GreaterThanOrEqual:
            case Like:
            case NotLike:
                return true;
            default:
                return false;
        }
    }

    /** 收集表达式中的列引用并归表（含 owner 归属与歧义隔离，DD-10）。 */
    private static void recordColumns(SQLExpr expr, Map<String, Set<String>> target, Builder b,
                                      Set<String> branchColumns) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expr;
            String owner = ownerName(property);
            addColumn(b, owner, property.getName(), branchColumns, target);
            return;
        }
        if (expr instanceof SQLIdentifierExpr) {
            addColumn(b, null, ((SQLIdentifierExpr) expr).getName(), branchColumns, target);
            return;
        }
        for (SQLExpr child : childrenOf(expr)) {
            recordColumns(child, target, b, branchColumns);
        }
    }

    private static String ownerName(SQLPropertyExpr property) {
        SQLExpr owner = property.getOwner();
        if (owner instanceof SQLIdentifierExpr) {
            return ((SQLIdentifierExpr) owner).getName();
        }
        if (owner instanceof SQLPropertyExpr) {
            return ((SQLPropertyExpr) owner).getName();
        }
        return null;
    }

    private static void addColumn(Builder b, String owner, String column, Set<String> branchColumns,
                                  Map<String, Set<String>> target) {
        if (column == null || column.isEmpty()) {
            return;
        }
        if (branchColumns != null) {
            branchColumns.add(column);
        }
        String table;
        if (owner != null) {
            String mapped = b.aliasToTable.get(owner.toLowerCase(Locale.ROOT));
            if (mapped == null && containsIgnoreCase(b.tables, owner)) {
                mapped = lowerCaseOf(b.tables, owner);
            }
            table = mapped != null ? mapped : QueryFacts.TABLE_UNRESOLVED;
        } else {
            table = b.tables.size() == 1 ? b.tables.iterator().next() : QueryFacts.TABLE_UNRESOLVED;
        }
        Set<String> columns = target.get(table);
        if (columns == null) {
            columns = new TreeSet<String>();
            target.put(table, columns);
        }
        columns.add(column);
    }

    private static boolean containsIgnoreCase(Set<String> values, String target) {
        return lowerCaseOf(values, target) != null;
    }

    private static String lowerCaseOf(Set<String> values, String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return value;
            }
        }
        return null;
    }

    private static List<SQLExpr> childrenOf(SQLExpr expr) {
        if (expr instanceof SQLMethodInvokeExpr) {
            return new ArrayList<SQLExpr>(((SQLMethodInvokeExpr) expr).getArguments());
        }
        if (expr instanceof SQLBinaryOpExpr) {
            List<SQLExpr> children = new ArrayList<SQLExpr>(2);
            children.add(((SQLBinaryOpExpr) expr).getLeft());
            children.add(((SQLBinaryOpExpr) expr).getRight());
            return children;
        }
        if (expr instanceof SQLUnaryExpr) {
            return Collections.singletonList(((SQLUnaryExpr) expr).getExpr());
        }
        if (expr instanceof SQLCastExpr) {
            return Collections.singletonList(((SQLCastExpr) expr).getExpr());
        }
        if (expr instanceof SQLInListExpr) {
            return new ArrayList<SQLExpr>(((SQLInListExpr) expr).getTargetList());
        }
        if (expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr between = (SQLBetweenExpr) expr;
            return Arrays.asList(between.getBeginExpr(), between.getEndExpr());
        }
        return Collections.emptyList();
    }

    /**
     * 函数包裹列（M2 判定输入）：比较表达式一侧为函数调用且实参含列引用时，
     * 置事实标志并把实参中的列**按表归属**收集（归属口径与谓词列一致，供 M2 查元数据）。
     */
    private static void recordFunctionWrapped(SQLExpr expr, Builder b) {
        if (!(expr instanceof SQLMethodInvokeExpr)) {
            return;
        }
        for (SQLExpr arg : ((SQLMethodInvokeExpr) expr).getArguments()) {
            if (containsColumnRef(arg)) {
                b.hasFunctionWrappedColumn = true;
                recordColumns(arg, b.functionWrappedColumnsByTable, b, null);
            }
        }
    }

    private static boolean containsColumnRef(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr || expr instanceof SQLPropertyExpr) {
            return true;
        }
        for (SQLExpr child : childrenOf(expr)) {
            if (containsColumnRef(child)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 隐式类型转换（M4，需元数据） ----------

    private static void checkImplicitCast(SQLExpr columnSide, SQLExpr literalSide, Builder b) {
        if (b.metadata == null) {
            return;
        }
        Optional<String> type = columnType(columnSide, b);
        if (!type.isPresent()) {
            return;
        }
        String upper = type.get().toUpperCase(Locale.ROOT);
        if (literalSide instanceof SQLCharExpr && startsWithAny(upper, NUMERIC_TYPES)) {
            b.hasImplicitCast = true;
        }
        if ((literalSide instanceof SQLIntegerExpr || literalSide instanceof SQLNumberExpr)
                && startsWithAny(upper, STRING_TYPES)) {
            b.hasImplicitCast = true;
        }
    }

    private static Optional<String> columnType(SQLExpr expr, Builder b) {
        String table = null;
        String column = null;
        if (expr instanceof SQLIdentifierExpr) {
            column = ((SQLIdentifierExpr) expr).getName();
            if (b.tables.size() == 1) {
                table = b.tables.iterator().next();
            }
        } else if (expr instanceof SQLPropertyExpr) {
            column = ((SQLPropertyExpr) expr).getName();
            String owner = ownerName((SQLPropertyExpr) expr);
            if (owner != null) {
                String mapped = b.aliasToTable.get(owner.toLowerCase(Locale.ROOT));
                table = mapped != null ? mapped : owner;
            }
        }
        if (table == null || column == null || b.metadata == null) {
            return Optional.empty();
        }
        Optional<TableMetadata> metadata = b.metadata.find(table);
        if (!metadata.isPresent()) {
            return Optional.empty(); // 类型信息缺失 → 不判定（§15）
        }
        Optional<FieldMeta> field = metadata.get().field(column);
        return field.map(FieldMeta::type);
    }

    private static boolean startsWithAny(String upperType, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (upperType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ---------- Builder ----------

    static final class Builder {

        final MetadataProvider metadata; // nullable
        final SqlDialect dialect;        // 分页事实识别（v1.8）
        QueryFacts.StatementKind kind = QueryFacts.StatementKind.OTHER;
        boolean hasWhere;
        boolean hasLimit;
        boolean selectAll;
        final Set<String> tables = new TreeSet<String>();
        final Map<String, Set<String>> predicateColumnsByTable = new TreeMap<String, Set<String>>();
        final Map<String, Set<String>> joinOnColumnsByTable = new TreeMap<String, Set<String>>();
        final Map<String, Set<String>> functionWrappedColumnsByTable = new TreeMap<String, Set<String>>();
        final List<Set<String>> orBranchColumns = new ArrayList<Set<String>>();
        boolean hasOr;
        boolean hasJoinWithoutOn;
        boolean hasCommaJoin;
        boolean hasCrossTableWhereLink;
        boolean hasFunctionWrappedColumn;
        boolean hasLeadingWildcardLike;
        boolean hasImplicitCast;
        final Map<String, String> aliasToTable = new TreeMap<String, String>();
        List<DroppedBranch> droppedBranches = Collections.emptyList();
        boolean uncertain;

        Builder(MetadataProvider metadata, SqlDialect dialect) {
            this.metadata = metadata;
            this.dialect = dialect;
        }

        void finalizeCrossTableLink() {
            int linked = 0;
            for (Map.Entry<String, Set<String>> entry : predicateColumnsByTable.entrySet()) {
                if (!QueryFacts.TABLE_UNRESOLVED.equals(entry.getKey()) && !entry.getValue().isEmpty()) {
                    linked++;
                }
            }
            hasCrossTableWhereLink = linked >= 2;
        }
    }
}
