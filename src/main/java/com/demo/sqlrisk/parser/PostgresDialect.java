package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;

import java.util.regex.Pattern;

/**
 * PostgreSQL 方言（详细设计 §10.7，v1.10/FR-9/D25）：
 * 分页识别 = {@code LIMIT n [OFFSET m]} 结构节点，或 {@code FETCH FIRST|NEXT n ROWS ONLY} 文本形态
 * （Druid 1.2.23 对 PG 的 FETCH 子句未归入 limit 节点，在方言内以受限文本判定收敛）。
 *
 * <p>保守语义：仅 {@code OFFSET}（不限制行数，仍全表扫描）与 {@code UPDATE}/{@code DELETE}
 * （PG 语法不支持行限制子句）→ false（按无 LIMIT 处理，宁可误报）。</p>
 */
public final class PostgresDialect implements SqlDialect {

    /** {@code FETCH FIRST|NEXT n ROWS ONLY}（n 可为字面量/参数/表达式，此处只认形态）。 */
    private static final Pattern FETCH_ROWS_ONLY =
            Pattern.compile("(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\b[\\s\\S]*\\bROWS?\\s+ONLY\\b");

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public DbType dbType() {
        return DbType.postgresql;
    }

    @Override
    public boolean hasRowLimit(SQLStatement ast) {
        if (!(ast instanceof SQLSelectStatement)) {
            return false; // UPDATE/DELETE 无行限制子句（PG 语法）
        }
        SQLSelectStatement select = (SQLSelectStatement) ast;
        SQLSelectQuery query = select.getSelect().getQuery();
        if (query instanceof SQLSelectQueryBlock && hasRowCount(((SQLSelectQueryBlock) query).getLimit())) {
            return true; // LIMIT n（纯 OFFSET 不算：跳过行数不减少扫描量）
        }
        return FETCH_ROWS_ONLY.matcher(select.toString()).find();
    }

    private static boolean hasRowCount(SQLLimit limit) {
        return limit != null && limit.getRowCount() != null;
    }
}
