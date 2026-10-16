package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.metadata.FieldMeta;
import com.demo.sqlrisk.metadata.IndexMeta;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.metadata.TableMetadata;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 正例/反例/边界（v1.13 语义：包裹列须为索引前缀列，与 M1/M6 同口径）。
 * 元数据桩 {@link RuleTestSupport#orderMetadata()}：t_order 索引 PRIMARY(id)、idx_user_id(user_id)。
 */
class M2RuleTest {

    private final M2Rule rule = new M2Rule();

    @Test
    void function_wrapped_indexed_column_fires() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE DATE(user_id) = '2026-01-01'",
                RuleTestSupport.orderMetadata(), null));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("函数包裹"), findings.get(0).message());
        assertTrue(findings.get(0).message().contains("t_order(user_id)"), findings.get(0).message());
    }

    @Test
    void function_wrapped_unindexed_column_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE DATE(status) = '2026-01-01'",
                RuleTestSupport.orderMetadata(), null)).isEmpty(),
                "包裹列无任何索引前缀可失 → 归 M1 判定，不重复报");
    }

    @Test
    void function_wrapped_column_outside_metadata_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_other WHERE DATE(create_time) = '2026-01-01'",
                RuleTestSupport.orderMetadata(), null)).isEmpty(), "表不在元数据中 = 无索引可失");
    }

    @Test
    void missing_metadata_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE DATE(user_id) = '2026-01-01'")).isEmpty(),
                "元数据缺失（ctx 无 provider）：无法证明索引存在 → 不判定");
    }

    @Test
    void composite_index_second_column_does_not_fire() {
        // 联合索引 (a, b)：包裹 b（非首列）本就用不上该索引，根因不是函数包裹
        MetadataProvider metadata = compositeMetadata();
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_cover WHERE DATE(b) = '2026-01-01'", metadata, null)).isEmpty());
        List<Finding> fired = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_cover WHERE DATE(a) = '2026-01-01'", metadata, null));
        assertEquals(1, fired.size(), "联合索引首列被包裹 → 命中");
        assertTrue(fired.get(0).message().contains("t_cover(a)"), fired.get(0).message());
    }

    @Test
    void plain_column_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = 1", RuleTestSupport.orderMetadata(), null)).isEmpty());
    }

    @Test
    void function_without_column_arg_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = LENGTH('abc')",
                RuleTestSupport.orderMetadata(), null)).isEmpty(), "函数实参无列引用");
    }

    private static MetadataProvider compositeMetadata() {
        Map<String, FieldMeta> fields = new LinkedHashMap<String, FieldMeta>();
        fields.put("a", new FieldMeta("a", "BIGINT"));
        fields.put("b", new FieldMeta("b", "BIGINT"));
        final TableMetadata table = new TableMetadata("t_cover", fields,
                Collections.singletonList(new IndexMeta("idx_a_b", Arrays.asList("a", "b"))));
        return new MetadataProvider() {
            @Override
            public Optional<TableMetadata> find(String tableName) {
                return "t_cover".equalsIgnoreCase(tableName) ? Optional.of(table) : Optional.empty();
            }
        };
    }
}
