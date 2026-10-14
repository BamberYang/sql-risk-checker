package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.lambda.LambdaChainModel;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语句解析编排（详细设计 §10.6，P5/P6）：载体分派 → 骨架生成 → 占位符归一化 → Druid 解析 → 事实提取。
 *
 * <p>任一步失败（include 展开失败 / 骨架解析失败）→ 语句整体计入 SKIP(PARSE_FAILED)，
 * reason 前缀"解析失败："，不中断整体分析（FR-3/§10）。</p>
 */
public final class StatementParser {

    private StatementParser() {
    }

    public static ParseOutcome parse(ExtractedStatement statement, ParseContext ctx) {
        try {
            List<SqlSkeleton> skeletons = skeletonsOf(statement, ctx);
            List<ParsedSql> parsed = new ArrayList<ParsedSql>(skeletons.size());
            for (SqlSkeleton skeleton : skeletons) {
                NormalizedSql normalized = PlaceholderNormalizer.normalize(skeleton.text());
                com.alibaba.druid.sql.ast.SQLStatement ast = DruidSqlParser.parse(normalized.text());
                QueryFacts facts = QueryFactsBuilder.build(ast, ctx == null ? null : ctx.metadata(), skeleton);
                parsed.add(new ParsedSql(skeleton, normalized, ast, facts));
            }
            return ParseOutcome.ok(new ParsedStatement(statement, parsed));
        } catch (DynamicEvaluationException e) {
            return handleFailure(statement, e.getMessage());
        } catch (SqlParseException e) {
            return handleFailure(statement, e.getMessage());
        } catch (RuntimeException e) {
            return handleFailure(statement, "解析失败：" + e);
        }
    }

    /** ${} 存在性判定（D8：语句原文，含被丢弃分支）。 */
    private static final Pattern DOLLAR_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*\\}");

    /**
     * 解析失败处理（§8.5/决策 D8 的结构性保证）：
     * 语句原文含 {@code ${...}} 时**不因解析失败而丢失 M8 短路判定**——
     * 以空骨架的 ParsedStatement 保留，交由规则引擎按 M8 语句级短路定级（根名未被 Wrapper 参数
     * 覆盖的占位符，§9.5-6；已覆盖并代换成功者不落此路径）；否则按 SKIP(PARSE_FAILED)。
     */
    private static ParseOutcome handleFailure(ExtractedStatement statement, String reason) {
        String rawText = statement.rawText();
        if (rawText != null && DOLLAR_PLACEHOLDER.matcher(rawText).find()) {
            return ParseOutcome.ok(new ParsedStatement(statement, Collections.<ParsedSql>emptyList()));
        }
        return ParseOutcome.skip(skip(statement, reason));
    }

    private static List<SqlSkeleton> skeletonsOf(ExtractedStatement statement, ParseContext ctx) {
        Object carrier = statement.carrier();
        if (carrier instanceof DynamicXmlText) {
            DynamicEvalContext dynamicContext = ctx == null ? null : ctx.dynamicContext();
            if (dynamicContext == null) {
                dynamicContext = new DynamicEvalContext(null, null);
            }
            return DynamicEvaluator.evaluate((DynamicXmlText) carrier, dynamicContext);
        }
        if (carrier instanceof AnnotationSqlText) {
            String sql = ((AnnotationSqlText) carrier).sqlText();
            if (statement.hasWrapperSqlParams()) {
                // Wrapper SQL 段代换（§10.6，v1.4）：条件来自运行期 Wrapper → 代换为中性谓词并可解析，
                // 骨架标记未知片段（报告 uncertain=true）
                WrapperSqlSubstitutor.Result result =
                        WrapperSqlSubstitutor.substitute(sql, statement.wrapperSqlParams());
                if (result.applied()) {
                    return Collections.singletonList(new SqlSkeleton(result.text(),
                            Collections.<DroppedBranch>emptyList(), SqlSkeleton.TAG_BASE,
                            true, false, false, false));
                }
            }
            return Collections.singletonList(SqlSkeleton.base(sql));
        }
        if (carrier instanceof LambdaChainModel) {
            // 控制流分支枚举（§10.3-补充，M15）：多骨架交由规则引擎"首命中即停"消费（AC-4）
            return LambdaSkeletonRenderer.renderAll(
                    (LambdaChainModel) carrier, ctx == null ? null : ctx.entityIndex());
        }
        throw new DynamicEvaluationException("未知载体类型: " + carrier.getClass().getName());
    }

    private static SkippedItem skip(ExtractedStatement statement, String reason) {
        String message = reason.startsWith("解析失败") ? reason : "解析失败：" + reason;
        return new SkippedItem(statement.statementId(), statement.file(), statement.line(),
                SkippedItem.Kind.PARSE_FAILED, message);
    }
}
