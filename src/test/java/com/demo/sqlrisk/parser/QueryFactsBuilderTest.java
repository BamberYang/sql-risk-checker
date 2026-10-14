package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.metadata.FieldMeta;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.metadata.TableMetadata;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6.4 自测：语句事实提取（kind/过滤/JOIN/OR/函数包裹/通配符/隐式转换/归属歧义）。 */
class QueryFactsBuilderTest {

    private static QueryFacts facts(String sql) {
        return facts(sql, null);
    }

    private static QueryFacts facts(String sql, MetadataProvider metadata) {
        return QueryFactsBuilder.build(DruidSqlParser.parse(sql), metadata, SqlSkeleton.base(sql));
    }

    private static MetadataProvider orderMetadata() {
        Map<String, FieldMeta> fields = new LinkedHashMap<String, FieldMeta>();
        fields.put("user_id", new FieldMeta("user_id", "BIGINT"));
        fields.put("order_no", new FieldMeta("order_no", "VARCHAR(64)"));
        return new MetadataProvider() {
            @Override
            public Optional<TableMetadata> find(String tableName) {
                if ("t_order".equalsIgnoreCase(tableName)) {
                    return Optional.of(new TableMetadata("t_order", fields, Collections.emptyList()));
                }
                return Optional.empty();
            }
        };
    }

    @Test
    void select_facts_and_predicate_attribution() {
        QueryFacts facts = facts("SELECT * FROM t_order WHERE user_id = ? AND status = 2");
        assertEquals(QueryFacts.StatementKind.SELECT, facts.kind());
        assertTrue(facts.selectAll());
        assertTrue(facts.hasWhere());
        assertFalse(facts.hasLimit());
        assertEquals(Collections.singleton("t_order"), facts.tables());
        assertEquals(2, facts.candidateColumnsOf("t_order").size(), "单表谓词列归属该表");
        assertFalse(facts.hasOr());
    }

    @Test
    void no_where_no_limit_for_full_scan_detection() {
        QueryFacts facts = facts("SELECT * FROM t_order");
        assertFalse(facts.hasWhere());
        assertFalse(facts.hasLimit());

        QueryFacts limited = facts("SELECT id FROM t_order LIMIT 10");
        assertTrue(limited.hasLimit());
        assertFalse(limited.selectAll());
    }

    @Test
    void update_and_delete_mysql_limit_is_detected() {
        QueryFacts update = facts("UPDATE t_order SET status = ? WHERE id = ? LIMIT 5");
        assertEquals(QueryFacts.StatementKind.UPDATE, update.kind());
        assertTrue(update.hasWhere());
        assertTrue(update.hasLimit(), "MySQL UPDATE ... LIMIT（M0.3 spike 结论）");

        QueryFacts noWhere = facts("UPDATE t_order SET status = ?");
        assertFalse(noWhere.hasWhere(), "H1 判定依据");

        QueryFacts delete = facts("DELETE FROM t_order WHERE id = ?");
        assertEquals(QueryFacts.StatementKind.DELETE, delete.kind());
    }

    @Test
    void join_facts_without_on_and_comma_link() {
        QueryFacts noOn = facts("SELECT * FROM a JOIN b");
        assertEquals(2, noOn.tables().size());
        assertTrue(noOn.hasJoinWithoutOn(), "M5 判定依据（含 CROSS）");

        QueryFacts comma = facts("SELECT * FROM a, b WHERE a.id = b.id");
        assertTrue(comma.hasCommaJoin());
        assertTrue(comma.hasCrossTableWhereLink(), "跨表关联列存在");

        QueryFacts commaUnlinked = facts("SELECT * FROM a, b WHERE a.id = 1");
        assertTrue(commaUnlinked.hasCommaJoin());
        assertFalse(commaUnlinked.hasCrossTableWhereLink(), "仅单表关联 → 逗号连接视为笛卡尔风险");
    }

    @Test
    void join_on_columns_are_attributed() {
        QueryFacts facts = facts("SELECT * FROM a JOIN b ON a.id = b.a_id WHERE a.x = 1");
        assertTrue(facts.joinOnColumnsOf("a").contains("id"));
        assertTrue(facts.joinOnColumnsOf("b").contains("a_id"));
        assertTrue(facts.candidateColumnsOf("a").contains("x"), "谓词列并入候选");
    }

    @Test
    void or_branches_are_collected() {
        QueryFacts facts = facts("SELECT * FROM t_order WHERE status = 1 OR order_no = ?");
        assertTrue(facts.hasOr());
        assertEquals(2, facts.orBranchColumns().size());
        assertTrue(facts.orBranchColumns().get(0).contains("status"));
        assertTrue(facts.orBranchColumns().get(1).contains("order_no"));
    }

    @Test
    void function_wrapped_and_leading_wildcard_flags() {
        assertTrue(facts("SELECT * FROM t_order WHERE DATE(create_time) = ?").hasFunctionWrappedColumn());
        assertTrue(facts("SELECT * FROM t_order WHERE name LIKE '%x'").hasLeadingWildcardLike());
        assertFalse(facts("SELECT * FROM t_order WHERE name LIKE 'x%'").hasLeadingWildcardLike());
        assertFalse(facts("SELECT * FROM t_order WHERE name LIKE ?").hasLeadingWildcardLike(),
                "非字面量模式不判定");
    }

    @Test
    void implicit_cast_needs_metadata() {
        assertTrue(facts("SELECT * FROM t_order WHERE user_id = '123'", orderMetadata()).hasImplicitCast(),
                "数值列 vs 字符串字面量");
        assertFalse(facts("SELECT * FROM t_order WHERE user_id = '123'").hasImplicitCast(),
                "类型信息缺失 → 不判定（§15）");
        assertFalse(facts("SELECT * FROM t_order WHERE order_no = '123'", orderMetadata()).hasImplicitCast(),
                "字符列 vs 字符串字面量：无转换");
    }

    @Test
    void multi_table_unqualified_column_is_unresolved() {
        QueryFacts facts = facts("SELECT * FROM a JOIN b ON a.id = b.a_id WHERE status = 1");
        assertFalse(facts.candidateColumnsOf("a").contains("status"), "多表未限定列归属歧义 → 不归属");
        assertFalse(facts.candidateColumnsOf("b").contains("status"));
        assertTrue(facts.predicateColumnsByTable().containsKey(QueryFacts.TABLE_UNRESOLVED),
                "DD-10：歧义列归 UNRESOLVED，不参与 M1/M6");
    }

    @Test
    void skeleton_flags_pass_through() {
        SqlSkeleton skeleton = new SqlSkeleton("SELECT * FROM t", Collections.<DroppedBranch>emptyList(),
                SqlSkeleton.TAG_BASE, false, false, true, false);
        QueryFacts facts = QueryFactsBuilder.build(DruidSqlParser.parse("SELECT * FROM t"), null, skeleton);
        assertTrue(facts.hasLeadingWildcardLike(), "Lambda likeLeft 事实并入");
    }
}
