package com.demo.sqlrisk.extractor.lambda;

import com.demo.sqlrisk.util.JavaSourceIndex;
import com.demo.sqlrisk.util.TextUtils;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 全仓实体与 Mapper/Service 泛型索引（详细设计 §9.8）：
 *
 * <ul>
 *   <li>实体映射：{@code @TableName/@TableField/@TableId/@TableField(exist=false)} → {@link EntityMeta}；</li>
 *   <li>泛型反解：{@code extends BaseMapper<T> / IService<T>}（第 1 实参）、{@code ServiceImpl<M,T>}（第 2 实参）
 *       → 类型名 → 实体类型名；</li>
 *   <li>索引只读、构建一次复用（§16）；简名冲突取字典序首个（确定性）。</li>
 * </ul>
 */
public final class EntityIndex {

    private final Map<String, EntityMeta> byName;    // FQN + 简名
    private final Map<String, String> entityOfType;  // mapper/service 类型名（FQN + 简名）→ 实体类型名

    private EntityIndex(Map<String, EntityMeta> byName, Map<String, String> entityOfType) {
        this.byName = Collections.unmodifiableMap(byName);
        this.entityOfType = Collections.unmodifiableMap(entityOfType);
    }

    public static EntityIndex build(JavaSourceIndex sourceIndex) {
        Map<String, EntityMeta> byName = new TreeMap<String, EntityMeta>();
        Map<String, String> entityOfType = new TreeMap<String, String>();

        for (CompilationUnit cu : sourceIndex.allCompilationUnits()) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            for (TypeDeclaration<?> type : cu.getTypes()) {
                indexType(type, pkg, byName, entityOfType);
            }
        }
        return new EntityIndex(byName, entityOfType);
    }

    /** 单个类型入索引：实体映射（FQN + 简名，先到先得）与泛型反解。 */
    private static void indexType(TypeDeclaration<?> type, String pkg,
                                  Map<String, EntityMeta> byName, Map<String, String> entityOfType) {
        String simple = type.getNameAsString();
        String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;

        EntityMeta meta = buildEntityMeta(type, simple);
        putIfAbsent(byName, fqn, meta);
        putIfAbsent(byName, simple, meta);

        if (!type.isClassOrInterfaceDeclaration()) {
            return;
        }
        for (ClassOrInterfaceType extended : type.asClassOrInterfaceDeclaration().getExtendedTypes()) {
            String entityTypeName = entityTypeName(extended);
            if (entityTypeName != null) {
                putIfAbsent(entityOfType, fqn, entityTypeName);
                putIfAbsent(entityOfType, simple, entityTypeName);
            }
        }
    }

    private static <V> void putIfAbsent(Map<String, V> map, String key, V value) {
        if (!map.containsKey(key)) {
            map.put(key, value);
        }
    }

    private static String entityTypeName(ClassOrInterfaceType extended) {
        String base = extended.getNameAsString();
        if ("BaseMapper".equals(base) || "IService".equals(base)) {
            return typeArg(extended, 0);
        }
        if ("ServiceImpl".equals(base)) {
            return typeArg(extended, 1);
        }
        return null;
    }

    private static String typeArg(ClassOrInterfaceType type, int index) {
        if (!type.getTypeArguments().isPresent()) {
            return null;
        }
        NodeList<Type> args = type.getTypeArguments().get();
        return args.size() > index ? args.get(index).asString() : null;
    }

    private static EntityMeta buildEntityMeta(TypeDeclaration<?> type, String simpleName) {
        Map<String, String> explicitColumns = new LinkedHashMap<String, String>();
        Set<String> excluded = new LinkedHashSet<String>();
        for (FieldDeclaration field : type.getFields()) {
            indexField(field, explicitColumns, excluded);
        }
        String tableName = tableNameOf(type, simpleName);
        return new EntityMeta(simpleName, tableName, explicitColumns, excluded);
    }

    /** {@code @TableName} 显式表名；无 → 简名转蛇形。 */
    private static String tableNameOf(TypeDeclaration<?> type, String simpleName) {
        String table = null;
        for (AnnotationExpr annotation : type.getAnnotations()) {
            if ("TableName".equals(annotation.getName().getIdentifier())) {
                table = annotationStringValue(annotation);
            }
        }
        return table != null ? table : TextUtils.camelToSnake(simpleName);
    }

    /** 字段入索引：{@code @TableField(exist=false)} → 排除列；显式列名 → 映射表。 */
    private static void indexField(FieldDeclaration field, Map<String, String> explicitColumns,
                                   Set<String> excluded) {
        String column = explicitColumnOf(field);
        boolean exist = existFlagOf(field);
        for (VariableDeclarator variable : field.getVariables()) {
            String property = variable.getNameAsString();
            if (!exist) {
                excluded.add(property);
            } else if (column != null) {
                explicitColumns.put(property, column);
            }
        }
    }

    /** {@code @TableField/@TableId} 的显式列名（后写覆盖先写，与注解顺序一致）；无 → null。 */
    private static String explicitColumnOf(FieldDeclaration field) {
        String column = null;
        for (AnnotationExpr annotation : field.getAnnotations()) {
            String id = annotation.getName().getIdentifier();
            if ("TableField".equals(id) || "TableId".equals(id)) {
                String value = annotationStringValue(annotation);
                if (value != null) {
                    column = value;
                }
            }
        }
        return column;
    }

    /** {@code @TableField(exist=...)} 标记；缺省 true。 */
    private static boolean existFlagOf(FieldDeclaration field) {
        boolean exist = true;
        for (AnnotationExpr annotation : field.getAnnotations()) {
            if ("TableField".equals(annotation.getName().getIdentifier())) {
                Optional<Boolean> existMember = annotationBooleanMember(annotation, "exist");
                if (existMember.isPresent()) {
                    exist = existMember.get();
                }
            }
        }
        return exist;
    }

    /** 注解 value 成员（单值或 normal 的 value=）的字符串字面量。 */
    static String annotationStringValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            Expression value = annotation.asSingleMemberAnnotationExpr().getMemberValue();
            return value.isStringLiteralExpr() ? value.asStringLiteralExpr().asString() : null;
        }
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                    return pair.getValue().asStringLiteralExpr().asString();
                }
            }
        }
        return null;
    }

    /** 注解成员中的布尔字面量（如 {@code @TableField(exist = false)}）；缺省/非布尔 → empty。 */
    static Optional<Boolean> annotationBooleanMember(AnnotationExpr annotation, String member) {
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (member.equals(pair.getNameAsString()) && pair.getValue().isBooleanLiteralExpr()) {
                    return Optional.of(pair.getValue().asBooleanLiteralExpr().getValue());
                }
            }
        }
        return Optional.empty();
    }

    /** 按 FQN 或简名解析实体。 */
    public Optional<EntityMeta> entity(String nameOrFqn) {
        if (nameOrFqn == null) {
            return Optional.empty();
        }
        EntityMeta direct = byName.get(nameOrFqn);
        if (direct != null) {
            return Optional.of(direct);
        }
        int dot = nameOrFqn.lastIndexOf('.');
        return Optional.ofNullable(byName.get(dot >= 0 ? nameOrFqn.substring(dot + 1) : nameOrFqn));
    }

    /** mapper/service 类型（FQN 或简名）→ 实体类型名（原文，可为简单名或全限定名）。 */
    public Optional<String> entityTypeOf(String typeName) {
        if (typeName == null) {
            return Optional.empty();
        }
        String direct = entityOfType.get(typeName);
        if (direct != null) {
            return Optional.of(direct);
        }
        int dot = typeName.lastIndexOf('.');
        return Optional.ofNullable(dot >= 0 ? entityOfType.get(typeName.substring(dot + 1)) : null);
    }
}
