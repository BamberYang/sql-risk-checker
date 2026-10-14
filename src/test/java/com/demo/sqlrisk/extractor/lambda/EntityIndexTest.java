package com.demo.sqlrisk.extractor.lambda;

import com.demo.sqlrisk.util.JavaSourceIndex;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5.4 自测：实体→表/列映射（注解/默认驼峰/exist=false）与 mapper/service 泛型反解。 */
class EntityIndexTest {

    private static EntityIndex index() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put("src/main/java/demo/entity/Order.java",
                "package demo.entity;\n"
                        + "@TableName(\"t_order\")\n"
                        + "public class Order {\n"
                        + "    private Long id;\n"
                        + "    @TableField(\"user_id\") private Long userId;\n"
                        + "    @TableField(exist = false) private String extra;\n"
                        + "    private String orderNo;\n"
                        + "}\n");
        sources.put("src/main/java/demo/entity/User.java",
                "package demo.entity;\n"
                        + "public class User { private Long id; private String userName; }\n");
        sources.put("src/main/java/demo/mapper/OrderMapper.java",
                "package demo.mapper;\npublic interface OrderMapper extends BaseMapper<Order> {}\n");
        sources.put("src/main/java/demo/service/impl/OrderServiceImpl.java",
                "package demo.service.impl;\n"
                        + "public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> {}\n");
        return EntityIndex.build(JavaSourceIndex.build(sources));
    }

    @Test
    void table_and_column_mapping() {
        EntityMeta order = index().entity("demo.entity.Order").get();
        assertEquals("t_order", order.table(), "@TableName 显式表名");
        assertEquals("user_id", order.columnOf("userId").get(), "@TableField 显式列名");
        assertEquals("order_no", order.columnOf("orderNo").get(), "未标注 → 驼峰转下划线");
        assertEquals("id", order.columnOf("id").get());
        assertFalse(order.columnOf("extra").isPresent(), "@TableField(exist=false) 排除");
        assertEquals("user_id", order.columnOfAccessor("getUserId").get(), "SFunction 访问器解析");

        EntityMeta user = index().entity("User").get();
        assertEquals("user", user.table(), "缺省表名 = 类名驼峰转下划线");
        assertEquals("user_name", user.columnOf("userName").get());
    }

    @Test
    void generic_reverse_resolution() {
        EntityIndex index = index();
        assertEquals("Order", index.entityTypeOf("demo.mapper.OrderMapper").get(), "BaseMapper<T>");
        assertEquals("Order", index.entityTypeOf("OrderMapper").get(), "简名");
        assertEquals("Order", index.entityTypeOf("demo.service.impl.OrderServiceImpl").get(), "ServiceImpl<M,T> 第 2 实参");
        assertEquals("Order", index.entityTypeOf("OrderServiceImpl").get());
    }

    @Test
    void unknown_lookups_are_empty() {
        EntityIndex index = index();
        assertFalse(index.entity("demo.NoSuch").isPresent());
        assertFalse(index.entity(null).isPresent());
        assertFalse(index.entityTypeOf("PlainClass").isPresent());
        assertTrue(index.entity("Order").isPresent(), "简名解析");
    }
}
