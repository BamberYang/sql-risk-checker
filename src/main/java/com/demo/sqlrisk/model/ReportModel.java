package com.demo.sqlrisk.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 报告模型（详细设计 §4.2/§12.1）：字段声明顺序即 JSON 输出顺序。
 * generatedAt 由注入 Clock 产生（确定性，见 §16）。
 */
public final class ReportModel {

    private final OffsetDateTime generatedAt;
    private final Mode mode;
    private final String headCommit;
    private final String prodCommit; // nullable：FULL 模式为 null（键保留）
    private final Summary summary;
    private final List<StatementResult> results;
    private final List<IgnoredItem> ignored;
    private final List<SkippedItem> skipped;

    public ReportModel(OffsetDateTime generatedAt,
                       Mode mode,
                       String headCommit,
                       String prodCommit,
                       Summary summary,
                       List<StatementResult> results,
                       List<IgnoredItem> ignored,
                       List<SkippedItem> skipped) {
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.headCommit = Objects.requireNonNull(headCommit, "headCommit");
        this.prodCommit = prodCommit;
        this.summary = Objects.requireNonNull(summary, "summary");
        this.results = Collections.unmodifiableList(new ArrayList<StatementResult>(
                Objects.requireNonNull(results, "results")));
        this.ignored = Collections.unmodifiableList(new ArrayList<IgnoredItem>(
                Objects.requireNonNull(ignored, "ignored")));
        this.skipped = Collections.unmodifiableList(new ArrayList<SkippedItem>(
                Objects.requireNonNull(skipped, "skipped")));
    }

    public OffsetDateTime generatedAt() {
        return generatedAt;
    }

    public Mode mode() {
        return mode;
    }

    public String headCommit() {
        return headCommit;
    }

    /** 可为 null。 */
    public String prodCommit() {
        return prodCommit;
    }

    public Summary summary() {
        return summary;
    }

    public List<StatementResult> results() {
        return results;
    }

    public List<IgnoredItem> ignored() {
        return ignored;
    }

    public List<SkippedItem> skipped() {
        return skipped;
    }
}
