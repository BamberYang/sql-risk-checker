package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.10（详设 §10.7）：PostgreSQL 方言——LIMIT / FETCH FIRST|NEXT 识别；纯 OFFSET 与 UPDATE/DELETE 保守 false。 */
class PostgresDialectTest {

    private final PostgresDialect postgres = new PostgresDialect();

    @Test
    void identity() {
        assertEquals("postgresql", postgres.name());
        assertEquals(DbType.postgresql, postgres.dbType());
    }

    @Test
    void limit_forms_are_recognized() {
        assertTrue(postgres.hasRowLimit(parse("SELECT id FROM t_order LIMIT 10")));
        assertTrue(postgres.hasRowLimit(parse("SELECT id FROM t_order LIMIT 10 OFFSET 20")));
        assertTrue(postgres.hasRowLimit(parse("SELECT id FROM t_order ORDER BY id DESC LIMIT 1")));
    }

    @Test
    void fetch_first_forms_are_recognized() {
        assertTrue(postgres.hasRowLimit(parse("SELECT id FROM t_order FETCH FIRST 5 ROWS ONLY")));
        assertTrue(postgres.hasRowLimit(parse("SELECT id FROM t_order FETCH FIRST 1 ROW ONLY")));
        assertTrue(postgres.hasRowLimit(
                parse("SELECT id FROM t_order ORDER BY id OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY")));
    }

    @Test
    void offset_only_counts_as_no_limit() {
        // 纯 OFFSET：跳过行数但不减少扫描量（仍全表扫描）→ 保守按无 LIMIT（H3 输入）
        assertFalse(postgres.hasRowLimit(parse("SELECT id FROM t_order OFFSET 20")));
    }

    @Test
    void unlimited_and_non_select_are_conservatively_false() {
        assertFalse(postgres.hasRowLimit(parse("SELECT * FROM t_order WHERE id = 1")));
        assertFalse(postgres.hasRowLimit(parse("UPDATE t_order SET status = 1 WHERE id = 1")));
        assertFalse(postgres.hasRowLimit(parse("DELETE FROM t_order WHERE id = 1")));
        assertFalse(postgres.hasRowLimit(parse("INSERT INTO t_order (id) VALUES (1)")));
    }

    private static SQLStatement parse(String sql) {
        return DruidSqlParser.parse(sql, new PostgresDialect());
    }
}
