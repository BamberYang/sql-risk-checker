package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.testutil.JsonAssertions;
import com.demo.sqlrisk.testutil.ReportFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8.4：JSON 报告序列化契约（详细设计 §12.3、需求 §10.2 + D5 追加字段）：
 * 黄金 JSON 全量比对、键顺序、serializeNulls 键保留。
 */
class JsonReporterTest {

    /** 黄金 JSON：DIFF 模式、4 结果（H/M/L/无命中）、1 忽略、2 SKIP、字段顺序全覆盖。 */
    private static final String EXPECTED = "{"
            + "\"generatedAt\": \"" + ReportFixtures.GENERATED_AT + "\","
            + "\"mode\": \"DIFF\","
            + "\"headCommit\": \"" + ReportFixtures.HEAD_COMMIT + "\","
            + "\"prodCommit\": \"" + ReportFixtures.PROD_COMMIT + "\","
            + "\"summary\": {\"total\": 4, \"high\": 1, \"medium\": 1, \"low\": 1, \"skip\": 2, \"ignored\": 1},"
            + "\"results\": ["
            + "  {\"statementId\": \"com.demo.mapper.OrderMapper.updateStatus\","
            + "   \"source\": \"MAPPER_XML\","
            + "   \"file\": \"src/main/resources/mapper/OrderMapper.xml\","
            + "   \"line\": 42,"
            + "   \"changeType\": \"MODIFIED\","
            + "   \"sql\": \"UPDATE t_order SET status = ?\","
            + "   \"findings\": [{\"ruleId\": \"H1\", \"level\": \"HIGH\","
            + "                  \"message\": \"" + ReportFixtures.H1_MESSAGE + "\","
            + "                  \"snippet\": \"UPDATE t_order SET status = ?\", \"skeleton\": 0}],"
            + "   \"level\": \"HIGH\", \"uncertain\": false,"
            + "   \"changedVia\": \"com.demo.mapper.Common.baseColumns\"},"
            + "  {\"statementId\": \"com.demo.mapper.OrderMapper.findByName\","
            + "   \"source\": \"ANNOTATION\","
            + "   \"file\": \"src/main/java/com/demo/mapper/OrderMapper.java\","
            + "   \"line\": 10,"
            + "   \"changeType\": \"MODIFIED\","
            + "   \"sql\": \"SELECT id FROM t_order WHERE name = ?\","
            + "   \"findings\": [{\"ruleId\": \"M1\", \"level\": \"MEDIUM\","
            + "                  \"message\": \"" + ReportFixtures.M1_MESSAGE + "\","
            + "                  \"snippet\": \"SELECT id FROM t_order WHERE name = ?\", \"skeleton\": 0}],"
            + "   \"level\": \"MEDIUM\", \"uncertain\": true, \"changedVia\": null},"
            + "  {\"statementId\": \"com.demo.mapper.OrderMapper.find\","
            + "   \"source\": \"MAPPER_XML\","
            + "   \"file\": \"src/main/resources/mapper/OrderMapper.xml\","
            + "   \"line\": 60,"
            + "   \"changeType\": \"ADDED\","
            + "   \"sql\": \"SELECT * FROM t_order WHERE id = ?\","
            + "   \"findings\": [{\"ruleId\": \"L2\", \"level\": \"LOW\", \"message\": \"SELECT * 且带过滤条件\","
            + "                  \"snippet\": \"SELECT * FROM t_order WHERE id = ?\", \"skeleton\": 0}],"
            + "   \"level\": \"LOW\", \"uncertain\": false, \"changedVia\": null},"
            + "  {\"statementId\": \"com.demo.mapper.OrderMapper.count\","
            + "   \"source\": \"MAPPER_XML\","
            + "   \"file\": \"src/main/resources/mapper/OrderMapper.xml\","
            + "   \"line\": 80,"
            + "   \"changeType\": \"MODIFIED\","
            + "   \"sql\": \"SELECT count(1) FROM t_order WHERE state = ?\","
            + "   \"findings\": [],"
            + "   \"level\": null, \"uncertain\": false, \"changedVia\": null}"
            + "],"
            + "\"ignored\": [{\"statementId\": \"m.legacy\","
            + "              \"file\": \"src/main/resources/mapper/Legacy.xml\", \"line\": 5,"
            + "              \"reason\": \"whitelist\"}],"
            + "\"skipped\": ["
            + "  {\"statementId\": \"m.bad\", \"file\": \"src/main/resources/mapper/Bad.xml\", \"line\": 3,"
            + "   \"kind\": \"PARSE_FAILED\", \"reason\": \"解析失败：意外的记号 '&&'\"},"
            + "  {\"statementId\": null, \"file\": \"src/main/java/com/demo/mapper/Prov.java\", \"line\": 0,"
            + "   \"kind\": \"UNSUPPORTED_CARRIER\", \"reason\": \"Provider 方法无法静态求值\"}"
            + "]"
            + "}";

    @Test
    void golden_json_matches_contract() {
        JsonAssertions.assertEqualsJson("报告 JSON（DIFF 黄金样例）",
                EXPECTED, JsonReporter.toJson(ReportFixtures.sampleReport()));
    }

    @Test
    void top_level_and_result_keys_follow_declaration_order() {
        String json = JsonReporter.toJson(ReportFixtures.sampleReport());
        assertIndentOrder(json, 2, "generatedAt", "mode", "headCommit", "prodCommit",
                "summary", "results", "ignored", "skipped");
        assertIndentOrder(json, 6, "statementId", "source", "file", "line", "changeType",
                "sql", "findings", "level", "uncertain", "changedVia");
        assertIndentOrder(json, 10, "ruleId", "level", "message", "snippet", "skeleton");
    }

    @Test
    void null_fields_are_serialized_as_null_keys() {
        String json = JsonReporter.toJson(ReportFixtures.sampleReport(Mode.FULL, null));
        assertTrue(json.contains("\"prodCommit\": null"), "FULL 模式 prodCommit 键保留（serializeNulls）");
        assertTrue(json.contains("\"level\": null"), "无命中的语句 level 为 null 键保留");
        assertTrue(json.contains("\"changedVia\": null"), "未传播语句 changedVia 为 null 键保留");
    }

    @Test
    void empty_report_serializes_empty_arrays() {
        String json = JsonReporter.toJson(ReportFixtures.emptyReport(Mode.FULL));
        assertTrue(json.contains("\"results\": []"));
        assertTrue(json.contains("\"ignored\": []"));
        assertTrue(json.contains("\"skipped\": []"));
    }

    /** 校验同一缩进层级下键的首次出现顺序（Gson pretty 输出中该层级的键唯一归属同一对象）。 */
    private static void assertIndentOrder(String json, int indent, String... keys) {
        int previous = -1;
        for (String key : keys) {
            StringBuilder needle = new StringBuilder("\n");
            for (int i = 0; i < indent; i++) {
                needle.append(' ');
            }
            needle.append('"').append(key).append("\": ");
            int at = json.indexOf(needle.toString());
            assertTrue(at > previous, "键顺序错误：" + key + " 应出现在前一键之后（缩进 " + indent + "）");
            previous = at;
        }
    }
}
