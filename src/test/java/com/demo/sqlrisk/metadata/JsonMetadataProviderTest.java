package com.demo.sqlrisk.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T3.2 自测：加载校验矩阵、大小写不敏感、find 语义（详设 §7）。 */
class JsonMetadataProviderTest {

    @TempDir
    Path tempDir;

    private static final String ORDER_TABLE_JSON =
            "{\n"
                    + "  \"version\": \"1.0\",\n"
                    + "  \"tables\": [\n"
                    + "    {\n"
                    + "      \"table\": \"t_order\",\n"
                    + "      \"fields\": [\n"
                    + "        { \"name\": \"id\", \"type\": \"BIGINT\" },\n"
                    + "        { \"name\": \"order_no\", \"type\": \"VARCHAR(64)\" },\n"
                    + "        { \"name\": \"user_id\", \"type\": \"BIGINT\" },\n"
                    + "        { \"name\": \"status\", \"type\": \"INT\" }\n"
                    + "      ],\n"
                    + "      \"indexes\": [\n"
                    + "        { \"name\": \"PRIMARY\", \"columns\": [\"id\"] },\n"
                    + "        { \"name\": \"idx_order_no\", \"columns\": [\"order_no\"] },\n"
                    + "        { \"name\": \"idx_user_id\", \"columns\": [\"user_id\"] }\n"
                    + "      ]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}";

    private Path write(String json) throws Exception {
        Path file = tempDir.resolve("meta.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void loads_and_finds_case_insensitively() throws Exception {
        JsonMetadataProvider provider = JsonMetadataProvider.load(write(ORDER_TABLE_JSON));

        TableMetadata table = provider.find("T_Order").get();
        assertEquals("t_order", table.table());
        assertEquals(4, table.fieldsByLowerName().size());
        assertEquals("BIGINT", table.field("USER_ID").get().type());
        assertEquals(3, table.indexes().size());
        assertTrue(table.hasIndexStartingWith("order_no"));
        assertTrue(table.hasIndexStartingWith("ORDER_NO"));
        assertFalse(table.hasIndexStartingWith("status"), "status 无索引");
    }

    @Test
    void unknown_table_returns_empty() throws Exception {
        JsonMetadataProvider provider = JsonMetadataProvider.load(write(ORDER_TABLE_JSON));
        assertFalse(provider.find("t_missing").isPresent(), "未命中 = 该表无任何索引语义");
        assertFalse(provider.find(null).isPresent());
    }

    @Test
    void empty_tables_array_is_valid() throws Exception {
        JsonMetadataProvider provider = JsonMetadataProvider.load(write("{\"version\":\"1.0\",\"tables\":[]}"));
        assertFalse(provider.find("t_order").isPresent());
    }

    @Test
    void missing_file_fails_with_exit_2() {
        MetadataException e = assertThrows(MetadataException.class,
                () -> JsonMetadataProvider.load(tempDir.resolve("nope.json")));
        assertTrue(e.getMessage().contains("元数据文件不存在"));
        assertEquals(2, e.exitCode());
    }

    @Test
    void invalid_json_syntax_fails() throws Exception {
        MetadataException e = assertThrows(MetadataException.class,
                () -> JsonMetadataProvider.load(write("{ oops")));
        assertTrue(e.getMessage().contains("解析失败"));
    }

    @Test
    void structure_violations_fail() throws Exception {
        assertThrows(MetadataException.class,
                () -> JsonMetadataProvider.load(write("{\"tables\":[{\"table\":\" \"}]}")), "table 为空");
        assertThrows(MetadataException.class,
                () -> JsonMetadataProvider.load(write("{\"tables\":[{\"table\":\"t\",\"fields\":[{\"name\":\"a\"}]}]}")),
                "fields[].type 缺失");
        assertThrows(MetadataException.class,
                () -> JsonMetadataProvider.load(write("{\"tables\":[{\"table\":\"t\",\"indexes\":[{\"name\":\"i\",\"columns\":[]}]}]}")),
                "indexes[].columns 为空");
        assertThrows(MetadataException.class,
                () -> JsonMetadataProvider.load(write("{\"version\":\"1.0\"}")), "缺少 tables 数组");
    }

    @Test
    void duplicate_table_keeps_first() throws Exception {
        String json = "{\"tables\":["
                + "{\"table\":\"t_order\",\"fields\":[{\"name\":\"a\",\"type\":\"INT\"}]},"
                + "{\"table\":\"T_ORDER\",\"fields\":[{\"name\":\"b\",\"type\":\"INT\"}]}]}";
        TableMetadata table = JsonMetadataProvider.load(write(json)).find("t_order").get();
        assertTrue(table.field("a").isPresent());
        assertFalse(table.field("b").isPresent(), "重复表名取首条");
    }
}
