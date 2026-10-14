package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6.4 自测：解析编排——归一化→解析→事实，及 ${} 短路候选的结构性保证（D8）。 */
class StatementParserTest {

    private static final ParseContext CTX = new ParseContext(null, null, null);

    private static ExtractedStatement annotation(String sqlText) {
        return new ExtractedStatement("m.s", StatementSource.ANNOTATION, ChangeType.FULL,
                "A.java", 1, sqlText, new AnnotationSqlText(sqlText), null);
    }

    @Test
    void valid_annotation_sql_parses_with_normalization_and_facts() {
        ParseOutcome outcome = StatementParser.parse(
                annotation("SELECT * FROM t_order WHERE id = #{id} LIMIT 1"), CTX);
        assertNotNull(outcome.parsed());
        assertEquals(1, outcome.parsed().parsed().size());
        ParsedSql parsed = outcome.parsed().parsed().get(0);
        assertEquals("SELECT * FROM t_order WHERE id = ? LIMIT 1", parsed.normalized().text());
        assertEquals(QueryFacts.StatementKind.SELECT, parsed.facts().kind());
        assertTrue(parsed.facts().hasWhere());
        assertTrue(parsed.facts().hasLimit());
    }

    @Test
    void invalid_sql_without_dollar_is_parse_failed_skip() {
        ParseOutcome outcome = StatementParser.parse(annotation("THIS IS NOT SQL"), CTX);
        assertNull(outcome.parsed());
        assertEquals(SkippedItem.Kind.PARSE_FAILED, outcome.skipped().kind());
        assertTrue(outcome.skipped().reason().startsWith("解析失败"));
        assertEquals("m.s", outcome.skipped().statementId());
    }

    @Test
    void invalid_sql_with_dollar_is_kept_as_short_circuit_candidate() {
        // MP 惯用法：${ew.customSqlSegment} 归一化后不可解析 → 保留空骨架，交 M7 按 M8 定级（§8.5 结构性保证）
        ParseOutcome outcome = StatementParser.parse(
                annotation("SELECT SUM(amount) FROM t_order ${ew.customSqlSegment}"), CTX);
        assertNotNull(outcome.parsed(), "不得因解析失败丢失 M8 短路判定");
        assertTrue(outcome.parsed().parsed().isEmpty());
        assertEquals("SELECT SUM(amount) FROM t_order ${ew.customSqlSegment}",
                outcome.parsed().source().rawText());
    }
}
