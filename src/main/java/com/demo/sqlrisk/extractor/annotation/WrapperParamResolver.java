package com.demo.sqlrisk.extractor.annotation;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wrapper 参数解析（详细设计 §9.5-6，v1.4）：
 * 方法参数中类型原始名以 {@code Wrapper} 结尾（MyBatis-Plus Wrapper 家族，泛型自动剥离）者，
 * 其对外名 = {@code @Param("name")} 的值，缺省取参数自身名；用于识别注解 SQL 中
 * {@code ${name…}} 为"框架经 Wrapper 参数生成的运行期条件段"（M8 排除项 / 解析代换输入）。
 */
public final class WrapperParamResolver {

    private WrapperParamResolver() {
    }

    /** @return Wrapper 家族参数的对外名（源码顺序；无则空列表） */
    public static List<String> resolve(MethodDeclaration method) {
        if (method == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Parameter parameter : method.getParameters()) {
            if (!isWrapperType(parameter.getTypeAsString())) {
                continue;
            }
            String name = externalName(parameter);
            if (name != null && !name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** @Param("name") 优先；缺省取参数自身名。 */
    private static String externalName(Parameter parameter) {
        for (AnnotationExpr annotation : parameter.getAnnotations()) {
            if (!"Param".equals(annotation.getName().getIdentifier())) {
                continue;
            }
            String value = paramAnnotationValue(annotation);
            if (value != null) {
                return value;
            }
        }
        return parameter.getNameAsString();
    }

    private static String paramAnnotationValue(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            Expression value = ((SingleMemberAnnotationExpr) annotation).getMemberValue();
            return value instanceof StringLiteralExpr ? ((StringLiteralExpr) value).asString() : null;
        }
        if (annotation instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                if ("value".equals(pair.getNameAsString()) && pair.getValue() instanceof StringLiteralExpr) {
                    return ((StringLiteralExpr) pair.getValue()).asString();
                }
            }
        }
        return null;
    }

    /** 类型原始名（剥离泛型与包名前缀）以 {@code Wrapper} 结尾。 */
    private static boolean isWrapperType(String typeAsString) {
        String raw = typeAsString == null ? "" : typeAsString;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        int dot = raw.lastIndexOf('.');
        if (dot >= 0) {
            raw = raw.substring(dot + 1);
        }
        return raw.trim().endsWith("Wrapper");
    }
}
