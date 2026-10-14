package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.extractor.xml.XmlFragmentIndex;
import com.demo.sqlrisk.util.JavaSourceIndex;

/**
 * 提取上下文（详细设计 §9.1）：由 P3 编排构建，各字段按需使用、可为 null。
 */
public final class ExtractContext {

    private final XmlFragmentIndex fragmentIndex; // nullable：XML 片段索引（Java 提取不使用）
    private final EntityIndex entityIndex;        // nullable：实体→表/列映射（Lambda 提取使用）
    private final JavaSourceIndex sourceIndex;    // nullable：源码索引（缺省时按内容即时解析）
    private final AppConfig config;               // nullable：配置（预留）

    public ExtractContext(XmlFragmentIndex fragmentIndex, EntityIndex entityIndex,
                          JavaSourceIndex sourceIndex, AppConfig config) {
        this.fragmentIndex = fragmentIndex;
        this.entityIndex = entityIndex;
        this.sourceIndex = sourceIndex;
        this.config = config;
    }

    /** 可为 null。 */
    public XmlFragmentIndex fragmentIndex() {
        return fragmentIndex;
    }

    /** 可为 null。 */
    public EntityIndex entityIndex() {
        return entityIndex;
    }

    /** 可为 null。 */
    public JavaSourceIndex sourceIndex() {
        return sourceIndex;
    }

    /** 可为 null。 */
    public AppConfig config() {
        return config;
    }
}
