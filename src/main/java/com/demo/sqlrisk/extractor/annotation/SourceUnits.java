package com.demo.sqlrisk.extractor.annotation;

import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.ExtractContext;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.util.Optional;

/**
 * Java 源码获取（详细设计 §9.5 实现细化）：优先复用 {@code JavaSourceIndex.byPath}（避免重复解析），
 * 缺省按内容即时解析；解析失败返回 null（调用方按 SKIP 处理）。
 */
final class SourceUnits {

    private SourceUnits() {
    }

    static CompilationUnit compilationUnit(CandidateFile file, ExtractContext ctx) {
        if (ctx != null && ctx.sourceIndex() != null) {
            Optional<CompilationUnit> cached = ctx.sourceIndex().byPath(file.path());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            return StaticJavaParser.parse(file.content());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
