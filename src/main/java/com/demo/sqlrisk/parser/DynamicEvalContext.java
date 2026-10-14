package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.xml.XmlFragmentIndex;

import java.util.Map;

/**
 * 动态求值上下文（详细设计 §10.2）：include 展开所需的片段索引与提交树文件内容。
 * 两者缺省（null）时，遇到 include 即整体求值失败（语句 → SKIP）。
 */
public final class DynamicEvalContext {

    private final XmlFragmentIndex fragmentIndex;      // nullable
    private final Map<String, String> fileContents;    // nullable：path → 内容

    public DynamicEvalContext(XmlFragmentIndex fragmentIndex, Map<String, String> fileContents) {
        this.fragmentIndex = fragmentIndex;
        this.fileContents = fileContents;
    }

    /** 可为 null。 */
    public XmlFragmentIndex fragmentIndex() {
        return fragmentIndex;
    }

    /** 可为 null。 */
    public Map<String, String> fileContents() {
        return fileContents;
    }
}
