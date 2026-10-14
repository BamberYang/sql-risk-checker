package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.extractor.StatementSelection;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5.1 自测：按行范围切片完整语句原文（D20 整句提取）、载体内涵、缺失内容 → SKIP。 */
class MapperXmlExtractorTest {

    private static final String ORDER_XML = String.join("\n",
            "<mapper namespace=\"com.demo.mapper.OrderMapper\">", // 1
            "  <select id=\"selectOne\">",                        // 2
            "    SELECT <include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>", // 3
            "    FROM t_order WHERE id = #{id}",                  // 4
            "  </select>",                                        // 5
            "</mapper>");                                         // 6

    private static Map<String, String> contents() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("mapper/order.xml", ORDER_XML);
        return map;
    }

    @Test
    void slices_complete_statement_raw_text() {
        StatementSelection selection = new StatementSelection("com.demo.mapper.OrderMapper.selectOne",
                "com.demo.mapper.OrderMapper", "mapper/order.xml", 2, 5,
                ChangeType.MODIFIED, "com.demo.mapper.BaseMapper#baseColumns");
        ExtractResult result = MapperXmlExtractor.extract(Collections.singletonList(selection), contents());

        assertEquals(1, result.statements().size());
        assertTrue(result.skipped().isEmpty());
        ExtractedStatement statement = result.statements().get(0);
        assertEquals(StatementSource.MAPPER_XML, statement.source());
        assertEquals(ChangeType.MODIFIED, statement.changeType());
        assertEquals("com.demo.mapper.BaseMapper#baseColumns", statement.changedVia());
        assertEquals(2, statement.line());
        assertEquals(String.join("\n",
                "  <select id=\"selectOne\">",
                "    SELECT <include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>",
                "    FROM t_order WHERE id = #{id}",
                "  </select>"), statement.rawText(), "完整语句原文（含全部动态标签）");

        DynamicXmlText carrier = (DynamicXmlText) statement.carrier();
        assertEquals("com.demo.mapper.OrderMapper", carrier.ownerNamespace());
        assertEquals(statement.rawText(), carrier.fragmentText());
    }

    @Test
    void missing_file_content_yields_skip() {
        StatementSelection selection = new StatementSelection("x.y", "x", "missing.xml", 1, 2,
                ChangeType.ADDED, null);
        ExtractResult result = MapperXmlExtractor.extract(Collections.singletonList(selection),
                Collections.<String, String>emptyMap());
        assertTrue(result.statements().isEmpty());
        assertEquals(1, result.skipped().size());
        assertEquals(SkippedItem.Kind.READ_ERROR, result.skipped().get(0).kind());
    }

    @Test
    void slice_lines_boundaries_and_crlf() {
        String content = "l1\r\nl2\r\nl3\r\nl4";
        assertEquals("l2\nl3", MapperXmlExtractor.sliceLines(content, 2, 3), "去除行尾 \\r");
        assertEquals("l1\nl2\nl3\nl4", MapperXmlExtractor.sliceLines(content, 1, 99), "越界截断到文件末尾");
        List<String> flipped = Arrays.asList(MapperXmlExtractor.sliceLines(content, 3, 2));
        assertEquals(Collections.singletonList(""), flipped, "非法区间返回空串");
    }
}
