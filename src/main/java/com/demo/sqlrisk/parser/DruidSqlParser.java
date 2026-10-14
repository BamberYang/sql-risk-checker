package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;

/**
 * Druid SQL 解析（详细设计 §10.4，v1.8 方言化）：按 {@link SqlDialect#dbType()} 解析（缺省 MySQL）；
 * 失败统一包装为 {@link SqlParseException}（含骨架原文，供 SKIP 原因展示）。
 */
public final class DruidSqlParser {

    private DruidSqlParser() {
    }

    public static SQLStatement parse(String normalizedSql) {
        return parse(normalizedSql, null);
    }

    /** @param dialect 可为 null（缺省 MySQL 方言，行为与迁移前一致） */
    public static SQLStatement parse(String normalizedSql, SqlDialect dialect) {
        if (normalizedSql == null || normalizedSql.trim().isEmpty()) {
            throw new SqlParseException("SQL 解析失败：骨架为空", normalizedSql, null);
        }
        DbType dbType = dialect == null ? DbType.mysql : dialect.dbType();
        try {
            SQLStatement statement = SQLUtils.parseSingleStatement(normalizedSql, dbType);
            if (statement == null) {
                throw new SqlParseException("SQL 解析失败：无有效语句", normalizedSql, null);
            }
            return statement;
        } catch (SqlParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SqlParseException("SQL 解析失败：" + e.getMessage(), normalizedSql, e);
        }
    }
}
