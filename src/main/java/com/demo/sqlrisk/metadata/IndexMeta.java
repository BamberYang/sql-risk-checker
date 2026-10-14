package com.demo.sqlrisk.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 索引元数据（详细设计 §7）：M1/M6 以"谓词列是否命中某索引的前缀列"判定。 */
public final class IndexMeta {

    private final String name;
    private final List<String> columns;

    public IndexMeta(String name, List<String> columns) {
        this.name = Objects.requireNonNull(name, "name");
        this.columns = Collections.unmodifiableList(new ArrayList<String>(
                Objects.requireNonNull(columns, "columns")));
    }

    public String name() {
        return name;
    }

    /** 索引列（顺序即索引定义顺序，首列即前缀列）。 */
    public List<String> columns() {
        return columns;
    }

    /** 是否以指定列（大小写不敏感）为前缀列。 */
    public boolean startsWith(String columnName) {
        return !columns.isEmpty() && columns.get(0).equalsIgnoreCase(columnName);
    }
}
