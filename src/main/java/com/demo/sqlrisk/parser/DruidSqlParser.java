package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;

/**
 * Druid SQL 解析（详细设计 §10.4）：MySQL 方言固定（需求 §16）；
 * 失败统一包装为 {@link SqlParseException}（含骨架原文，供 SKIP 原因展示）。
 */
public final class DruidSqlParser {

    private DruidSqlParser() {
    }

    public static SQLStatement parse(String normalizedSql) {
        if (normalizedSql == null || normalizedSql.trim().isEmpty()) {
            throw new SqlParseException("SQL 解析失败：骨架为空", normalizedSql, null);
        }
        try {
            SQLStatement statement = SQLUtils.parseSingleStatement(normalizedSql, DbType.mysql);
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
