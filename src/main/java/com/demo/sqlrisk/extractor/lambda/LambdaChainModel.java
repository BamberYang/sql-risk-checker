package com.demo.sqlrisk.extractor.lambda;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Lambda 链模型（详细设计 §9.7，carrier）：M6 渲染为 SQL 骨架。
 *
 * <p>语句范围 = [rootLine, rangeEndLine]：链式构造 ∪ wrapper 变量的执行调用行（DD-7）；
 * {@code incomplete=true}（wrapper 由方法参数传入等还原不完整）→ 整句 uncertain（D11）。</p>
 */
public final class LambdaChainModel {

    private final String file;
    private final int rootLine;
    private final int rangeEndLine;
    private final String entityTypeName; // nullable：解析失败 → 表名占位（D13）
    private final List<LambdaCall> calls;
    private final boolean incomplete;
    private final boolean updateWrapper;

    public LambdaChainModel(String file, int rootLine, int rangeEndLine, String entityTypeName,
                            List<LambdaCall> calls, boolean incomplete, boolean updateWrapper) {
        this.file = Objects.requireNonNull(file, "file");
        this.rootLine = rootLine;
        this.rangeEndLine = rangeEndLine;
        this.entityTypeName = entityTypeName;
        this.calls = Collections.unmodifiableList(new ArrayList<LambdaCall>(calls));
        this.incomplete = incomplete;
        this.updateWrapper = updateWrapper;
    }

    public String file() {
        return file;
    }

    public int rootLine() {
        return rootLine;
    }

    public int rangeEndLine() {
        return rangeEndLine;
    }

    /** 可为 null。 */
    public String entityTypeName() {
        return entityTypeName;
    }

    public List<LambdaCall> calls() {
        return calls;
    }

    public boolean incomplete() {
        return incomplete;
    }

    /** true = LambdaUpdateWrapper/lambdaUpdate（渲染为 UPDATE）；false = 查询链。 */
    public boolean updateWrapper() {
        return updateWrapper;
    }
}
