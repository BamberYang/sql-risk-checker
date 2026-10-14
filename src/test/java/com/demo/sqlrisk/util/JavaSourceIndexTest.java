package com.demo.sqlrisk.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1.3 自测：全限定名/简名索引、解析失败可见性、确定性。 */
class JavaSourceIndexTest {

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put("src/main/java/demo/entity/Order.java",
                "package demo.entity;\npublic class Order { private Long userId; }\n");
        sources.put("src/main/java/demo/mapper/OrderMapper.java",
                "package demo.mapper;\npublic interface OrderMapper extends BaseMapper<Order> {}\n");
        sources.put("src/main/java/demo/Broken.java", "class {");
        return sources;
    }

    @Test
    void indexes_by_qualified_and_simple_name() {
        JavaSourceIndex index = JavaSourceIndex.build(sources());

        assertTrue(index.byQualifiedName("demo.entity.Order").isPresent());
        assertTrue(index.byQualifiedName("demo.mapper.OrderMapper").isPresent());
        assertTrue(index.bySimpleName("Order").isPresent());
        assertTrue(index.bySimpleName("OrderMapper").isPresent());

        assertFalse(index.byQualifiedName("demo.NotExists").isPresent());
        assertFalse(index.bySimpleName("NotExists").isPresent());
    }

    @Test
    void broken_file_is_reported_not_fatal() {
        JavaSourceIndex index = JavaSourceIndex.build(sources());
        assertEquals(1, index.failedFiles().size());
        assertTrue(index.failedFiles().contains("src/main/java/demo/Broken.java"));
        assertTrue(index.byQualifiedName("demo.entity.Order").isPresent(), "失败文件不影响其余索引");
    }
}
