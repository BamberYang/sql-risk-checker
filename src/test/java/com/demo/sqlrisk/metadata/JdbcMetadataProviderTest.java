package com.demo.sqlrisk.metadata;

import com.demo.sqlrisk.config.DataSourceSetting;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T10.2 自测：JDBC 元数据加载（H2 内存库）——表/列类型/索引、一次加载快照、失败语义（详设 §7 v1.8）。 */
class JdbcMetadataProviderTest {

    private static String freshUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static DataSourceSetting setting(String url) {
        return new DataSourceSetting(url, "sa", "", 5);
    }

    private static void execute(String url, String... statements) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
        }
    }

    @Test
    void loads_tables_fields_types_and_indexes() throws Exception {
        String url = freshUrl();
        execute(url,
                "CREATE TABLE t_order (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount DECIMAL(10,2),"
                        + " create_time TIMESTAMP(3), status INT)",
                "CREATE INDEX idx_order_no ON t_order(order_no)",
                "CREATE INDEX idx_province_city ON t_order(status, order_no)",
                "CREATE TABLE t_plain (id BIGINT PRIMARY KEY)");

        JdbcMetadataProvider provider = JdbcMetadataProvider.load(setting(url));

        assertTrue(provider.find("t_order").isPresent());
        TableMetadata order = provider.find("t_order").get();
        assertEquals("BIGINT", order.field("id").get().type());
        assertEquals("VARCHAR(64)", order.field("order_no").get().type(), "varchar 拼接长度");
        assertEquals("DECIMAL(10,2)", order.field("amount").get().type(), "numeric 拼接精度");
        assertEquals("TIMESTAMP(3)", order.field("create_time").get().type(), "小数位 > 0 拼接精度");
        assertEquals("INT", order.field("status").get().type(), "无尺寸类型保留原名");
        assertTrue(order.hasIndexStartingWith("order_no"));
        assertTrue(order.hasIndexStartingWith("status"), "复合索引 idx_province_city 前缀列 status");
        assertFalse(order.hasIndexStartingWith("amount"), "无索引列不构成前缀命中");

        assertTrue(provider.find("t_plain").isPresent());
        assertFalse(provider.find("t_missing").isPresent(), "未加载表 = empty（无索引语义）");
    }

    @Test
    void table_and_column_matching_is_case_insensitive() throws Exception {
        String url = freshUrl();
        execute(url, "CREATE TABLE t_order (id BIGINT PRIMARY KEY)");
        JdbcMetadataProvider provider = JdbcMetadataProvider.load(setting(url));
        assertTrue(provider.find("T_ORDER").isPresent());
        assertTrue(provider.find("t_order").get().field("ID").isPresent());
        assertEquals(java.util.Optional.empty(), provider.find(null));
    }

    @Test
    void snapshot_survives_connection_close_and_later_ddl() throws Exception {
        String url = freshUrl();
        execute(url, "CREATE TABLE t_order (id BIGINT PRIMARY KEY)");
        JdbcMetadataProvider provider = JdbcMetadataProvider.load(setting(url));

        // 加载后连接已关闭；后续 DDL 不影响既有快照（一次加载语义）
        execute(url, "DROP TABLE t_order", "CREATE TABLE t_other (id BIGINT)");
        assertTrue(provider.find("t_order").isPresent(), "快照不随库变更");
        assertFalse(provider.find("t_other").isPresent());
    }

    @Test
    void empty_database_yields_empty_snapshot() throws Exception {
        String url = freshUrl();
        execute(url, "CREATE TABLE t_dummy (id BIGINT)");
        String empty = freshUrl();
        execute(empty, "SELECT 1");
        JdbcMetadataProvider provider = JdbcMetadataProvider.load(setting(empty));
        assertFalse(provider.find("t_dummy").isPresent());
    }

    @Test
    void connection_failure_throws_metadata_exception_exit_2() {
        DataSourceSetting badSetting = setting("jdbc:unknown-subprotocol://127.0.0.1/none");
        MetadataException e = assertThrows(MetadataException.class,
                () -> JdbcMetadataProvider.load(badSetting));
        assertEquals(2, e.exitCode());
        assertTrue(e.getMessage().contains("数据库元数据加载失败"), e.getMessage());
        assertFalse(e.getMessage().contains("pwd"), "错误信息不含密码");
    }

    @Test
    void normalize_type_rules() {
        assertEquals("VARCHAR(64)", JdbcMetadataProvider.normalizeType("VARCHAR", 64, 0, false));
        assertEquals("NUMERIC(19,2)", JdbcMetadataProvider.normalizeType("NUMERIC", 19, 2, false));
        assertEquals("BIGINT", JdbcMetadataProvider.normalizeType("BIGINT", 19, 0, false));
        assertEquals("TIMESTAMP(6)", JdbcMetadataProvider.normalizeType("TIMESTAMP", 26, 6, false));
        assertEquals("TIMESTAMP", JdbcMetadataProvider.normalizeType("TIMESTAMP", 26, 0, false));
        assertEquals("VARCHAR", JdbcMetadataProvider.normalizeType("VARCHAR", 0, 0, false));
    }
}
