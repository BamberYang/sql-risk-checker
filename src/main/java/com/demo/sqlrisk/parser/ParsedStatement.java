package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.model.ExtractedStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 语句解析产物（详细设计 §10.6）：一条语句可产生多骨架（choose），
 * 骨架按生成顺序排列，规则引擎按"任一骨架命中即停"消费（AC-4）。
 */
public final class ParsedStatement {

    private final ExtractedStatement source;
    private final List<ParsedSql> parsed;

    public ParsedStatement(ExtractedStatement source, List<ParsedSql> parsed) {
        this.source = Objects.requireNonNull(source, "source");
        this.parsed = Collections.unmodifiableList(new ArrayList<ParsedSql>(
                Objects.requireNonNull(parsed, "parsed")));
    }

    public ExtractedStatement source() {
        return source;
    }

    public List<ParsedSql> parsed() {
        return parsed;
    }

    /** 任一样骨架不确定（未知片段/截断/还原不完整）→ 语句 uncertain。 */
    public boolean isUncertain() {
        for (ParsedSql sql : parsed) {
            if (sql.skeleton().isUncertain()) {
                return true;
            }
        }
        return false;
    }
}
