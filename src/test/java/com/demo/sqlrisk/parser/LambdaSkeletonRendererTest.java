package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.extractor.lambda.LambdaArg;
import com.demo.sqlrisk.extractor.lambda.LambdaCall;
import com.demo.sqlrisk.extractor.lambda.LambdaChainModel;
import com.demo.sqlrisk.util.JavaSourceIndex;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6.3 自测：谓词树渲染、连接符/嵌套组、条件重载、从句（select/set/last/apply）、未知与缺省语义。 */
class LambdaSkeletonRendererTest {

    private static EntityIndex entityIndex() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put("src/main/java/demo/entity/Order.java",
                "package demo.entity;\n"
                        + "@TableName(\"t_order\")\n"
                        + "public class Order {\n"
                        + "    @TableField(\"user_id\") private Long userId;\n"
                        + "    private String orderNo;\n"
                        + "    private String status;\n"
                        + "    @TableField(exist = false) private String extra;\n"
                        + "}\n");
        return EntityIndex.build(JavaSourceIndex.build(sources));
    }

    private static LambdaArg fn(String accessor) {
        return new LambdaArg(LambdaArg.Kind.S_FUNCTION, accessor);
    }

    private static LambdaArg val(String text) {
        return new LambdaArg(LambdaArg.Kind.OTHER, text);
    }

    private static LambdaArg lit(String text) {
        return new LambdaArg(LambdaArg.Kind.STRING_LITERAL, text);
    }

    private static LambdaCall call(String method, LambdaArg... args) {
        return new LambdaCall(method, 1, Arrays.asList(args), false, Collections.<LambdaCall>emptyList());
    }

    private static LambdaCall conditional(String method, LambdaArg... args) {
        return new LambdaCall(method, 1, Arrays.asList(args), true, Collections.<LambdaCall>emptyList());
    }

    private static LambdaCall group(String method, LambdaCall... nested) {
        return new LambdaCall(method, 1, Collections.<LambdaArg>emptyList(), false, Arrays.asList(nested));
    }

    private static SqlSkeleton render(EntityIndex index, boolean update, boolean incomplete,
                                      LambdaCall... calls) {
        LambdaChainModel model = new LambdaChainModel("OrderService.java", 10, 12, "Order",
                Arrays.asList(calls), incomplete, update);
        return LambdaSkeletonRenderer.render(model, index);
    }

    @Test
    void simple_select_with_columns_and_connectors() {
        SqlSkeleton skeleton = render(entityIndex(), false, false,
                call("select", fn("getUserId"), fn("getOrderNo")),
                call("eq", fn("getUserId"), val("1L")),
                call("or"),
                call("eq", fn("getStatus"), val("NEW")));
        assertEquals("SELECT user_id, order_no FROM t_order WHERE user_id = ? OR status = ?", skeleton.text());
        assertFalse(skeleton.isUncertain());
        assertEquals(SqlSkeleton.TAG_BASE, skeleton.strategyTag());
    }

    @Test
    void nested_consumer_group_renders_parenthesized() {
        SqlSkeleton skeleton = render(entityIndex(), false, false,
                call("eq", fn("getUserId"), val("1L")),
                group("and",
                        call("eq", fn("getStatus"), val("NEW")),
                        call("or"),
                        call("eq", fn("getOrderNo"), val("A"))));
        assertEquals("SELECT * FROM t_order WHERE user_id = ? AND (status = ? OR order_no = ?)", skeleton.text());
    }

    @Test
    void runtime_condition_is_dropped_with_provenance() {
        SqlSkeleton skeleton = render(entityIndex(), false, false,
                call("eq", fn("getUserId"), val("1L")),
                conditional("eq", fn("getStatus"), val("NEW")));
        assertEquals("SELECT * FROM t_order WHERE user_id = ?", skeleton.text(),
                "运行期条件按保守策略丢弃（暴露漏条件风险）");
        assertEquals(1, skeleton.droppedBranches().size());
        assertEquals(DroppedBranch.Reason.RUNTIME_CONDITION, skeleton.droppedBranches().get(0).reason());
        assertEquals(DroppedBranch.Role.CONDITION, skeleton.droppedBranches().get(0).role());
    }

    @Test
    void like_left_sets_leading_wildcard_fact() {
        SqlSkeleton skeleton = render(entityIndex(), false, false,
                call("likeLeft", fn("getOrderNo"), val("A")));
        assertTrue(skeleton.leadingWildcardLike());
        assertEquals("SELECT * FROM t_order WHERE order_no LIKE ?", skeleton.text());
    }

    @Test
    void update_chain_renders_set_and_where() {
        SqlSkeleton skeleton = render(entityIndex(), true, false,
                call("set", fn("getStatus"), val("1")),
                call("setIncrBy", fn("getUserId"), val("1")),
                call("eq", fn("getUserId"), val("1L")));
        assertEquals("UPDATE t_order SET status = ?, user_id = user_id + ? WHERE user_id = ?", skeleton.text());
    }

    @Test
    void entity_update_without_set_items_uses_parseable_placeholder() {
        // 实体式 update(entity, wrapper)：SET 项静态不可见 → 占位列保证可解析 + unknown（真实项目验证补充）
        SqlSkeleton skeleton = render(entityIndex(), true, false,
                call("eq", fn("getUserId"), val("1L")));
        assertEquals("UPDATE t_order SET update_placeholder = ? WHERE user_id = ?", skeleton.text());
        assertTrue(skeleton.hasUnknownFragment());
    }

    @Test
    void raw_fragments_only_from_literals() {
        SqlSkeleton applied = render(entityIndex(), false, false,
                call("apply", lit("DATE(create_time) = {0}"), val("x")),
                call("last", lit("LIMIT 10")));
        assertEquals("SELECT * FROM t_order WHERE DATE(create_time) = ? LIMIT 10", applied.text());
        assertFalse(applied.hasUnknownFragment());

        SqlSkeleton unknown = render(entityIndex(), false, false,
                call("apply", val("someVariable")),
                call("last", val("suffixVar")));
        assertTrue(unknown.hasUnknownFragment(), "非字面量片段 → unknown（不猜测）");
    }

    @Test
    void unknown_method_is_ignored_and_flagged() {
        SqlSkeleton skeleton = render(entityIndex(), false, false,
                call("eq", fn("getUserId"), val("1L")),
                call("someBrandNewMethod", fn("getStatus")));
        assertEquals("SELECT * FROM t_order WHERE user_id = ?", skeleton.text());
        assertTrue(skeleton.hasUnknownFragment(), "DD-8：未收录方法不猜测语义");
    }

    @Test
    void missing_entity_uses_placeholders_and_excluded_field_flags() {
        LambdaChainModel model = new LambdaChainModel("OrderService.java", 10, 12, "Order",
                Arrays.asList(call("eq", fn("getUserId"), val("1L"))), false, false);
        SqlSkeleton noEntity = LambdaSkeletonRenderer.render(model, null);
        assertEquals("SELECT * FROM ? WHERE user_id = ?", noEntity.text(), "实体解析失败 → 表名占位（D13）");

        SqlSkeleton excluded = render(entityIndex(), false, false, call("eq", fn("getExtra"), val("x")));
        assertTrue(excluded.hasUnknownFragment(), "exist=false 字段 → 保守标记");
    }

    @Test
    void incomplete_chain_makes_skeleton_uncertain() {
        SqlSkeleton skeleton = render(entityIndex(), false, true,
                call("eq", fn("getUserId"), val("1L")));
        assertTrue(skeleton.incomplete());
        assertTrue(skeleton.isUncertain(), "参数型 wrapper → 整句 uncertain（D11）");
    }
}
