package com.demo.sqlrisk.model;

import java.util.Objects;

/**
 * 白名单命中语句（详细设计 §4.2/§13.3）：不参与 P5 之后任何阶段。
 */
public final class IgnoredItem {

    private final String statementId;
    private final String file;
    private final int line;
    private final String reason;

    public IgnoredItem(String statementId, String file, int line, String reason) {
        this.statementId = Objects.requireNonNull(statementId, "statementId");
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String statementId() {
        return statementId;
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    public String reason() {
        return reason;
    }
}
