package com.demo.sqlrisk.extractor.annotation;

import com.demo.sqlrisk.util.JavaSourceIndex;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Provider 方法静态求值（详细设计 §9.6）：定位 {@code @*Provider(type, method)} 的方法体，支持
 * （a）{@code return "字面量"/拼接}；（b）局部 {@code StringBuilder/StringBuffer} 的字面量 append 序列；
 * （c）链式 builder 表达式。其余语法 → empty（调用方按 SKIP 处理）。
 */
public final class ProviderMethodResolver {

    private final JavaSourceIndex sourceIndex; // nullable：缺省仅当前 CU 内查找

    public ProviderMethodResolver(JavaSourceIndex sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public Optional<String> resolve(String providerTypeName, String methodName, CompilationUnit contextCu) {
        if (providerTypeName == null || methodName == null) {
            return Optional.empty();
        }
        Optional<CompilationUnit> target = locateType(providerTypeName, contextCu);
        if (!target.isPresent()) {
            return Optional.empty();
        }
        CompilationUnit cu = target.get();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            for (MethodDeclaration method : type.getMethods()) {
                if (method.getNameAsString().equals(methodName) && method.getBody().isPresent()) {
                    return resolveBody(method, cu);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<CompilationUnit> locateType(String typeName, CompilationUnit contextCu) {
        if (sourceIndex != null) {
            Optional<CompilationUnit> byFqn = sourceIndex.byQualifiedName(typeName);
            if (byFqn.isPresent()) {
                return byFqn;
            }
            int dot = typeName.lastIndexOf('.');
            String simple = dot >= 0 ? typeName.substring(dot + 1) : typeName;
            Optional<CompilationUnit> bySimple = sourceIndex.bySimpleName(simple);
            if (bySimple.isPresent()) {
                return bySimple;
            }
        }
        // 回退：provider 与注解同文件
        if (contextCu != null) {
            int dot = typeName.lastIndexOf('.');
            String simple = dot >= 0 ? typeName.substring(dot + 1) : typeName;
            for (TypeDeclaration<?> type : contextCu.getTypes()) {
                if (type.getNameAsString().equals(simple)) {
                    return Optional.of(contextCu);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveBody(MethodDeclaration method, CompilationUnit cu) {
        Map<String, StringBuilder> builders = new LinkedHashMap<String, StringBuilder>();
        for (Statement statement : method.getBody().get().getStatements()) {
            if (statement.isExpressionStmt()
                    && !applyExpression(statement.asExpressionStmt().getExpression(), cu, builders)) {
                return Optional.empty(); // append 参数无法静态解析 → 整体放弃（不猜测）
            }
            if (statement.isReturnStmt()) {
                return resolveReturn(statement.asReturnStmt(), cu, builders);
            }
        }
        return Optional.empty();
    }

    /** 表达式语句：builder 变量声明入表 / 已知 builder 的字面量 append 累加；false = 参数不可解析。 */
    private boolean applyExpression(Expression expression, CompilationUnit cu,
                                    Map<String, StringBuilder> builders) {
        if (expression.isVariableDeclarationExpr()) {
            collectBuilders(expression.asVariableDeclarationExpr(), builders);
            return true;
        }
        if (!expression.isMethodCallExpr() || !isSingleArgAppend(expression.asMethodCallExpr())) {
            return true;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        StringBuilder builder = builders.get(call.getScope().get().asNameExpr().getNameAsString());
        if (builder == null) {
            return true;
        }
        Optional<String> piece = resolveValue(call.getArgument(0), cu, builders);
        if (!piece.isPresent()) {
            return false;
        }
        builder.append(piece.get());
        return true;
    }

    private static boolean isSingleArgAppend(MethodCallExpr call) {
        return "append".equals(call.getNameAsString()) && call.getScope().isPresent()
                && call.getScope().get().isNameExpr() && call.getArguments().size() == 1;
    }

    private static void collectBuilders(VariableDeclarationExpr declaration,
                                        Map<String, StringBuilder> builders) {
        for (VariableDeclarator variable : declaration.getVariables()) {
            if (variable.getInitializer().isPresent() && isBuilderCreation(variable.getInitializer().get())) {
                builders.put(variable.getNameAsString(), new StringBuilder());
            }
        }
    }

    private Optional<String> resolveReturn(ReturnStmt returnStmt, CompilationUnit cu,
                                           Map<String, StringBuilder> builders) {
        if (!returnStmt.getExpression().isPresent()) {
            return Optional.empty();
        }
        return resolveValue(returnStmt.getExpression().get(), cu, builders);
    }

    private Optional<String> resolveValue(Expression expr, CompilationUnit cu,
                                          Map<String, StringBuilder> builders) {
        if (expr.isMethodCallExpr()) {
            return resolveCallValue(expr.asMethodCallExpr(), cu, builders);
        }
        if (expr.isObjectCreationExpr() && isBuilderCreation(expr)) {
            return Optional.of("");
        }
        if (expr.isNameExpr()) {
            StringBuilder builder = builders.get(expr.asNameExpr().getNameAsString());
            if (builder != null) {
                return Optional.of(builder.toString());
            }
        }
        return new JavaConstantResolver(sourceIndex).resolve(expr, cu);
    }

    /** 方法调用：{@code toString()} 取 scope 值；{@code append(...)} 拼接 scope 与参数；其余 → 常量解析兜底。 */
    private Optional<String> resolveCallValue(MethodCallExpr call, CompilationUnit cu,
                                              Map<String, StringBuilder> builders) {
        String name = call.getNameAsString();
        if ("toString".equals(name) && call.getScope().isPresent()) {
            return resolveValue(call.getScope().get(), cu, builders);
        }
        if ("append".equals(name) && call.getScope().isPresent() && call.getArguments().size() == 1) {
            Optional<String> base = resolveValue(call.getScope().get(), cu, builders);
            Optional<String> piece = resolveValue(call.getArgument(0), cu, builders);
            return base.isPresent() && piece.isPresent()
                    ? Optional.of(base.get() + piece.get())
                    : Optional.<String>empty();
        }
        return new JavaConstantResolver(sourceIndex).resolve(call, cu);
    }

    private static boolean isBuilderCreation(Expression expr) {
        if (!expr.isObjectCreationExpr()) {
            return false;
        }
        ObjectCreationExpr creation = expr.asObjectCreationExpr();
        String type = creation.getType().getNameAsString();
        return "StringBuilder".equals(type) || "StringBuffer".equals(type);
    }
}
