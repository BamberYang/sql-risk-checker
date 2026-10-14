package com.demo.sqlrisk.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 表元数据（详细设计 §7）：全部字段（含类型）与索引信息。 */
public final class TableMetadata {

    private final String table;
    private final Map<String, FieldMeta> fieldsByLowerName; // 列名小写 → 字段
    private final List<IndexMeta> indexes;

    public TableMetadata(String table, Map<String, FieldMeta> fieldsByLowerName, List<IndexMeta> indexes) {
        this.table = Objects.requireNonNull(table, "table");
        this.fieldsByLowerName = Collections.unmodifiableMap(
                new LinkedHashMap<String, FieldMeta>(Objects.requireNonNull(fieldsByLowerName, "fieldsByLowerName")));
        this.indexes = Collections.unmodifiableList(
                new ArrayList<IndexMeta>(Objects.requireNonNull(indexes, "indexes")));
    }

    public String table() {
        return table;
    }

    public Map<String, FieldMeta> fieldsByLowerName() {
        return fieldsByLowerName;
    }

    public List<IndexMeta> indexes() {
        return indexes;
    }

    /** 按列名查找字段（大小写不敏感）。 */
    public Optional<FieldMeta> field(String columnName) {
        if (columnName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(fieldsByLowerName.get(columnName.toLowerCase(Locale.ROOT)));
    }

    /** 是否存在以指定列为前缀列的索引（M1 判定依据）。 */
    public boolean hasIndexStartingWith(String columnName) {
        for (IndexMeta index : indexes) {
            if (index.startsWith(columnName)) {
                return true;
            }
        }
        return false;
    }
}
