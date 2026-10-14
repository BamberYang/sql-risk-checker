package com.demo.sqlrisk.model;

import java.util.Objects;

/**
 * SKIP 类语句（详细设计 §4.2/§9.4 口径）：无法分析项，不影响退出码。
 * 收录三类：承载形式无法提取 / 解析失败 / 文件读取失败（reason 携带具体原因）。
 */
public final class SkippedItem {

    public enum Kind {
        /** 识别到 SQL 承载形式但无法完成提取（Provider 无法静态求值等）。 */
        UNSUPPORTED_CARRIER,
        /** 骨架解析失败（reason 前缀"解析失败："）。 */
        PARSE_FAILED,
        /** 文件读取/编码失败。 */
        READ_ERROR
    }

    private final String statementId; // nullable：提取阶段即失败的可能已知 ID
    private final String file;
    private final int line;
    private final Kind kind;
    private final String reason;

    public SkippedItem(String statementId, String file, int line, Kind kind, String reason) {
        this.statementId = statementId;
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** 可为 null。 */
    public String statementId() {
        return statementId;
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    public Kind kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }
}
