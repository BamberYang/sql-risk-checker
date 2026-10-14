package com.demo.sqlrisk.spike;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0.3 Spike B：验证 Druid（MySQL 方言）对事实提取所需 AST 形态的支持：
 * 占位符归一化后的语句可解析、UPDATE/DELETE 的 WHERE 与 MySQL 扩展 LIMIT 可达、
 * SELECT * 的 SQLAllColumnExpr 可达、toMySqlString 可生成展示文本。
 *
 * <p>结论落地：M6 的 DruidSqlParser / QueryFactsBuilder 按此口径实现。</p>
 */
class DruidMySqlSpikeTest {

    @Test
    void update_without_where_has_null_where() {
        SQLStatement st = SQLUtils.parseSingleStatement("UPDATE t_order SET status = ?", DbType.mysql);
        assertTrue(st instanceof SQLUpdateStatement);
        assertNull(((SQLUpdateStatement) st).getWhere(), "无 WHERE 的 UPDATE 应得到 null where（H1 判定依据）");
    }

    @Test
    void update_with_where_and_mysql_limit_is_reachable() throws Exception {
        SQLStatement st = SQLUtils.parseSingleStatement(
                "UPDATE t_order SET status = ? WHERE user_id = ? LIMIT 10", DbType.mysql);
        assertTrue(st instanceof SQLUpdateStatement);
        assertNotNull(((SQLUpdateStatement) st).getWhere());
        Object limit = st.getClass().getMethod("getLimit").invoke(st);
        assertNotNull(limit, "MySQL UPDATE ... LIMIT 的 limit 应可通过 getLimit() 取得（实际类: "
                + st.getClass().getName() + "）");
    }

    @Test
    void delete_with_limit_is_reachable() throws Exception {
        SQLStatement st = SQLUtils.parseSingleStatement(
                "DELETE FROM t_order WHERE user_id = ? LIMIT 5", DbType.mysql);
        assertTrue(st instanceof SQLDeleteStatement);
        Object limit = st.getClass().getMethod("getLimit").invoke(st);
        assertNotNull(limit, "MySQL DELETE ... LIMIT 的 limit 应可通过 getLimit() 取得（实际类: "
                + st.getClass().getName() + "）");
    }

    @Test
    void select_all_column_and_limit_are_reachable() {
        SQLStatement st = SQLUtils.parseSingleStatement("SELECT * FROM t_order WHERE id = ? LIMIT 1", DbType.mysql);
        assertTrue(st instanceof SQLSelectStatement);
        SQLSelectQueryBlock block = ((SQLSelectStatement) st).getSelect().getQueryBlock();
        assertNotNull(block.getWhere());
        assertNotNull(block.getLimit());
        assertTrue(block.getSelectList().get(0).getExpr() instanceof SQLAllColumnExpr,
                "SELECT * 应得到 SQLSelectItem(expr=SQLAllColumnExpr)（selectAll 事实判定依据）");
    }

    @Test
    void select_without_where_and_limit_is_null() {
        SQLSelectQueryBlock block = ((SQLSelectStatement) SQLUtils.parseSingleStatement(
                "SELECT * FROM t_order", DbType.mysql)).getSelect().getQueryBlock();
        assertNull(block.getWhere());
        assertNull(block.getLimit());
    }

    @Test
    void to_mysql_string_renders_display_text() {
        SQLStatement st = SQLUtils.parseSingleStatement("UPDATE t_order SET status = ? WHERE id = ?", DbType.mysql);
        String rendered = SQLUtils.toMySqlString(st);
        assertNotNull(rendered);
        assertTrue(rendered.toUpperCase().contains("UPDATE"));
    }
}
