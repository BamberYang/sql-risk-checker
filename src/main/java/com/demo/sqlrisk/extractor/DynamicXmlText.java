package com.demo.sqlrisk.extractor;

import java.util.Objects;

/**
 * 动态 SQL 载体（详细设计 §5.5.1 carrier）：mapper.xml 语句片段 / 注解 &lt;script&gt; 文本。
 * {@code ownerNamespace} 供裸 refid 的 include 解析（本 namespace 前缀）。
 */
public final class DynamicXmlText {

    private final String fragmentText;
    private final String ownerNamespace;

    public DynamicXmlText(String fragmentText, String ownerNamespace) {
        this.fragmentText = Objects.requireNonNull(fragmentText, "fragmentText");
        this.ownerNamespace = Objects.requireNonNull(ownerNamespace, "ownerNamespace");
    }

    public String fragmentText() {
        return fragmentText;
    }

    public String ownerNamespace() {
        return ownerNamespace;
    }
}
