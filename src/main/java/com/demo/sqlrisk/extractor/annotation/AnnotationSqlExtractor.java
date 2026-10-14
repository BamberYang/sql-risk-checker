package com.demo.sqlrisk.extractor.annotation;

import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.ChangeClassifier;
import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.ExtractContext;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.extractor.SqlSourceExtractor;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementSource;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 注解式 SQL 提取（详细设计 §9.5，FR-2）：
 * 识别 {@code @Select/@Update/@Insert/@Delete} 与 {@code @*Provider}；支持字面量/数组/静态常量/Provider；
 * {@code <script>} 文本转 {@link DynamicXmlText}（与 mapper.xml 同管道）；
 * statementId = 接口全限定名.方法名；语句范围 = 方法声明范围（含注解，行级命中 → 整句提取 D20）。
 */
public final class AnnotationSqlExtractor implements SqlSourceExtractor {

    /** 目标注解简单名 → 是否 Provider 形式。 */
    private static final Map<String, Boolean> TARGET_ANNOTATIONS;

    static {
        Map<String, Boolean> map = new LinkedHashMap<String, Boolean>();
        map.put("Select", Boolean.FALSE);
        map.put("Update", Boolean.FALSE);
        map.put("Insert", Boolean.FALSE);
        map.put("Delete", Boolean.FALSE);
        map.put("SelectProvider", Boolean.TRUE);
        map.put("InsertProvider", Boolean.TRUE);
        map.put("UpdateProvider", Boolean.TRUE);
        map.put("DeleteProvider", Boolean.TRUE);
        TARGET_ANNOTATIONS = Collections.unmodifiableMap(map);
    }

    @Override
    public String name() {
        return "annotation";
    }

    @Override
    public boolean supports(CandidateFile file) {
        return file.path().endsWith(".java");
    }

    @Override
    public ExtractResult extract(CandidateFile file, ExtractContext ctx) {
        CompilationUnit cu = SourceUnits.compilationUnit(file, ctx);
        if (cu == null) {
            return new ExtractResult(Collections.<ExtractedStatement>emptyList(),
                    Collections.singletonList(new SkippedItem(null, file.path(), 0,
                            SkippedItem.Kind.PARSE_FAILED, "解析失败：Java 源码无法解析")));
        }
        return new FileSession(file, cu, ctx).run();
    }

    /** 单文件提取会话：解析器与产物收集的状态载体（避免方法参数膨胀）。 */
    private static final class FileSession {

        private final CandidateFile file;
        private final CompilationUnit cu;
        private final JavaConstantResolver constantResolver;
        private final ProviderMethodResolver providerResolver;
        private final List<ExtractedStatement> statements = new ArrayList<ExtractedStatement>();
        private final List<SkippedItem> skipped = new ArrayList<SkippedItem>();
        private final String pkg;

        FileSession(CandidateFile file, CompilationUnit cu, ExtractContext ctx) {
            this.file = file;
            this.cu = cu;
            this.constantResolver = new JavaConstantResolver(ctx == null ? null : ctx.sourceIndex());
            this.providerResolver = new ProviderMethodResolver(ctx == null ? null : ctx.sourceIndex());
            this.pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        }

        ExtractResult run() {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                String typeFqn = pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
                for (MethodDeclaration method : type.getMethods()) {
                    extractMethod(typeFqn, method);
                }
            }
            return new ExtractResult(statements, skipped);
        }

        /** 单方法：命中目标注解且行级判定通过 → 提取语句，否则忽略。 */
        private void extractMethod(String typeFqn, MethodDeclaration method) {
            AnnotationExpr annotation = findTargetAnnotation(method);
            if (annotation == null) {
                return;
            }
            Optional<ChangeType> change = classify(file, method);
            if (!change.isPresent()) {
                return;
            }
            int line = method.getRange().map(r -> r.begin.line).orElse(0);
            String statementId = typeFqn + "." + method.getNameAsString();
            boolean provider = Boolean.TRUE.equals(TARGET_ANNOTATIONS.get(annotation.getName().getIdentifier()));
            String sqlText = provider
                    ? resolveProvider(annotation, cu, providerResolver)
                    : resolveAnnotationValue(annotation, cu, constantResolver);
            if (sqlText == null) {
                skipped.add(new SkippedItem(statementId, file.path(), line,
                        SkippedItem.Kind.UNSUPPORTED_CARRIER,
                        provider ? "无法静态解析 Provider 方法" : "无法静态解析注解值（非常量表达式）"));
                return;
            }
            Object carrier = isScript(sqlText)
                    ? new DynamicXmlText(sqlText, typeFqn)
                    : new AnnotationSqlText(sqlText);
            // Wrapper SQL 段识别（§9.5-6，v1.4）：纯文本注解中 ${ew.customSqlSegment} 类占位符
            // 根名解析到 Wrapper 家族参数 → 记录，供解析代换（§10.6）与 M8 排除（§11.4）
            List<String> wrapperSqlParams = carrier instanceof AnnotationSqlText
                    ? WrapperParamResolver.resolve(method)
                    : Collections.<String>emptyList();
            statements.add(new ExtractedStatement(statementId, StatementSource.ANNOTATION,
                    change.get(), file.path(), line, sqlText, carrier, null, wrapperSqlParams));
        }
    }

    // ---------- 内部 ----------

    private static Optional<ChangeType> classify(CandidateFile file, MethodDeclaration method) {
        if (file.change() == null) {
            return Optional.of(ChangeType.FULL);
        }
        if (!method.getRange().isPresent()) {
            return Optional.of(ChangeType.MODIFIED); // 无行范围（不应发生）：保守按变更处理
        }
        return ChangeClassifier.classify(file.change(),
                method.getRange().get().begin.line, method.getRange().get().end.line);
    }

    private static AnnotationExpr findTargetAnnotation(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (TARGET_ANNOTATIONS.containsKey(annotation.getName().getIdentifier())) {
                return annotation;
            }
        }
        return null;
    }

    private static boolean isScript(String sqlText) {
        return sqlText.trim().startsWith("<script");
    }

    /** 非 Provider 注解值：单值/数组/常量引用。 */
    private static String resolveAnnotationValue(AnnotationExpr annotation, CompilationUnit cu,
                                                 JavaConstantResolver constantResolver) {
        Expression value = annotationValue(annotation);
        if (value == null) {
            return null;
        }
        if (value instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) value).asString();
        }
        if (value instanceof ArrayInitializerExpr) {
            return joinArray((ArrayInitializerExpr) value, cu, constantResolver);
        }
        return constantResolver.resolve(value, cu).orElse(null);
    }

    /** 注解 value 表达式（单值成员或 {@code value=} 命名成员）；无 → null。 */
    private static Expression annotationValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return annotation.asSingleMemberAnnotationExpr().getMemberValue();
        }
        if (annotation.isNormalAnnotationExpr()) {
            Expression value = null;
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    value = pair.getValue();
                }
            }
            return value;
        }
        return null;
    }

    /** 数组值：逐元素（字面量/常量）解析并以换行连接；任一元素无法解析 → null。 */
    private static String joinArray(ArrayInitializerExpr array, CompilationUnit cu,
                                    JavaConstantResolver constantResolver) {
        List<String> parts = new ArrayList<String>();
        for (Expression element : array.getValues()) {
            if (element instanceof StringLiteralExpr) {
                parts.add(((StringLiteralExpr) element).asString());
            } else {
                Optional<String> resolved = constantResolver.resolve(element, cu);
                if (!resolved.isPresent()) {
                    return null;
                }
                parts.add(resolved.get());
            }
        }
        return String.join("\n", parts);
    }

    /** Provider 形式：type/method 成员静态求值。 */
    private static String resolveProvider(AnnotationExpr annotation, CompilationUnit cu,
                                          ProviderMethodResolver providerResolver) {
        if (!annotation.isNormalAnnotationExpr()) {
            return null;
        }
        String typeName = null;
        String methodName = null;
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            String name = pair.getNameAsString();
            Expression value = pair.getValue();
            if ("type".equals(name)) {
                if (value instanceof ClassExpr) {
                    typeName = ((ClassExpr) value).getType().asString();
                } else {
                    typeName = value.toString();
                }
            } else if ("method".equals(name) && value instanceof StringLiteralExpr) {
                methodName = ((StringLiteralExpr) value).asString();
            }
        }
        if (typeName == null || methodName == null) {
            return null;
        }
        return providerResolver.resolve(typeName, methodName, cu).orElse(null);
    }
}
