package com.demo.sqlrisk.extractor.annotation;

import com.demo.sqlrisk.util.JavaSourceIndex;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Optional;

/**
 * 静态常量解析（详细设计 §9.6）：支持字符串字面量、'+' 拼接、常量引用（递归一层）
 * 与静态导入（{@code import static ...}); 超出支持语法子集 → empty（不猜测），调用方按 SKIP 处理。
 */
public final class JavaConstantResolver {

    private static final int MAX_DEPTH = 2;

    private final JavaSourceIndex sourceIndex; // nullable：缺省仅当前 CU 内解析

    public JavaConstantResolver(JavaSourceIndex sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public Optional<String> resolve(Expression expr, CompilationUnit contextCu) {
        return resolve(expr, contextCu, MAX_DEPTH);
    }

    private Optional<String> resolve(Expression expr, CompilationUnit contextCu, int depth) {
        if (expr == null || depth <= 0) {
            return Optional.empty();
        }
        if (expr instanceof StringLiteralExpr) {
            return Optional.of(((StringLiteralExpr) expr).asString());
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr binary = (BinaryExpr) expr;
            if (binary.getOperator() != BinaryExpr.Operator.PLUS) {
                return Optional.empty();
            }
            Optional<String> left = resolve(binary.getLeft(), contextCu, depth - 1);
            Optional<String> right = resolve(binary.getRight(), contextCu, depth - 1);
            return left.isPresent() && right.isPresent()
                    ? Optional.of(left.get() + right.get())
                    : Optional.<String>empty();
        }
        if (expr instanceof NameExpr) {
            String name = ((NameExpr) expr).getNameAsString();
            Optional<Expression> local = findFieldInitializerInTypes(contextCu, name);
            if (local.isPresent()) {
                return resolve(local.get(), contextCu, depth - 1);
            }
            return resolveViaStaticImport(contextCu, name, depth);
        }
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) expr;
            return resolveFieldAccess(fieldAccess, contextCu, depth);
        }
        return Optional.empty();
    }

    private Optional<String> resolveFieldAccess(FieldAccessExpr fieldAccess, CompilationUnit contextCu, int depth) {
        String fieldName = fieldAccess.getNameAsString();
        Optional<String> bySimpleScope = resolveBySimpleScope(fieldAccess, contextCu, fieldName, depth);
        if (bySimpleScope.isPresent()) {
            return bySimpleScope;
        }
        return resolveByQualifiedPrefix(fieldAccess, fieldName, depth);
    }

    /** 形式 A：简单类名.常量（当前 CU 内嵌类 or 源码索引简名）。 */
    private Optional<String> resolveBySimpleScope(FieldAccessExpr fieldAccess, CompilationUnit contextCu,
                                                  String fieldName, int depth) {
        if (!(fieldAccess.getScope() instanceof NameExpr)) {
            return Optional.empty();
        }
        String scopeName = ((NameExpr) fieldAccess.getScope()).getNameAsString();
        Optional<Expression> local = findFieldInitializerInTypes(contextCu, fieldName);
        if (local.isPresent() && hasTypeNamed(contextCu, scopeName)) {
            return resolve(local.get(), contextCu, depth - 1);
        }
        if (sourceIndex == null) {
            return Optional.empty();
        }
        return sourceIndex.bySimpleName(scopeName)
                .flatMap(cu -> findFieldInitializerInTypes(cu, fieldName)
                        .flatMap(init -> resolve(init, cu, depth - 1)));
    }

    /** 形式 B：全限定前缀.常量（com.x.SqlConsts.FIND_USER）。 */
    private Optional<String> resolveByQualifiedPrefix(FieldAccessExpr fieldAccess, String fieldName, int depth) {
        String text = fieldAccess.toString();
        int lastDot = text.lastIndexOf('.');
        if (lastDot <= 0 || sourceIndex == null) {
            return Optional.empty();
        }
        return sourceIndex.byQualifiedName(text.substring(0, lastDot))
                .flatMap(cu -> findFieldInitializerInTypes(cu, fieldName)
                        .flatMap(init -> resolve(init, cu, depth - 1)));
    }

    /** 静态导入形式：import static com.x.SqlConsts.FIND_USER; */
    private Optional<String> resolveViaStaticImport(CompilationUnit cu, String name, int depth) {
        if (sourceIndex == null) {
            return Optional.empty();
        }
        for (ImportDeclaration imp : cu.getImports()) {
            Optional<String> resolved = resolveStaticImport(imp, name, depth);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    /** 单条 import static 的解析：非静态导入 / 成员名不匹配 / 目标缺失 → empty。 */
    private Optional<String> resolveStaticImport(ImportDeclaration imp, String name, int depth) {
        String importName = imp.getNameAsString();
        if (!imp.isStatic() || !importName.endsWith("." + name)) {
            return Optional.empty();
        }
        String classFqn = importName.substring(0, importName.length() - name.length() - 1);
        return sourceIndex.byQualifiedName(classFqn)
                .flatMap(target -> findFieldInitializerInTypes(target, name)
                        .flatMap(init -> resolve(init, target, depth - 1)));
    }

    /** 在 CU 的全部类型（含直接嵌套）中查找 static final 字段的初始化表达式。 */
    private static Optional<Expression> findFieldInitializerInTypes(CompilationUnit cu, String fieldName) {
        if (cu == null) {
            return Optional.empty();
        }
        for (TypeDeclaration<?> type : cu.getTypes()) {
            Optional<Expression> found = findFieldInitializerInType(type, fieldName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Expression> findFieldInitializerInType(TypeDeclaration<?> type, String fieldName) {
        Optional<Expression> field = findStaticFinalField(type, fieldName);
        if (field.isPresent()) {
            return field;
        }
        return findInNestedTypes(type, fieldName);
    }

    /** 本类型的 static final 字段初始化表达式。 */
    private static Optional<Expression> findStaticFinalField(TypeDeclaration<?> type, String fieldName) {
        for (FieldDeclaration field : type.getFields()) {
            if (!field.isStatic() || !field.isFinal()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getNameAsString().equals(fieldName) && variable.getInitializer().isPresent()) {
                    return variable.getInitializer();
                }
            }
        }
        return Optional.empty();
    }

    /** 直接嵌套类型中的字段（递归）。 */
    private static Optional<Expression> findInNestedTypes(TypeDeclaration<?> type, String fieldName) {
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member.isClassOrInterfaceDeclaration()) {
                Optional<Expression> nested =
                        findFieldInitializerInType(member.asClassOrInterfaceDeclaration(), fieldName);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasTypeNamed(CompilationUnit cu, String simpleName) {
        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (type.getNameAsString().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }
}
