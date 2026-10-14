package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 提取产物（详细设计 §9.1）：语句 + 跳过项（SKIP，reason 说明原因）。 */
public final class ExtractResult {

    private final List<ExtractedStatement> statements;
    private final List<SkippedItem> skipped;

    public ExtractResult(List<ExtractedStatement> statements, List<SkippedItem> skipped) {
        this.statements = Collections.unmodifiableList(
                new ArrayList<ExtractedStatement>(Objects.requireNonNull(statements, "statements")));
        this.skipped = Collections.unmodifiableList(
                new ArrayList<SkippedItem>(Objects.requireNonNull(skipped, "skipped")));
    }

    public static ExtractResult empty() {
        return new ExtractResult(Collections.<ExtractedStatement>emptyList(),
                Collections.<SkippedItem>emptyList());
    }

    public List<ExtractedStatement> statements() {
        return statements;
    }

    public List<SkippedItem> skipped() {
        return skipped;
    }
}
