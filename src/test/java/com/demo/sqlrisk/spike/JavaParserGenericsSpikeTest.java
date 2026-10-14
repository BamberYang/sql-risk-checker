package com.demo.sqlrisk.spike;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T0.3 Spike C：验证 JavaParser 对 Lambda 提取所需泛型信息的可达性：
 * `extends BaseMapper&lt;Order&gt;` / `extends ServiceImpl&lt;OrderMapper, Order&gt;` 的泛型实参、
 * 字段声明与构造表达式的泛型实参——无需符号求解器即可覆盖显式泛型场景。
 *
 * <p>结论落地：M5 的 EntityIndex / LambdaSqlExtractor 实体解析优先级链按此实现。</p>
 */
class JavaParserGenericsSpikeTest {

    @Test
    void mapper_interface_generic_of_base_mapper() {
        CompilationUnit cu = StaticJavaParser.parse(
                "package demo;\npublic interface OrderMapper extends BaseMapper<Order> { Long count(); }\n");
        ClassOrInterfaceDeclaration cid = cu.getType(0).asClassOrInterfaceDeclaration();
        ClassOrInterfaceType base = cid.getExtendedTypes().get(0);
        assertEquals("BaseMapper", base.getNameAsString());
        assertEquals("Order", base.getTypeArguments().get().get(0).asString());
    }

    @Test
    void service_impl_generic_two_args() {
        CompilationUnit cu = StaticJavaParser.parse(
                "package demo;\npublic class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> {}\n");
        ClassOrInterfaceDeclaration cid = cu.getType(0).asClassOrInterfaceDeclaration();
        ClassOrInterfaceType base = cid.getExtendedTypes().get(0);
        assertEquals("ServiceImpl", base.getNameAsString());
        assertEquals("OrderMapper", base.getTypeArguments().get().get(0).asString());
        assertEquals("Order", base.getTypeArguments().get().get(1).asString());
    }

    @Test
    void wrapper_field_and_object_creation_generics() {
        CompilationUnit cu = StaticJavaParser.parse(
                "package demo;\n"
                        + "class C {\n"
                        + "  LambdaQueryWrapper<Order> wrapper;\n"
                        + "  void m() { wrapper = new LambdaQueryWrapper<Order>(); }\n"
                        + "}\n");
        ClassOrInterfaceDeclaration cid = cu.getType(0).asClassOrInterfaceDeclaration();

        FieldDeclaration field = cid.getFields().get(0);
        ClassOrInterfaceType fieldType = field.getCommonType().asClassOrInterfaceType();
        assertEquals("LambdaQueryWrapper", fieldType.getNameAsString());
        assertEquals("Order", fieldType.getTypeArguments().get().get(0).asString());

        ObjectCreationExpr creation = cid.findFirst(ObjectCreationExpr.class).get();
        assertEquals("LambdaQueryWrapper", creation.getType().getNameAsString());
        assertEquals("Order", creation.getType().getTypeArguments().get().get(0).asString());
    }
}
