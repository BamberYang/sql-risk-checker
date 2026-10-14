package com.demo.sqlrisk.extractor.annotation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.4（详设 §9.5-6）：Wrapper 家族参数解析——@Param 名优先、泛型/包名剥离、非 Wrapper 不记录。 */
class WrapperParamResolverTest {

    private static MethodDeclaration methodOf(String source) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        return cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new AssertionError("无类型声明"))
                .getMethods().get(0);
    }

    @Test
    void param_annotation_name_takes_precedence() {
        MethodDeclaration method = methodOf(
                "import com.baomidou.mybatisplus.core.conditions.Wrapper;\n"
                        + "import org.apache.ibatis.annotations.Param;\n"
                        + "class M {\n"
                        + "  Object sum(@Param(\"ew\") Wrapper<Order> wrapper);\n"
                        + "}\n");
        assertEquals(Arrays.asList("ew"), WrapperParamResolver.resolve(method));
    }

    @Test
    void wrapper_subclasses_and_fqn_recognized() {
        MethodDeclaration method = methodOf(
                "class M {\n"
                        + "  Object q(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order> w1,\n"
                        + "           QueryWrapper<Order> w2, UpdateWrapper<Order> w3);\n"
                        + "}\n");
        assertEquals(Arrays.asList("w1", "w2", "w3"), WrapperParamResolver.resolve(method),
                "泛型剥离 + 包名剥离，按源码顺序");
    }

    @Test
    void non_wrapper_types_not_recorded() {
        MethodDeclaration method = methodOf(
                "import org.apache.ibatis.annotations.Param;\n"
                        + "class M {\n"
                        + "  Object find(@Param(\"id\") Long id, String wrapper, Order order);\n"
                        + "}\n");
        assertTrue(WrapperParamResolver.resolve(method).isEmpty(),
                "类型不以 Wrapper 结尾（含名字叫 wrapper 的 String）不记录");
    }

    @Test
    void normal_annotation_form_supported() {
        MethodDeclaration method = methodOf(
                "import com.baomidou.mybatisplus.core.conditions.Wrapper;\n"
                        + "import org.apache.ibatis.annotations.Param;\n"
                        + "class M {\n"
                        + "  Object sum(@Param(value = \"ew\") Wrapper<Order> wrapper);\n"
                        + "}\n");
        assertEquals(Arrays.asList("ew"), WrapperParamResolver.resolve(method));
    }

    @Test
    void distinct_names_deduplicated_in_order() {
        MethodDeclaration method = methodOf(
                "import com.baomidou.mybatisplus.core.conditions.Wrapper;\n"
                        + "import org.apache.ibatis.annotations.Param;\n"
                        + "class M {\n"
                        + "  Object sum(@Param(\"ew\") Wrapper<Order> a, @Param(\"ew\") Wrapper<Order> b,\n"
                        + "             @Param(\"uw\") UpdateWrapper<Order> c);\n"
                        + "}\n");
        List<String> resolved = WrapperParamResolver.resolve(method);
        assertEquals(Arrays.asList("ew", "uw"), resolved);
    }
}
