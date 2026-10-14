package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.model.SkippedItem;

/** 单语句解析结果（详细设计 §10.6）：成功 → ParsedStatement；失败 → SKIP(PARSE_FAILED)。 */
public final class ParseOutcome {

    private final ParsedStatement parsed; // nullable
    private final SkippedItem skipped;    // nullable

    private ParseOutcome(ParsedStatement parsed, SkippedItem skipped) {
        this.parsed = parsed;
        this.skipped = skipped;
    }

    public static ParseOutcome ok(ParsedStatement parsed) {
        return new ParseOutcome(parsed, null);
    }

    public static ParseOutcome skip(SkippedItem skipped) {
        return new ParseOutcome(null, skipped);
    }

    /** 可为 null。 */
    public ParsedStatement parsed() {
        return parsed;
    }

    /** 可为 null。 */
    public SkippedItem skipped() {
        return skipped;
    }
}
