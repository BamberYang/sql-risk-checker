package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.extractor.CandidateFile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5.2 自测：片段/语句站点/反向引用构建、refid 规范化、反向闭包（传递/跨文件/循环）、失败隔离。 */
class XmlFragmentIndexTest {

    private static final String BASE_XML = xml(
            "<mapper namespace=\"com.demo.mapper.BaseMapper\">",
            "  <sql id=\"baseColumns\">",
            "    id, order_no",
            "  </sql>",
            "  <sql id=\"composite\">",
            "    <include refid=\"baseColumns\"/>,",
            "    status",
            "  </sql>",
            "</mapper>");

    private static final String ORDER_XML = xml(
            "<mapper namespace=\"com.demo.mapper.OrderMapper\">",
            "  <sql id=\"filter\">",
            "    AND user_id = #{userId}",
            "  </sql>",
            "  <select id=\"selectOne\">",
            "    SELECT <include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>",
            "    FROM t_order WHERE id = #{id}",
            "  </select>",
            "  <select id=\"selectList\">",
            "    SELECT <include refid=\"com.demo.mapper.BaseMapper.composite\"/>",
            "    FROM t_order WHERE 1=1 <include refid=\"filter\"/>",
            "  </select>",
            "</mapper>");

    private static final String CYCLE_XML = xml(
            "<mapper namespace=\"c\">",
            "  <sql id=\"a\"><include refid=\"b\"/></sql>",
            "  <sql id=\"b\"><include refid=\"a\"/></sql>",
            "  <select id=\"s\"><include refid=\"a\"/></select>",
            "</mapper>");

    private static String xml(String... lines) {
        return String.join("\n", lines);
    }

    private static List<CandidateFile> files() {
        return Arrays.asList(
                CandidateFile.of("mapper/base.xml", BASE_XML),
                CandidateFile.of("mapper/order.xml", ORDER_XML));
    }

    private static Set<String> set(String... values) {
        return new TreeSet<String>(Arrays.asList(values));
    }

    @Test
    void builds_fragments_statements_and_spans() {
        XmlFragmentIndex index = XmlFragmentIndex.build(files());

        XmlFragmentIndex.FragmentDef baseColumns =
                index.fragment("com.demo.mapper.BaseMapper#baseColumns").get();
        assertEquals("mapper/base.xml", baseColumns.file());
        assertEquals(2, baseColumns.startLine());
        assertEquals(4, baseColumns.endLine());

        XmlFragmentIndex.StatementSite selectOne = index.statement("com.demo.mapper.OrderMapper.selectOne").get();
        assertEquals("select", selectOne.tagName());
        assertEquals(5, selectOne.startLine());
        assertEquals(8, selectOne.endLine());

        assertEquals(2, index.statementsOfFile("mapper/order.xml").size());
        assertEquals(1, index.statementsOfFile("mapper/order.xml").get(0).startLine() == 5 ? 1 : 0);
        assertEquals("com.demo.mapper.OrderMapper.selectOne",
                index.statementsOfFile("mapper/order.xml").get(0).key(), "按起始行排序");
        assertTrue(index.parseFailedFiles().isEmpty());
        assertTrue(index.unresolvedIncludes().isEmpty());
    }

    @Test
    void closure_is_transitive_and_cross_file() {
        XmlFragmentIndex index = XmlFragmentIndex.build(files());

        // baseColumns 变更：selectOne 直接引用；selectList 经 composite 传递（跨文件）
        Map<String, String> affected =
                index.closureOf(set("com.demo.mapper.BaseMapper#baseColumns"));
        assertEquals(set("com.demo.mapper.OrderMapper.selectOne", "com.demo.mapper.OrderMapper.selectList"),
                affected.keySet());
        assertEquals("com.demo.mapper.BaseMapper#baseColumns",
                affected.get("com.demo.mapper.OrderMapper.selectList"), "changedVia = 变更片段（种子）");

        // composite 变更：仅 selectList（经 composite），selectOne 不受影响
        Map<String, String> viaComposite = index.closureOf(set("com.demo.mapper.BaseMapper#composite"));
        assertEquals(set("com.demo.mapper.OrderMapper.selectList"), viaComposite.keySet());
        assertEquals("com.demo.mapper.BaseMapper#composite", viaComposite.get("com.demo.mapper.OrderMapper.selectList"));

        // 同文件片段 filter 变更：仅 selectList
        Map<String, String> viaFilter = index.closureOf(set("com.demo.mapper.OrderMapper#filter"));
        assertEquals(set("com.demo.mapper.OrderMapper.selectList"), viaFilter.keySet());
    }

    @Test
    void closure_handles_cycles() {
        XmlFragmentIndex index = XmlFragmentIndex.build(
                Collections.singletonList(CandidateFile.of("mapper/cycle.xml", CYCLE_XML)));
        Map<String, String> affected = index.closureOf(set("c#b"));
        assertEquals(set("c.s"), affected.keySet(), "循环引用不阻塞闭包");
    }

    @Test
    void unresolved_refid_is_recorded_not_referenced() {
        String brokenRef = xml(
                "<mapper namespace=\"x\">",
                "  <select id=\"s\"><include refid=\"not.Exists.frag\"/></select>",
                "</mapper>");
        XmlFragmentIndex index = XmlFragmentIndex.build(
                Collections.singletonList(CandidateFile.of("mapper/x.xml", brokenRef)));
        assertEquals(1, index.unresolvedIncludes().size());
        assertEquals("not.Exists.frag", index.unresolvedIncludes().get(0).refidRaw());
        assertTrue(index.closureOf(set("not#frag")).isEmpty());
    }

    @Test
    void parse_failures_are_isolated() {
        String malformed = "<mapper namespace=\"x\"><select id=\"a\">SELECT 1";
        List<CandidateFile> candidates = Arrays.asList(
                CandidateFile.of("mapper/broken.xml", malformed),
                CandidateFile.of("mapper/order.xml", ORDER_XML),
                CandidateFile.of("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>"));
        XmlFragmentIndex index = XmlFragmentIndex.build(candidates);

        assertEquals(Collections.singletonList("mapper/broken.xml"), index.parseFailedFiles());
        assertFalse(index.statement("x.a").isPresent(), "失败文件数据整体丢弃");
        assertTrue(index.statement("com.demo.mapper.OrderMapper.selectOne").isPresent(), "其余文件不受影响");
    }
}
