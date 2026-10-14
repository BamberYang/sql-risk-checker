package com.demo.sqlrisk.metadata;

import java.util.Objects;

/** 字段元数据（详细设计 §7）：type 供 M4 隐式类型转换判定，如 "BIGINT"/"VARCHAR(64)"。 */
public final class FieldMeta {

    private final String name;
    private final String type;

    public FieldMeta(String name, String type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }
}
