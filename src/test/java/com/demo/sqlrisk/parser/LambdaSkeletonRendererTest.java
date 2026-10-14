package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.extractor.lambda.LambdaArg;
import com.demo.sqlrisk.extractor.lambda.LambdaBranchGroup;
import com.demo.sqlrisk.extractor.lambda.LambdaBranchRef;
import com.demo.sqlrisk.extractor.lambda.LambdaCall;
import com.demo.sqlrisk.extractor.lambda.LambdaChainModel;
import com.demo.sqlrisk.util.JavaSourceIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    // ---------- 控制流分支枚举（§10.3-补充，M15） ----------

    private static LambdaCall branchCall(String method, int groupId, int pathIndex, LambdaArg... args) {
        return new LambdaCall(method, 1, Arrays.asList(args), false,
                Collections.<LambdaCall>emptyList(),
                Collections.singletonList(new LambdaBranchRef(groupId, pathIndex)));
    }

    private static List<SqlSkeleton> renderAll(EntityIndex index, boolean update,
                                               List<LambdaBranchGroup> groups, LambdaCall... calls) {
        LambdaChainModel model = new LambdaChainModel("OrderService.java", 10, 24, "Order",
                Arrays.asList(calls), false, update, groups);
        return LambdaSkeletonRenderer.renderAll(model, index);
    }

    private static LambdaBranchGroup ifElseGroup() {
        return new LambdaBranchGroup(0, LambdaBranchGroup.Kind.IF, Arrays.asList("if-then", "if-else"));
    }

    @Test
    void if_else_enumerates_two_skeletons_with_dropped_sibling() {
        List<SqlSkeleton> skeletons = renderAll(entityIndex(), false,
                Collections.singletonList(ifElseGroup()),
                call("eq", fn("getUserId"), val("1L")),
                branchCall("eq", 0, 0, fn("getStatus"), val("NEW")),
                branchCall("gt", 0, 1, fn("getOrderNo"), val("A")));

        assertEquals(2, skeletons.size(), "if/else → 两骨架（choose 语义）");
        assertEquals("SELECT * FROM t_order WHERE user_id = ? AND status = ?", skeletons.get(0).text());
        assertEquals("SELECT * FROM t_order WHERE user_id = ? AND order_no > ?", skeletons.get(1).text());
        assertEquals("branch:if-then", skeletons.get(0).strategyTag());
        assertEquals("branch:if-else", skeletons.get(1).strategyTag());
        // 未选中的兄弟分支记 DroppedBranch（RUNTIME_CONDITION，对齐 choose 另一路径）
        assertEquals(1, skeletons.get(0).droppedBranches().size());
        assertEquals(DroppedBranch.BranchType.IF, skeletons.get(0).droppedBranches().get(0).branchType());
        assertEquals(DroppedBranch.Reason.RUNTIME_CONDITION, skeletons.get(0).droppedBranches().get(0).reason());
        assertEquals(DroppedBranch.Role.CONDITION, skeletons.get(0).droppedBranches().get(0).role());
        assertEquals("gt()", skeletons.get(0).droppedBranches().get(0).fragmentText());
        assertEquals("eq()", skeletons.get(1).droppedBranches().get(0).fragmentText());
        assertFalse(skeletons.get(0).truncated());
    }

    @Test
    void if_without_else_drops_body_conservatively() {
        List<SqlSkeleton> skeletons = renderAll(entityIndex(), false,
                Collections.singletonList(new LambdaBranchGroup(0, LambdaBranchGroup.Kind.IF,
                        Collections.singletonList("if-skip"))),
                call("eq", fn("getUserId"), val("1L")),
                branchCall("eq", 0, LambdaBranchRef.NEVER_TAKEN, fn("getStatus"), val("NEW")));

        assertEquals(1, skeletons.size(), "无 else 的 if → 单骨架（<if> 语义，保守丢弃）");
        assertEquals("SELECT * FROM t_order WHERE user_id = ?", skeletons.get(0).text());
        assertEquals(SqlSkeleton.TAG_BASE, skeletons.get(0).strategyTag());
        assertEquals(1, skeletons.get(0).droppedBranches().size());
        assertEquals(DroppedBranch.Reason.CONSERVATIVE_FALSE, skeletons.get(0).droppedBranches().get(0).reason());
    }

    @Test
    void switch_enumerates_case_paths_with_when_and_otherwise_types() {
        List<SqlSkeleton> skeletons = renderAll(entityIndex(), false,
                Collections.singletonList(new LambdaBranchGroup(0, LambdaBranchGroup.Kind.SWITCH,
                        Arrays.asList("case 1", "case 2", "default"))),
                branchCall("eq", 0, 0, fn("getStatus"), val("NEW")),
                branchCall("eq", 0, 1, fn("getOrderNo"), val("A")),
                branchCall("gt", 0, 2, fn("getUserId"), val("5L")));

        assertEquals(3, skeletons.size(), "两个 case + default → 三骨架");
        assertEquals("SELECT * FROM t_order WHERE status = ?", skeletons.get(0).text());
        assertEquals("branch:case 1", skeletons.get(0).strategyTag());
        assertEquals("branch:default", skeletons.get(2).strategyTag());
        // 骨架#0 丢弃 case 2（WHEN）与 default（OTHERWISE）分支
        assertEquals(2, skeletons.get(0).droppedBranches().size());
        boolean sawWhen = false;
        boolean sawOtherwise = false;
        for (DroppedBranch dropped : skeletons.get(0).droppedBranches()) {
            sawWhen |= dropped.branchType() == DroppedBranch.BranchType.WHEN;
            sawOtherwise |= dropped.branchType() == DroppedBranch.BranchType.OTHERWISE;
        }
        assertTrue(sawWhen && sawOtherwise, "case → WHEN，default → OTHERWISE");
    }

    @Test
    void switch_without_default_gets_none_path() {
        List<SqlSkeleton> skeletons = renderAll(entityIndex(), false,
                Collections.singletonList(new LambdaBranchGroup(0, LambdaBranchGroup.Kind.SWITCH,
                        Arrays.asList("case 1", "switch-none"))),
                branchCall("eq", 0, 0, fn("getStatus"), val("NEW")));

        assertEquals(2, skeletons.size());
        assertEquals("SELECT * FROM t_order", skeletons.get(1).text(), "无 default：全部落空为合法路径");
        assertEquals("branch:switch-none", skeletons.get(1).strategyTag());
    }

    @Test
    void dropped_set_call_in_update_wrapper_has_set_role() {
        List<SqlSkeleton> skeletons = renderAll(entityIndex(), true,
                Collections.singletonList(ifElseGroup()),
                branchCall("set", 0, 0, fn("getStatus"), val("2")),
                branchCall("set", 0, 1, fn("getOrderNo"), val("X")));

        assertEquals(2, skeletons.size());
        assertEquals("UPDATE t_order SET status = ?", skeletons.get(0).text());
        assertEquals(DroppedBranch.Role.SET, skeletons.get(0).droppedBranches().get(0).role(),
                "set/setIncrBy/setDecrBy 分支丢弃 → SET 角色");
    }

    @Test
    void nested_groups_multiply_and_truncate_at_max_skeletons() {
        List<LambdaBranchGroup> groups = new ArrayList<LambdaBranchGroup>();
        for (int g = 0; g < 4; g++) {
            groups.add(new LambdaBranchGroup(g, LambdaBranchGroup.Kind.IF, Arrays.asList("if-then", "if-else")));
        }
        List<LambdaCall> calls = new ArrayList<LambdaCall>();
        for (int g = 0; g < 4; g++) {
            calls.add(branchCall("eq", g, 0, fn("getStatus"), val("S" + g)));
        }
        List<SqlSkeleton> skeletons = renderAll(entityIndex(), false, groups,
                calls.toArray(new LambdaCall[0]));

        assertEquals(8, skeletons.size(), "16 组合 → MAX_SKELETONS=8 截断（DD-4）");
        assertTrue(skeletons.get(0).truncated(), "截断标记");
        assertEquals("branch:if-then, if-then, if-then, if-then", skeletons.get(0).strategyTag());
    }
}
