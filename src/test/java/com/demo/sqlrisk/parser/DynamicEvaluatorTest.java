package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.xml.XmlFragmentIndex;
import com.demo.sqlrisk.util.TextUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6.2 自测：折叠规则表（if/choose/where/set/trim/foreach/include）、role 溯源、上限截断、失败语义。 */
class DynamicEvaluatorTest {

    private static final DynamicEvalContext BARE = new DynamicEvalContext(null, null);

    private static List<SqlSkeleton> eval(String fragment) {
        return DynamicEvaluator.evaluate(new DynamicXmlText(fragment, "com.demo.mapper.OrderMapper"), BARE);
    }

    private static String collapsed(SqlSkeleton skeleton) {
        return TextUtils.collapseWhitespace(skeleton.text());
    }

    @Test
    void if_is_always_false_and_where_collapses() {
        List<SqlSkeleton> skeletons = eval(
                "<update id=\"u\">\n"
                        + "  UPDATE t_order SET status = 1\n"
                        + "  <where>\n"
                        + "    <if test=\"userId != null\">AND user_id = #{userId}</if>\n"
                        + "  </where>\n"
                        + "</update>");
        assertEquals(1, skeletons.size());
        SqlSkeleton skeleton = skeletons.get(0);
        assertEquals("UPDATE t_order SET status = 1", collapsed(skeleton), "唯一条件在 if 内 → WHERE 整体消失（H 组命中面）");
        assertEquals("base", skeleton.strategyTag());
        assertEquals(1, skeleton.droppedBranches().size());
        DroppedBranch dropped = skeleton.droppedBranches().get(0);
        assertEquals(DroppedBranch.BranchType.IF, dropped.branchType());
        assertEquals(DroppedBranch.Reason.CONSERVATIVE_FALSE, dropped.reason());
        assertEquals(DroppedBranch.Role.CONDITION, dropped.role(), "位于 where 内 → CONDITION");
        assertTrue(skeleton.hasDroppedRole(DroppedBranch.Role.CONDITION));
    }

    @Test
    void bare_if_where_is_condition_role() {
        // 裸 <if>WHERE …</if>（无 where 包装）：折叠后 WHERE 消失 → role=CONDITION（D19 回写，驱动 H 组附注）
        List<SqlSkeleton> skeletons = eval(
                "<update id=\"u\">UPDATE t_order SET banned = 1 "
                        + "<if test=\"st != null\">WHERE status = #{st}</if></update>");
        assertEquals(1, skeletons.size());
        DroppedBranch dropped = skeletons.get(0).droppedBranches().get(0);
        assertEquals(DroppedBranch.Role.CONDITION, dropped.role());
        assertEquals("UPDATE t_order SET banned = 1", collapsed(skeletons.get(0)));
    }

    @Test
    void bare_if_plain_text_stays_raw_role() {
        // 非 WHERE 开头（也不是 JOIN）的裸 if → RAW，不误报附注
        List<SqlSkeleton> skeletons = eval(
                "<select id=\"s\">SELECT id FROM t_order WHERE id = 1 "
                        + "<if test=\"x != null\">GROUP BY id</if></select>");
        assertEquals(DroppedBranch.Role.RAW, skeletons.get(0).droppedBranches().get(0).role());
    }

    @Test
    void choose_emits_two_tagged_skeletons() {
        List<SqlSkeleton> skeletons = eval(
                "<select id=\"s\">SELECT * FROM t_order "
                        + "<choose>"
                        + "<when test=\"a != null\">WHERE a = 1</when>"
                        + "<otherwise>WHERE b = 2</otherwise>"
                        + "</choose></select>");
        assertEquals(2, skeletons.size());
        assertEquals("choose:when#0", skeletons.get(0).strategyTag());
        assertEquals("SELECT * FROM t_order WHERE a = 1", collapsed(skeletons.get(0)));
        assertEquals("choose:otherwise", skeletons.get(1).strategyTag());
        assertEquals("SELECT * FROM t_order WHERE b = 2", collapsed(skeletons.get(1)));
    }

    @Test
    void trim_strips_overrides_and_applies_prefix() {
        List<SqlSkeleton> skeletons = eval(
                "<select id=\"s\">SELECT * FROM t_order "
                        + "<trim prefix=\"WHERE\" prefixOverrides=\"AND | OR \">"
                        + "<if test=\"cond\">1 = 1</if> AND x = 1"
                        + "</trim></select>");
        assertEquals("SELECT * FROM t_order WHERE x = 1", collapsed(skeletons.get(0)));
    }

    @Test
    void set_folds_trailing_comma() {
        List<SqlSkeleton> skeletons = eval(
                "<update id=\"u\">UPDATE t_order "
                        + "<set>status = 1, <if test=\"a\">remark = #{r},</if></set>"
                        + "</update>");
        assertEquals("UPDATE t_order SET status = 1", collapsed(skeletons.get(0)));
    }

    @Test
    void foreach_expands_single_element() {
        List<SqlSkeleton> skeletons = eval(
                "<select id=\"s\">SELECT * FROM t_order WHERE id IN "
                        + "<foreach collection=\"ids\" item=\"id\" open=\"(\" close=\")\" separator=\",\">#{id}</foreach>"
                        + "</select>");
        assertEquals("SELECT * FROM t_order WHERE id IN (#{id})", collapsed(skeletons.get(0)),
                "单元素展开（占位符保留至归一化阶段）");
    }

    @Test
    void unknown_tag_is_passed_through_and_flagged() {
        List<SqlSkeleton> skeletons = eval(
                "<select id=\"s\">SELECT 1 <foobar>AND x = 1</foobar></select>");
        assertEquals("SELECT 1 AND x = 1", collapsed(skeletons.get(0)));
        assertTrue(skeletons.get(0).hasUnknownFragment());
        assertTrue(skeletons.get(0).isUncertain());
    }

    @Test
    void include_expands_with_properties_and_transitively() {
        String base = "<mapper namespace=\"com.demo.mapper.BaseMapper\">\n"
                + "  <sql id=\"columns\">${cols}, status</sql>\n"
                + "</mapper>";
        String order = "<mapper namespace=\"com.demo.mapper.OrderMapper\">\n"
                + "  <sql id=\"filter\">AND user_id = #{u}</sql>\n"
                + "</mapper>";
        Map<String, String> contents = new HashMap<String, String>();
        contents.put("mapper/base.xml", base);
        contents.put("mapper/order.xml", order);
        XmlFragmentIndex index = XmlFragmentIndex.build(Arrays.asList(
                CandidateFile.of("mapper/base.xml", base), CandidateFile.of("mapper/order.xml", order)));
        DynamicEvalContext ctx = new DynamicEvalContext(index, contents);

        List<SqlSkeleton> skeletons = DynamicEvaluator.evaluate(new DynamicXmlText(
                "<select id=\"s\">SELECT <include refid=\"com.demo.mapper.BaseMapper.columns\">"
                        + "<property name=\"cols\" value=\"id, user_id\"/>"
                        + "</include> FROM t_order "
                        + "<include refid=\"filter\"/>"
                        + "</select>", "com.demo.mapper.OrderMapper"), ctx);

        assertEquals("SELECT id, user_id, status FROM t_order AND user_id = #{u}", collapsed(skeletons.get(0)),
                "属性替换 + 跨文件 include（裸 refid 按 ownerNamespace 解析）");
    }

    @Test
    void include_cycle_fails_with_exception() {
        String cycle = "<mapper namespace=\"c\">\n"
                + "  <sql id=\"a\"><include refid=\"b\"/></sql>\n"
                + "  <sql id=\"b\"><include refid=\"a\"/></sql>\n"
                + "</mapper>";
        Map<String, String> contents = new HashMap<String, String>();
        contents.put("mapper/cycle.xml", cycle);
        DynamicEvalContext ctx = new DynamicEvalContext(
                XmlFragmentIndex.build(Arrays.asList(CandidateFile.of("mapper/cycle.xml", cycle))), contents);

        DynamicXmlText text = new DynamicXmlText(
                "<select id=\"s\"><include refid=\"c.a\"/></select>", "c");
        DynamicEvaluationException e = assertThrows(DynamicEvaluationException.class,
                () -> DynamicEvaluator.evaluate(text, ctx));
        assertTrue(e.getMessage().contains("深度"), "循环引用由深度上限拦截");
    }

    @Test
    void unresolved_include_fails() {
        DynamicXmlText text = new DynamicXmlText(
                "<select id=\"s\"><include refid=\"missing.frag\"/></select>", "ns");
        DynamicEvalContext emptyCtx = new DynamicEvalContext(null, null);
        DynamicEvaluationException e = assertThrows(DynamicEvaluationException.class,
                () -> DynamicEvaluator.evaluate(text, emptyCtx));
        assertTrue(e.getMessage().contains("无法展开") || e.getMessage().contains("无法解析"));
    }

    @Test
    void skeleton_enumeration_is_capped_and_truncated() {
        StringBuilder xml = new StringBuilder("<select id=\"s\">SELECT * FROM t ");
        for (String flag : Arrays.asList("a", "b", "c", "d")) {
            xml.append("<choose><when test=\"").append(flag).append("\">AND ")
                    .append(flag).append(" = 1</when><otherwise>AND ")
                    .append(flag).append(" = 2</otherwise></choose> ");
        }
        xml.append("</select>");

        List<SqlSkeleton> skeletons = eval(xml.toString());
        assertEquals(DynamicEvaluator.MAX_SKELETONS, skeletons.size(), "2^4=16 组合被上限截断为 8");
        for (SqlSkeleton skeleton : skeletons) {
            assertTrue(skeleton.truncated());
            assertTrue(skeleton.strategyTag().contains("choose:"));
        }
    }
}
