package com.demo.sqlrisk.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 全仓 Java 源码索引（详细设计 §14）：按路径解析为 CompilationUnit，
 * 建立「全限定名 / 简名」两套索引，供常量解析、Provider 解析与实体映射复用（M5）。
 *
 * <p>解析失败的文件不中断构建，记入 {@link #failedFiles()} 由调用方决定降级策略；
 * 索引按路径字典序遍历构建，结果确定。</p>
 */
public final class JavaSourceIndex {

    private final Map<String, CompilationUnit> byQualifiedName;
    private final Map<String, CompilationUnit> bySimpleName;
    private final Map<String, CompilationUnit> byPath;
    private final List<String> failedFiles;

    private JavaSourceIndex(Map<String, CompilationUnit> byQualifiedName,
                            Map<String, CompilationUnit> bySimpleName,
                            Map<String, CompilationUnit> byPath,
                            List<String> failedFiles) {
        this.byQualifiedName = Collections.unmodifiableMap(byQualifiedName);
        this.bySimpleName = Collections.unmodifiableMap(bySimpleName);
        this.byPath = Collections.unmodifiableMap(byPath);
        this.failedFiles = Collections.unmodifiableList(failedFiles);
    }

    /**
     * @param pathToContent 仓库相对路径 → 源码文本（调用方保证已完成编码解码）
     */
    public static JavaSourceIndex build(Map<String, String> pathToContent) {
        Map<String, CompilationUnit> byFqn = new TreeMap<String, CompilationUnit>();
        Map<String, CompilationUnit> bySimple = new TreeMap<String, CompilationUnit>();
        Map<String, CompilationUnit> byPath = new TreeMap<String, CompilationUnit>();
        List<String> failed = new ArrayList<String>();

        List<String> paths = new ArrayList<String>(pathToContent.keySet());
        Collections.sort(paths);
        for (String path : paths) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(pathToContent.get(path));
                byPath.put(path, cu);
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                for (TypeDeclaration<?> type : cu.getTypes()) {
                    String simple = type.getNameAsString();
                    String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
                    byFqn.put(fqn, cu);
                    if (!bySimple.containsKey(simple)) { // 简名冲突取路径序首个（确定性）
                        bySimple.put(simple, cu);
                    }
                }
            } catch (RuntimeException e) { // ParseProblemException 等：不中断，记入失败清单
                failed.add(path);
            }
        }
        return new JavaSourceIndex(byFqn, bySimple, byPath, failed);
    }

    public Optional<CompilationUnit> byQualifiedName(String fqn) {
        return Optional.ofNullable(byQualifiedName.get(fqn));
    }

    public Optional<CompilationUnit> bySimpleName(String simpleName) {
        return Optional.ofNullable(bySimpleName.get(simpleName));
    }

    /** 按仓库相对路径查询（同文件复解析；M5 提取器复用）。 */
    public Optional<CompilationUnit> byPath(String path) {
        return Optional.ofNullable(byPath.get(path));
    }

    /** 全部已解析编译单元（按路径字典序、去重）。 */
    public java.util.Collection<CompilationUnit> allCompilationUnits() {
        return java.util.Collections.unmodifiableCollection(
                new java.util.LinkedHashSet<CompilationUnit>(byPath.values()));
    }

    /** 解析失败的文件路径（仓库相对路径，路径字典序）。 */
    public List<String> failedFiles() {
        return failedFiles;
    }
}
