package com.demo.sqlrisk.parser;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.demo.sqlrisk.config.ConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T11.1/T11.2 自测：方言注册表、MySQL 分页识别正反例、ServiceLoader 扩展路径（详设 §10.7 v1.8）。 */
class SqlDialectsTest {

    @Test
    void supported_names_include_builtin_and_service_loaded_dialect() {
        // 内置 mysql/postgresql + test classpath 服务文件注册的 test-dialect（扩展路径验证）
        assertTrue(SqlDialects.supportedNames().contains("mysql"));
        assertTrue(SqlDialects.supportedNames().contains("postgresql"));
        assertTrue(SqlDialects.supportedNames().contains("test-dialect"),
                "ServiceLoader 注册的方言应出现在支持列表：" + SqlDialects.supportedNames());
    }

    @Test
    void postgresql_is_builtin_and_configurable() {
        assertTrue(SqlDialects.of("POSTGRESQL") instanceof PostgresDialect, "取值大小写不敏感");
        ConfigValidator.validateDialect("postgresql"); // 不抛异常 = 配置层已放开取值（D25 动态提供）
    }

    @Test
    void postgres_dialect_feeds_limit_facts_end_to_end() {
        PostgresDialect pg = new PostgresDialect();
        String limited = "SELECT * FROM t_order FETCH FIRST 5 ROWS ONLY";
        QueryFacts facts = QueryFactsBuilder.build(
                DruidSqlParser.parse(limited, pg), null, SqlSkeleton.base(limited), pg);
        assertTrue(facts.hasLimit(), "PG FETCH FIRST → hasLimit 事实（H3/L4 输入）");

        String unlimited = "SELECT * FROM t_order";
        QueryFacts plain = QueryFactsBuilder.build(
                DruidSqlParser.parse(unlimited, pg), null, SqlSkeleton.base(unlimited), pg);
        assertFalse(plain.hasLimit(), "无分页子句 → 无 hasLimit");
    }

    @Test
    void names_are_ordered_deterministically() {
        List<String> names = new ArrayList<String>(SqlDialects.supportedNames());
        List<String> sorted = new ArrayList<String>(names);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, names, "支持列表按字典序（确定性）");
    }

    @Test
    void of_is_case_insensitive() {
        assertTrue(SqlDialects.of("MySql") instanceof MysqlDialect);
        assertTrue(SqlDialects.of(" mysql ") instanceof MysqlDialect);
    }

    @Test
    void unknown_dialect_throws_with_supported_list() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlDialects.of("oracle"));
        assertTrue(e.getMessage().contains("未知方言"), e.getMessage());
        assertTrue(e.getMessage().contains("mysql"), e.getMessage());
        assertTrue(e.getMessage().contains("postgresql"), e.getMessage());
    }

    @Test
    void mysql_row_limit_positive_and_negative() {
        MysqlDialect mysql = new MysqlDialect();
        assertEquals("mysql", mysql.name());
        assertTrue(mysql.hasRowLimit(parse("SELECT id FROM t_order LIMIT 10")));
        assertFalse(mysql.hasRowLimit(parse("SELECT id FROM t_order WHERE id = 1")));
        assertTrue(mysql.hasRowLimit(parse("UPDATE t_order SET status = 1 WHERE id = 1 LIMIT 1")));
        assertTrue(mysql.hasRowLimit(parse("DELETE FROM t_order WHERE id = 1 LIMIT 1")));
        assertFalse(mysql.hasRowLimit(parse("INSERT INTO t_order (id) VALUES (1)")));
    }

    @Test
    void service_loaded_dialect_parses_and_feeds_facts_end_to_end() {
        // 扩展路径端到端：实现 SPI → 注册 → 配置校验放开 → 按该方言解析与事实提取
        TestDialect test = new TestDialect();
        ConfigValidator.validateDialect(test.name()); // 不抛异常 = 配置层已放开取值

        String sql = "SELECT * FROM t_order FETCH FIRST 5 ROWS ONLY";
        SQLStatement ast = DruidSqlParser.parse(sql, test); // DbType.postgresql 解析
        QueryFacts facts = QueryFactsBuilder.build(ast, null, SqlSkeleton.base(sql), test);
        assertTrue(facts.hasLimit(), "替身方言的 FETCH FIRST 识别进入 hasLimit 事实（H3/L4 输入）");

        // 对照：同一 SQL 文本在 MySQL 方言下不构成 LIMIT 事实
        assertFalse(new MysqlDialect().hasRowLimit(ast), "方言识别彼此独立");
    }

    private static SQLStatement parse(String sql) {
        return DruidSqlParser.parse(sql); // 1 参重载 = 缺省 MySQL（兼容路径）
    }
}
