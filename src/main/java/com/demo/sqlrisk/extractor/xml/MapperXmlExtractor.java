package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.extractor.StatementSelection;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * mapper.xml 语句提取（详细设计 §9.2，选择驱动）：
 * 按 {@link StatementSelection} 的行范围从提交树内容切片原文（含全部动态标签，D20 整句提取），
 * carrier 为 {@link DynamicXmlText}，M6 交 DynamicEvaluator 保守求值。
 */
public final class MapperXmlExtractor {

    private MapperXmlExtractor() {
    }

    public static ExtractResult extract(List<StatementSelection> selections, Map<String, String> fileContents) {
        List<ExtractedStatement> statements = new ArrayList<ExtractedStatement>();
        List<SkippedItem> skipped = new ArrayList<SkippedItem>();
        for (StatementSelection selection : selections) {
            String content = fileContents.get(selection.file());
            if (content == null) {
                skipped.add(new SkippedItem(selection.statementKey(), selection.file(), selection.startLine(),
                        SkippedItem.Kind.READ_ERROR, "文件内容缺失: " + selection.file()));
                continue;
            }
            String rawText = sliceLines(content, selection.startLine(), selection.endLine());
            statements.add(new ExtractedStatement(selection.statementKey(), StatementSource.MAPPER_XML,
                    selection.changeType(), selection.file(), selection.startLine(), rawText,
                    new DynamicXmlText(rawText, selection.namespace()), selection.changedVia()));
        }
        return new ExtractResult(statements, skipped);
    }

    /** 切片 [startLine, endLine]（1 基闭区间，含首尾行）；实现见 {@link com.demo.sqlrisk.util.TextUtils#sliceLines}。 */
    public static String sliceLines(String content, int startLine, int endLine) {
        return com.demo.sqlrisk.util.TextUtils.sliceLines(content, startLine, endLine);
    }
}
