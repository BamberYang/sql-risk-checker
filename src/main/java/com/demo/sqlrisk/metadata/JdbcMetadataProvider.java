package com.demo.sqlrisk.metadata;

import com.demo.sqlrisk.config.DataSourceSetting;
import com.demo.sqlrisk.util.Diagnostics;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 数据库元数据加载（详细设计 §7，v1.8/FR-8/D24）：JDBC 连接数据库一次性加载
 * 表 / 字段与类型 / 索引为内存快照——连接随后关闭，运行期内不再连接、不重复加载。
 *
 * <p>读取：{@code DatabaseMetaData.getTables/getColumns/getIndexInfo}；表名与列名匹配大小写不敏感；
 * 重复表名取首条并告警。任何失败（连接失败/权限不足/查询失败/超时）→ {@link MetadataException}（exit 2），
 * 无降级路径；错误信息脱敏（不含密码）。JDBC 驱动为 pom 依赖（D27），随 shaded jar 分发。</p>
 */
public final class JdbcMetadataProvider implements MetadataProvider {

    /** 需要拼接长度的类型（其余保留 TYPE_NAME 原名）。 */
    private static final Set<String> SIZE_TYPES = Collections.unmodifiableSet(new java.util.HashSet<String>(
            java.util.Arrays.asList("varchar", "char", "character varying", "char varying",
                    "binary", "varbinary", "numeric", "decimal")));
    /** 仅当小数位 > 0 时拼接精度的类型（如 timestamp(6)）。 */
    private static final Set<String> PRECISION_TYPES = Collections.unmodifiableSet(new java.util.HashSet<String>(
            java.util.Arrays.asList("timestamp", "datetime", "time")));

    private final Map<String, TableMetadata> byLowerTableName;

    private JdbcMetadataProvider(Map<String, TableMetadata> byLowerTableName) {
        this.byLowerTableName = byLowerTableName;
    }

    @Override
    public Optional<TableMetadata> find(String tableName) {
        if (tableName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byLowerTableName.get(tableName.toLowerCase(Locale.ROOT)));
    }

    /** 快照表数（--verbose 日志展示"一次加载"语义用，AC-12）。 */
    public int tableCount() {
        return byLowerTableName.size();
    }

    /**
     * 连接一次 → 读取全部元数据 → 关闭连接；返回只读内存快照。
     *
     * @throws MetadataException 连接失败 / 权限不足 / 元数据查询失败 / 超时（exit 2）
     */
    public static JdbcMetadataProvider load(DataSourceSetting cfg) {
        DriverManager.setLoginTimeout(cfg.connectionTimeoutSeconds()); // 登录超时（§17；不分驱动，尽力约束）
        try (Connection connection = DriverManager.getConnection(
                cfg.url(), cfg.username(), cfg.password())) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = safeCatalog(connection);
            String schema = safeSchema(connection);
            return new JdbcMetadataProvider(readSnapshot(meta, catalog, schema));
        } catch (SQLException e) {
            throw new MetadataException("数据库元数据加载失败（" + cfg.url() + "）：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new MetadataException("数据库元数据加载失败（" + cfg.url() + "）：" + e);
        }
    }

    // ---------- 读取 ----------

    private static Map<String, TableMetadata> readSnapshot(DatabaseMetaData meta, String catalog,
                                                           String schema) throws SQLException {
        Map<String, TableMetadata> byLower = new TreeMap<String, TableMetadata>(); // 排序保证确定性
        for (String table : readTableNames(meta, catalog, schema)) {
            String key = table.toLowerCase(Locale.ROOT);
            if (byLower.containsKey(key)) {
                Diagnostics.warn("[警告] 数据库元数据中重复表名，取首条: " + table);
                continue;
            }
            byLower.put(key, new TableMetadata(table,
                    readFields(meta, catalog, schema, table), readIndexes(meta, catalog, schema, table)));
        }
        return byLower;
    }

    private static List<String> readTableNames(DatabaseMetaData meta, String catalog,
                                               String schema) throws SQLException {
        List<String> tables = new ArrayList<String>();
        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.trim().isEmpty()) {
                    tables.add(name.trim());
                }
            }
        }
        return tables;
    }

    private static Map<String, FieldMeta> readFields(DatabaseMetaData meta, String catalog,
                                                     String schema, String table) throws SQLException {
        Map<String, FieldMeta> fields = new LinkedHashMap<String, FieldMeta>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                String type = normalizeType(rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"), rs.wasNull());
                fields.put(name.trim().toLowerCase(Locale.ROOT), new FieldMeta(name.trim(), type));
            }
        }
        return fields;
    }

    private static List<IndexMeta> readIndexes(DatabaseMetaData meta, String catalog,
                                               String schema, String table) throws SQLException {
        Map<String, TreeMap<Integer, String>> byIndex = new TreeMap<String, TreeMap<Integer, String>>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName == null || column == null) {
                    continue; // tableIndexStatistic 等统计行
                }
                int position = rs.getInt("ORDINAL_POSITION");
                TreeMap<Integer, String> columns = byIndex.get(indexName);
                if (columns == null) {
                    columns = new TreeMap<Integer, String>();
                    byIndex.put(indexName, columns);
                }
                columns.put(position, column.trim());
            }
        }
        return byIndex.entrySet().stream()
                .map(entry -> new IndexMeta(entry.getKey(),
                        new ArrayList<String>(entry.getValue().values())))
                .collect(Collectors.toList());
    }

    /** 类型归一化：基名 + 长度/精度（如 varchar(64)、numeric(19,2)、timestamp(6)）；跨库别名归一。 */
    static String normalizeType(String typeName, int size, int decimals, boolean sizeNull) {
        String base = typeName == null || typeName.trim().isEmpty() ? "" : typeName.trim();
        String lower = base.toLowerCase(Locale.ROOT);
        if ("character varying".equals(lower) || "char varying".equals(lower)) {
            base = "VARCHAR"; // H2/PG 的 VARCHAR 标准名 → 统一为 VARCHAR（跨库一致）
            lower = "varchar";
        } else if ("character".equals(lower)) {
            base = "CHAR";
            lower = "char";
        } else if ("integer".equals(lower)) {
            base = "INT"; // H2 标准名 INTEGER → 统一为 INT（跨库一致）
            lower = "int";
        }
        if (SIZE_TYPES.contains(lower) && !sizeNull && size > 0) {
            if (("numeric".equals(lower) || "decimal".equals(lower)) && decimals > 0) {
                return base + "(" + size + "," + decimals + ")";
            }
            return base + "(" + size + ")";
        }
        if (PRECISION_TYPES.contains(lower) && !sizeNull && decimals > 0) {
            return base + "(" + decimals + ")";
        }
        return base;
    }

    // ---------- 连接信息（脱敏：仅用于错误信息，绝不含密码） ----------

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    private static String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException e) {
            return null; // 部分驱动不支持
        }
    }
}
