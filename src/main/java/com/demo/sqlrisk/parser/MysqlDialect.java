package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;

/**
 * MySQL 方言（详细设计 §10.7，v1.8；v1 唯一内置实现，行为与迁移前完全一致）：
 * 分页识别 = SELECT 查询块的 {@code LIMIT} / UPDATE、DELETE 的 {@code LIMIT}（方言 AST 反射可达，M0.3 spike 已验证）。
 */
public final class MysqlDialect implements SqlDialect {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public DbType dbType() {
        return DbType.mysql;
    }

    @Override
    public boolean hasRowLimit(SQLStatement ast) {
        if (ast instanceof SQLSelectStatement) {
            SQLSelectQuery query = ((SQLSelectStatement) ast).getSelect().getQuery();
            if (query instanceof SQLSelectQueryBlock) {
                return ((SQLSelectQueryBlock) query).getLimit() != null;
            }
            return false; // UNION 等复合查询：保守不带 LIMIT（与迁移前行为一致）
        }
        if (ast instanceof SQLUpdateStatement || ast instanceof SQLDeleteStatement) {
            return reflectiveLimit(ast); // MySqlUpdateStatement/MySqlDeleteStatement.getLimit()
        }
        return false;
    }

    private static boolean reflectiveLimit(SQLStatement statement) {
        try {
            Object limit = statement.getClass().getMethod("getLimit").invoke(statement);
            return limit != null;
        } catch (Exception e) {
            return false;
        }
    }
}
