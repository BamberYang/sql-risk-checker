package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.parser.ParsedSql;
import com.demo.sqlrisk.parser.ParsedStatement;
import com.demo.sqlrisk.util.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则引擎（详细设计 §11.2、FR-4、AC-4）：
 *
 * <ol>
 *   <li><b>① 语句级短路</b>（M8 等，priority 负值先行）：扫描语句原文，命中即定级并结束该语句，
 *       不再评估任何骨架与规则——含 ${} 的"空骨架短路候选"亦由此覆盖（§8.5 结构性保证）；</li>
 *   <li><b>② 骨架循环</b>：按生成顺序逐骨架评估；任一骨架命中 ≥1 条 → 记录并停止，不再评估后续骨架（AC-4）；</li>
 *   <li><b>③ 归类</b>：语句级别 = 命中规则最高级别（配置覆盖后的生效级别）；无命中 → level=null；</li>
 *   <li>防御式：单规则异常 → stderr 警告 + 跳过该规则，不中断整体。</li>
 * </ol>
 */
public final class RuleEngine {

    private RuleEngine() {
    }

    public static List<StatementResult> run(List<ParsedStatement> statements, RuleRegistry registry,
                                            MetadataProvider metadata, AppConfig config) {
        List<Rule> rules = registry.enabledRules();
        List<StatementResult> results = new ArrayList<StatementResult>(statements.size());
        for (ParsedStatement statement : statements) {
            results.add(analyze(statement, rules, registry, metadata, config));
        }
        return results;
    }

    private static StatementResult analyze(ParsedStatement statement, List<Rule> rules,
                                           RuleRegistry registry, MetadataProvider metadata,
                                           AppConfig config) {
        StatementMeta meta = StatementMeta.from(statement.source());
        String rawText = statement.source().rawText();

        // ① 语句级短路（仅依赖语句原文）
        RuleContext statementCtx = new RuleContext(null, meta, metadata, config, rawText);
        for (Rule rule : rules) {
            if (!rule.shortCircuit()) {
                continue;
            }
            List<Finding> findings = safeCheck(rule, statementCtx, registry);
            if (!findings.isEmpty()) {
                return new StatementResult(meta, TextUtils.collapseWhitespace(rawText),
                        findings, RiskLevel.max(levelsOf(findings)).orElse(null), statement.isUncertain());
            }
        }

        // ② 骨架循环：任一骨架命中即停止（AC-4）
        List<ParsedSql> skeletons = statement.parsed();
        for (int i = 0; i < skeletons.size(); i++) {
            ParsedSql parsedSql = skeletons.get(i);
            RuleContext ctx = new RuleContext(parsedSql, meta, metadata, config, rawText);
            List<Finding> findings = new ArrayList<Finding>();
            for (Rule rule : rules) {
                if (rule.shortCircuit()) {
                    continue; // 已在上一步处理
                }
                findings.addAll(safeCheck(rule, ctx, registry));
            }
            if (!findings.isEmpty()) {
                return new StatementResult(meta, parsedSql.skeleton().text(), withSkeletonIndex(findings, i),
                        RiskLevel.max(levelsOf(findings)).orElse(null), statement.isUncertain());
            }
        }

        // ③ 无命中：sql 取骨架 #0（空骨架短路候选 → 语句原文）
        String sql = skeletons.isEmpty() ? TextUtils.collapseWhitespace(rawText)
                : skeletons.get(0).skeleton().text();
        return new StatementResult(meta, sql, Collections.<Finding>emptyList(), null, statement.isUncertain());
    }

    /** 防御式执行 + 生效级别覆盖（§11.3）。 */
    private static List<Finding> safeCheck(Rule rule, RuleContext ctx, RuleRegistry registry) {
        try {
            List<Finding> findings = rule.check(ctx);
            if (findings == null || findings.isEmpty()) {
                return Collections.emptyList();
            }
            RiskLevel effectiveLevel = registry.effectiveLevel(rule);
            List<Finding> adjusted = new ArrayList<Finding>(findings.size());
            for (Finding finding : findings) {
                adjusted.add(finding.level() == effectiveLevel ? finding
                        : new Finding(finding.ruleId(), effectiveLevel, finding.message(),
                        finding.snippet(), finding.skeletonIndex()));
            }
            return adjusted;
        } catch (RuntimeException e) {
            System.err.println("[警告] 规则执行异常，跳过: " + rule.id()
                    + "（" + ctx.meta().statementId() + "）：" + e);
            return Collections.emptyList();
        }
    }

    private static List<Finding> withSkeletonIndex(List<Finding> findings, int index) {
        List<Finding> out = new ArrayList<Finding>(findings.size());
        for (Finding finding : findings) {
            out.add(new Finding(finding.ruleId(), finding.level(), finding.message(),
                    finding.snippet(), index));
        }
        return out;
    }

    private static List<RiskLevel> levelsOf(List<Finding> findings) {
        List<RiskLevel> levels = new ArrayList<RiskLevel>(findings.size());
        for (Finding finding : findings) {
            levels.add(finding.level());
        }
        return levels;
    }
}
