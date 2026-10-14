package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.model.ChangeType;

import java.util.Objects;

/**
 * 生效语句选择（详细设计 §9.4）：差异/全量模式下需要提取的语句及其变更判定结果。
 * 片段派生命中 {@code changedVia} 为来源片段 refid（决策 D20/DD-9：直接变更优先）。
 */
public final class StatementSelection {

    private final String statementKey; // namespace.id
    private final String namespace;
    private final String file;
    private final int startLine;
    private final int endLine;
    private final ChangeType changeType;
    private final String changedVia;   // nullable

    public StatementSelection(String statementKey, String namespace, String file,
                              int startLine, int endLine, ChangeType changeType, String changedVia) {
        this.statementKey = Objects.requireNonNull(statementKey, "statementKey");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.file = Objects.requireNonNull(file, "file");
        this.startLine = startLine;
        this.endLine = endLine;
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.changedVia = changedVia;
    }

    public String statementKey() {
        return statementKey;
    }

    public String namespace() {
        return namespace;
    }

    public String file() {
        return file;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }

    public ChangeType changeType() {
        return changeType;
    }

    /** 可为 null：片段变派生命中时的来源片段 refid（如 `com.demo.mapper.BaseMapper.baseColumns` 派生标记）。 */
    public String changedVia() {
        return changedVia;
    }
}
