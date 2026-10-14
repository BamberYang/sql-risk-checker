package com.demo.sqlrisk.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1.2 自测：投影、不可变性、必填约束。 */
class ModelValueObjectsTest {

    @Test
    void statement_meta_projects_from_extracted_statement() {
        ExtractedStatement stmt = new ExtractedStatement("com.demo.OrderMapper.updateByUser",
                StatementSource.MAPPER_XML, ChangeType.MODIFIED,
                "src/main/resources/mapper/OrderMapper.xml", 42, "<update ...>UPDATE ...</update>",
                "carrier", null);
        assertFalse(stmt.isPropagated());

        StatementMeta meta = StatementMeta.from(stmt);
        assertEquals(stmt.statementId(), meta.statementId());
        assertEquals(stmt.source(), meta.source());
        assertEquals(stmt.file(), meta.file());
        assertEquals(stmt.line(), meta.line());
        assertEquals(stmt.changeType(), meta.changeType());
        assertNull(meta.changedVia());
    }

    @Test
    void propagated_statement_exposes_changed_via() {
        ExtractedStatement stmt = new ExtractedStatement("com.demo.OrderMapper.selectAll",
                StatementSource.MAPPER_XML, ChangeType.MODIFIED,
                "src/main/resources/mapper/OrderMapper.xml", 12, "raw", "carrier",
                "com.demo.mapper.BaseMapper#baseColumns");
        assertTrue(stmt.isPropagated());
        assertEquals("com.demo.mapper.BaseMapper#baseColumns", stmt.changedVia());
    }

    @Test
    void report_model_collections_are_immutable() {
        StatementResult result = new StatementResult(
                StatementMeta.from(new ExtractedStatement("id", StatementSource.LAMBDA, ChangeType.ADDED,
                        "A.java", 1, "raw", "carrier", null)),
                "SELECT * FROM t", Collections.<Finding>emptyList(), null, false);
        ReportModel report = new ReportModel(OffsetDateTime.now(), Mode.DIFF, "head", null,
                new Summary(1, 0, 0, 0, 0, 0), Collections.singletonList(result),
                Collections.<IgnoredItem>emptyList(), Collections.<SkippedItem>emptyList());

        List<StatementResult> results = report.results();
        List<IgnoredItem> ignored = report.ignored();
        List<SkippedItem> skipped = report.skipped();
        IgnoredItem ignoredItem = new IgnoredItem("x", "f", 1, "whitelist");
        SkippedItem skippedItem = new SkippedItem(null, "f", 1, SkippedItem.Kind.READ_ERROR, "r");
        assertThrows(UnsupportedOperationException.class, () -> results.add(result));
        assertThrows(UnsupportedOperationException.class, () -> ignored.add(ignoredItem));
        assertThrows(UnsupportedOperationException.class, () -> skipped.add(skippedItem));
        assertNull(report.prodCommit());
    }

    @Test
    void finding_requires_level() {
        assertThrows(NullPointerException.class,
                () -> new Finding("H1", null, "msg", "snippet", 0));
        Finding finding = new Finding("M8", RiskLevel.MEDIUM, "msg", "snippet", 2);
        assertEquals(2, finding.skeletonIndex());
        assertEquals(RiskLevel.MEDIUM, finding.level());
    }

    @Test
    void statement_result_level_is_optional() {
        StatementMeta meta = StatementMeta.from(new ExtractedStatement("id", StatementSource.ANNOTATION,
                ChangeType.MODIFIED, "A.java", 3, "raw", "carrier", null));
        StatementResult clean = new StatementResult(meta, "SELECT id FROM t WHERE id=?",
                Collections.<Finding>emptyList(), null, false);
        assertFalse(clean.hasFindings());
        assertNull(clean.level());

        StatementResult hit = new StatementResult(meta, "UPDATE t SET a=?",
                Arrays.asList(new Finding("H1", RiskLevel.HIGH, "m", "s", 0)), RiskLevel.HIGH, true);
        assertTrue(hit.hasFindings());
        assertEquals(RiskLevel.HIGH, hit.level());
    }
}
