package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.diff.ChangeSet;
import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.extractor.annotation.AnnotationSqlExtractor;
import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.extractor.lambda.LambdaSqlExtractor;
import com.demo.sqlrisk.extractor.xml.FragmentPropagator;
import com.demo.sqlrisk.extractor.xml.MapperXmlExtractor;
import com.demo.sqlrisk.extractor.xml.XmlFragmentIndex;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.util.JavaSourceIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P3 提取编排（详细设计 §9.1，core 内调度逻辑的独立封装）：
 *
 * <ol>
 *   <li>索引构建：{@link XmlFragmentIndex}（全仓 xml）、{@link JavaSourceIndex}、{@link EntityIndex}；</li>
 *   <li>生效语句选择：DIFF = 直接变更 ∪ 片段派生（{@link FragmentPropagator}）；FULL = 全量；</li>
 *   <li>提取：XML（选择驱动）+ 注解 + Lambda；</li>
 *   <li>statementId 去重（保留首个 + 告警）→ 按 (file, line, statementId) 排序（§16 确定性）。</li>
 * </ol>
 */
public final class ExtractionService {

    private ExtractionService() {
    }

    /**
     * @param mode     运行模式
     * @param changeSet DIFF 模式的差异（FULL 模式为 null）
     * @param xmlFiles  提交树内全部 xml 候选（索引与提取来源）
     * @param javaFiles 提交树内全部 java 候选
     */
    /** 兼容入口：等价 {@code run(...).result()}。 */
    public static ExtractResult extract(Mode mode, ChangeSet changeSet,
                                        List<CandidateFile> xmlFiles, List<CandidateFile> javaFiles,
                                        AppConfig config) {
        return run(mode, changeSet, xmlFiles, javaFiles, config).result();
    }

    /** 提取编排并返回解析所需上下文（片段索引/XML 内容/实体索引，M6 起 P5/P6 复用）。 */
    public static ExtractionOutput run(Mode mode, ChangeSet changeSet,
                                       List<CandidateFile> xmlFiles, List<CandidateFile> javaFiles,
                                       AppConfig config) {
        XmlFragmentIndex xmlIndex = XmlFragmentIndex.build(xmlFiles);
        Map<String, String> javaContents = contents(javaFiles);
        JavaSourceIndex sourceIndex = JavaSourceIndex.build(javaContents);
        EntityIndex entityIndex = EntityIndex.build(sourceIndex);
        ExtractContext ctx = new ExtractContext(xmlIndex, entityIndex, sourceIndex, config);

        List<ExtractedStatement> statements = new ArrayList<ExtractedStatement>();
        List<SkippedItem> skipped = new ArrayList<SkippedItem>();

        // 索引期解析失败的 XML 文件 → SKIP（不静默）
        for (String failedFile : xmlIndex.parseFailedFiles()) {
            skipped.add(new SkippedItem(null, failedFile, 0,
                    SkippedItem.Kind.PARSE_FAILED, "解析失败：XML 结构错误"));
        }

        // XML 语句（选择驱动）
        List<StatementSelection> selections = mode == Mode.FULL
                ? FragmentPropagator.allStatements(xmlIndex)
                : FragmentPropagator.compute(changeSet, xmlIndex);
        ExtractResult xmlResult = MapperXmlExtractor.extract(selections, contents(xmlFiles));
        statements.addAll(xmlResult.statements());
        skipped.addAll(xmlResult.skipped());

        // Java 语句（注解 + Lambda；DIFF 模式仅变更文件，Java 无片段传播）
        AnnotationSqlExtractor annotationExtractor = new AnnotationSqlExtractor();
        LambdaSqlExtractor lambdaExtractor = new LambdaSqlExtractor();
        for (CandidateFile file : javaFiles) {
            CandidateFile target = file;
            if (mode == Mode.DIFF) {
                Optional<FileChange> change = changeSet.file(file.path());
                if (!change.isPresent()) {
                    continue;
                }
                target = CandidateFile.of(file.path(), file.content(), change.get().type(), change.get());
            }
            if (annotationExtractor.supports(target)) {
                ExtractResult result = annotationExtractor.extract(target, ctx);
                statements.addAll(result.statements());
                skipped.addAll(result.skipped());
            }
            if (lambdaExtractor.supports(target)) {
                ExtractResult result = lambdaExtractor.extract(target, ctx);
                statements.addAll(result.statements());
                skipped.addAll(result.skipped());
            }
        }

        // 去重（statementId 保留首个）+ 确定性排序
        Map<String, ExtractedStatement> byId = new LinkedHashMap<String, ExtractedStatement>();
        for (ExtractedStatement statement : statements) {
            if (!byId.containsKey(statement.statementId())) {
                byId.put(statement.statementId(), statement);
            } else {
                System.err.println("[警告] 重复 statementId，保留首个: " + statement.statementId());
            }
        }
        List<ExtractedStatement> ordered = new ArrayList<ExtractedStatement>(byId.values());
        Collections.sort(ordered, Comparator.comparing(ExtractedStatement::file)
                .thenComparingInt(ExtractedStatement::line)
                .thenComparing(ExtractedStatement::statementId));
        return new ExtractionOutput(new ExtractResult(ordered, skipped),
                xmlIndex, contents(xmlFiles), entityIndex);
    }

    private static Map<String, String> contents(List<CandidateFile> files) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (CandidateFile file : files) {
            map.put(file.path(), file.content());
        }
        return map;
    }
}
