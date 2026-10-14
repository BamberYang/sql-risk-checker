package com.demo.sqlrisk.parser;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6.1 自测：MySQL 方言解析与失败包装（SqlParseException 携带骨架原文）。 */
class DruidSqlParserTest {

    @Test
    void parses_core_statement_kinds() {
        assertTrue(DruidSqlParser.parse("SELECT * FROM t_order") instanceof SQLSelectStatement);
        assertTrue(DruidSqlParser.parse("UPDATE t_order SET status = ? WHERE id = ?") instanceof SQLUpdateStatement);
        assertTrue(DruidSqlParser.parse("DELETE FROM t_order WHERE id = ?") instanceof SQLDeleteStatement);
    }

    @Test
    void parse_failure_wraps_with_sql_text() {
        SqlParseException e = assertThrows(SqlParseException.class,
                () -> DruidSqlParser.parse("!!! not sql !!!"));
        assertTrue(e.getMessage().contains("解析失败"));
        assertEquals("!!! not sql !!!", e.sql());
    }

    @Test
    void empty_statement_fails() {
        assertThrows(SqlParseException.class, () -> DruidSqlParser.parse("   "));
        assertThrows(SqlParseException.class, () -> DruidSqlParser.parse(null));
    }
}
