package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.extractor.xml.XmlFragmentIndex;

import java.util.Map;
import java.util.Objects;

/**
 * 提取编排产物 + 解析所需上下文（M6 起 P5/P6 复用索引，避免二次全仓扫描）：
 * 片段索引与 XML 文件内容供 include 展开；实体索引供 Lambda 渲染。
 */
public final class ExtractionOutput {

    private final ExtractResult result;
    private final XmlFragmentIndex xmlIndex;
    private final Map<String, String> xmlContents;
    private final EntityIndex entityIndex;

    public ExtractionOutput(ExtractResult result, XmlFragmentIndex xmlIndex,
                            Map<String, String> xmlContents, EntityIndex entityIndex) {
        this.result = Objects.requireNonNull(result, "result");
        this.xmlIndex = Objects.requireNonNull(xmlIndex, "xmlIndex");
        this.xmlContents = Objects.requireNonNull(xmlContents, "xmlContents");
        this.entityIndex = Objects.requireNonNull(entityIndex, "entityIndex");
    }

    public ExtractResult result() {
        return result;
    }

    public XmlFragmentIndex xmlIndex() {
        return xmlIndex;
    }

    /** path → XML 文件内容（提交树）。 */
    public Map<String, String> xmlContents() {
        return xmlContents;
    }

    public EntityIndex entityIndex() {
        return entityIndex;
    }
}
