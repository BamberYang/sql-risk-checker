package com.demo.sqlrisk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final List<String> wrapperSqlParams; // §9.5-6（M8 排除项），空=无

    private StatementMeta(String statementId,
                          StatementSource source,
                          String file,
                          int line,
                          ChangeType changeType,
                          String changedVia,
                          List<String> wrapperSqlParams) {
        this.statementId = Objects.requireNonNull(statementId, "statementId");
        this.source = Objects.requireNonNull(source, "source");
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.changedVia = changedVia;
        this.wrapperSqlParams = Collections.unmodifiableList(new ArrayList<String>(
                Objects.requireNonNull(wrapperSqlParams, "wrapperSqlParams")));
    }

    public static StatementMeta from(ExtractedStatement statement) {
        Objects.requireNonNull(statement, "statement");
        return new StatementMeta(statement.statementId(), statement.source(), statement.file(),
                statement.line(), statement.changeType(), statement.changedVia(),
                statement.wrapperSqlParams());
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

    /** 注解 SQL 中解析到 Wrapper 家族参数的 ${} 根名集合（§9.5-6），空=无。 */
    public List<String> wrapperSqlParams() {
        return wrapperSqlParams;
    }
}
