package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.metadata.MetadataProvider;

/**
 * 解析上下文（详细设计 §10.6，由 P5/P6 编排装配）：
 * XML 求值（片段索引/文件内容）、Lambda 渲染（实体索引）、事实提取（元数据）。
 */
public final class ParseContext {

    private final DynamicEvalContext dynamicContext; // nullable：仅 XML 载体需要
    private final EntityIndex entityIndex;           // nullable：仅 Lambda 载体需要
    private final MetadataProvider metadata;         // nullable：缺失时 M4 相关事实退化

    public ParseContext(DynamicEvalContext dynamicContext, EntityIndex entityIndex,
                        MetadataProvider metadata) {
        this.dynamicContext = dynamicContext;
        this.entityIndex = entityIndex;
        this.metadata = metadata;
    }

    /** 可为 null。 */
    public DynamicEvalContext dynamicContext() {
        return dynamicContext;
    }

    /** 可为 null。 */
    public EntityIndex entityIndex() {
        return entityIndex;
    }

    /** 可为 null。 */
    public MetadataProvider metadata() {
        return metadata;
    }
}
