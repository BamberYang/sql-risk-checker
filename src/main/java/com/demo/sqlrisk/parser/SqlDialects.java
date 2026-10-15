package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.util.Diagnostics;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * 方言注册表（详细设计 §10.7，v1.8/FR-9）：
 * 内置 {@link MysqlDialect}、{@link PostgresDialect} + {@link ServiceLoader} 外部方言（META-INF/services，与规则 SPI 同形态）；
 * 同名冲突以内置优先并告警忽略；名称按字典序稳定排列（确定性）。
 *
 * <p>扩展路径（验收 T11.2 以测试替身验证）：实现 {@link SqlDialect} → 注册服务文件 → 配置
 * {@code dialect: <name>} 即可使用（{@link com.demo.sqlrisk.config.ConfigValidator} 经
 * {@link #supportedNames()} 放开取值校验）。</p>
 */
public final class SqlDialects {

    private static final Map<String, SqlDialect> REGISTRY;

    static {
        Map<String, SqlDialect> map = new TreeMap<String, SqlDialect>();
        register(map, new MysqlDialect());
        register(map, new PostgresDialect());
        for (SqlDialect dialect : ServiceLoader.load(SqlDialect.class)) {
            registerExternal(map, dialect);
        }
        REGISTRY = Collections.unmodifiableMap(map);
    }

    /** 外部方言注册：无名忽略；同名以内置优先并告警。 */
    private static void registerExternal(Map<String, SqlDialect> map, SqlDialect dialect) {
        if (dialect == null || dialect.name() == null) {
            return;
        }
        String key = dialect.name().toLowerCase(Locale.ROOT);
        if (map.containsKey(key)) {
            Diagnostics.warn("[警告] 外部方言与已注册方言同名，忽略: " + dialect.name());
            return;
        }
        map.put(key, dialect);
    }

    private SqlDialects() {
    }

    private static void register(Map<String, SqlDialect> map, SqlDialect dialect) {
        map.put(dialect.name().toLowerCase(Locale.ROOT), dialect);
    }

    /**
     * @throws IllegalArgumentException 未支持方言（调用方通常已由配置层校验，此处为编程错误兜底）
     */
    public static SqlDialect of(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        SqlDialect dialect = REGISTRY.get(key);
        if (dialect == null) {
            throw new IllegalArgumentException("未知方言: " + name + "（已支持: " + supportedNames() + "）");
        }
        return dialect;
    }

    /** 已支持方言名（字典序，确定性）。 */
    public static Set<String> supportedNames() {
        return REGISTRY.keySet();
    }
}
