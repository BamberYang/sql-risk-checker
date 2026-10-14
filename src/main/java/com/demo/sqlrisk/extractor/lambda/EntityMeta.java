package com.demo.sqlrisk.extractor.lambda;

import com.demo.sqlrisk.util.TextUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 实体 → 表/列映射（详细设计 §9.8）：{@code @TableName} 定表名、{@code @TableField/@TableId} 定列名；
 * 未标注按默认「驼峰 → 下划线」；{@code @TableField(exist=false)} 的属性被排除。
 */
public final class EntityMeta {

    private final String typeName;
    private final String table;
    private final Map<String, String> explicitColumns; // 属性名 → 列名
    private final Set<String> excludedProperties;      // exist=false

    public EntityMeta(String typeName, String table,
                      Map<String, String> explicitColumns, Set<String> excludedProperties) {
        this.typeName = Objects.requireNonNull(typeName, "typeName");
        this.table = Objects.requireNonNull(table, "table");
        this.explicitColumns = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(explicitColumns));
        this.excludedProperties = Collections.unmodifiableSet(
                new java.util.LinkedHashSet<String>(excludedProperties));
    }

    public String typeName() {
        return typeName;
    }

    /** 真实表名（@TableName 或类名驼峰→下划线）。 */
    public String table() {
        return table;
    }

    /** 属性名 → 列名；被排除（exist=false）→ empty。 */
    public Optional<String> columnOf(String property) {
        if (property == null || excludedProperties.contains(property)) {
            return Optional.empty();
        }
        String explicit = explicitColumns.get(property);
        return Optional.of(explicit != null ? explicit : TextUtils.camelToSnake(property));
    }

    /** 由 SFunction 访问器方法名（getUserId/isActive）解析列名。 */
    public Optional<String> columnOfAccessor(String accessorName) {
        String property = accessorToProperty(accessorName);
        return property == null ? Optional.<String>empty() : columnOf(property);
    }

    /** getUserId → userId；isActive → active；无法识别 → null。（M6 渲染器复用） */
    public static String accessorToProperty(String accessorName) {
        if (accessorName == null) {
            return null;
        }
        String name;
        if (accessorName.startsWith("get") && accessorName.length() > 3) {
            name = accessorName.substring(3);
        } else if (accessorName.startsWith("is") && accessorName.length() > 2) {
            name = accessorName.substring(2);
        } else {
            return null;
        }
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name; // JavaBeans 规则：getURL → URL
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
