package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.StatementSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.4（详设 §10.6）：Wrapper 占位符代换——上下文判定、未匹配保持、代换后可解析（未知片段标记）。 */
class WrapperSqlSubstitutorTest {

    @Test
    void custom_sql_segment_after_from_gets_where_prefix() {
        WrapperSqlSubstitutor.Result result = WrapperSqlSubstitutor.substitute(
                "SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order ${ew.customSqlSegment}",
                Arrays.asList("ew"));
        assertTrue(result.applied());
        assertEquals("SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order WHERE 1 = 1", result.text());
    }

    @Test
    void condition_slot_gets_bare_predicate() {
        assertEquals("SELECT id FROM t WHERE 1 = 1 ORDER BY id",
                WrapperSqlSubstitutor.substitute("SELECT id FROM t WHERE ${ew.sqlSegment} ORDER BY id",
                        Arrays.asList("ew")).text());
        assertEquals("SELECT id FROM t WHERE a = 1 AND 1 = 1",
                WrapperSqlSubstitutor.substitute("SELECT id FROM t WHERE a = 1 AND ${ew.sqlSegment}",
                        Arrays.asList("ew")).text());
        assertEquals("SELECT id FROM t WHERE (1 = 1)",
                WrapperSqlSubstitutor.substitute("SELECT id FROM t WHERE (${ew.sqlSegment})",
                        Arrays.asList("ew")).text());
    }

    @Test
    void unmatched_placeholder_kept_verbatim() {
        WrapperSqlSubstitutor.Result result = WrapperSqlSubstitutor.substitute(
                "SELECT * FROM t WHERE a = ${otherInput}", Arrays.asList("ew"));
        assertFalse(result.applied());
        assertEquals("SELECT * FROM t WHERE a = ${otherInput}", result.text());
    }

    @Test
    void mixed_placeholders_only_wrapper_replaced() {
        WrapperSqlSubstitutor.Result result = WrapperSqlSubstitutor.substitute(
                "SELECT * FROM t WHERE a = ${userInput} ${ew.customSqlSegment}", Arrays.asList("ew"));
        assertTrue(result.applied());
        assertEquals("SELECT * FROM t WHERE a = ${userInput} WHERE 1 = 1", result.text());
    }

    @Test
    void empty_params_no_change() {
        WrapperSqlSubstitutor.Result result = WrapperSqlSubstitutor.substitute(
                "SELECT * FROM t ${ew.customSqlSegment}", Collections.<String>emptyList());
        assertFalse(result.applied());
        assertEquals("SELECT * FROM t ${ew.customSqlSegment}", result.text());
    }

    @Test
    void substituted_annotation_statement_parses_with_where_and_uncertain() {
        ExtractedStatement statement = new ExtractedStatement("m.sum", StatementSource.ANNOTATION,
                ChangeType.MODIFIED, "CashOrderMapper.java", 1,
                "SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order ${ew.customSqlSegment}",
                new AnnotationSqlText("SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order ${ew.customSqlSegment}"),
                null, Arrays.asList("ew"));
        ParseOutcome outcome = StatementParser.parse(statement, new ParseContext(null, null, null));

        assertNotNull(outcome.parsed(), "代换后应可解析（不再落入短路候选/SKIP）");
        ParsedStatement parsed = outcome.parsed();
        assertEquals(1, parsed.parsed().size());
        assertTrue(parsed.parsed().get(0).facts().hasWhere(), "占位符代换后的 WHERE 视为存在");
        assertTrue(parsed.isUncertain(), "条件来自运行期 Wrapper → 骨架标记未知片段（报告 uncertain）");
    }

    @Test
    void without_wrapper_params_falls_back_to_short_circuit_candidate() {
        ExtractedStatement statement = new ExtractedStatement("m.sum", StatementSource.ANNOTATION,
                ChangeType.MODIFIED, "CashOrderMapper.java", 1,
                "SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order ${ew.customSqlSegment}",
                new AnnotationSqlText("SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order ${ew.customSqlSegment}"),
                null);
        ParseOutcome outcome = StatementParser.parse(statement, new ParseContext(null, null, null));
        assertTrue(outcome.parsed() != null && outcome.parsed().parsed().isEmpty(),
                "无 Wrapper 参数（绑定失配）→ 空骨架短路候选，交 M8 定级");
    }
}
