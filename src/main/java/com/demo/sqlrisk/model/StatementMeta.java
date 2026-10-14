package com.demo.sqlrisk.model;

import java.util.Objects;

/**
 * 语句元信息（详细设计 §4.2）：由 {@link ExtractedStatement} 投影，供规则上下文与报告使用。
 */
public final class StatementMeta {

    private final String statementId;
    private final StatementSource source;
    private final String file;
    private final int line;
    private final ChangeType changeType;
    private final String changedVia; // nullable

    private StatementMeta(String statementId,
                          StatementSource source,
                          String file,
                          int line,
                          ChangeType changeType,
                          String changedVia) {
        this.statementId = Objects.requireNonNull(statementId, "statementId");
        this.source = Objects.requireNonNull(source, "source");
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.changedVia = changedVia;
    }

    public static StatementMeta from(ExtractedStatement statement) {
        Objects.requireNonNull(statement, "statement");
        return new StatementMeta(statement.statementId(), statement.source(), statement.file(),
                statement.line(), statement.changeType(), statement.changedVia());
    }

    public String statementId() {
        return statementId;
    }

    public StatementSource source() {
        return source;
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    public ChangeType changeType() {
        return changeType;
    }

    /** 可为 null（见 {@link ExtractedStatement#changedVia()}）。 */
    public String changedVia() {
        return changedVia;
    }
}
