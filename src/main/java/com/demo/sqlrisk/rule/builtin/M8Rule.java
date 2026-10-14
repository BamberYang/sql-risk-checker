package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.rule.Rule;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M8 — ${} 拼串（详细设计 §11.4，短路规则，priority -100 先行）：
 * 扫描语句**原文**（含被丢弃分支，D8/AC-11）；命中即定级 MEDIUM 并结束该语句，
 * 不再评估任何骨架与规则；解析失败的"含 ${} 短路候选"由本规则覆盖（§8.5 结构性保证）。
 *
 * <p>排除项（v1.4，§9.5-6）：根名解析到 Wrapper 家族参数的 {@code ${name…}}（如 MP 惯用法
 * {@code ${ew.customSqlSegment}}）由框架经参数生成，非字面量拼串——全部占位符均被覆盖时不命中；
 * 绑定失配（@Param 改名而 SQL 未同步）时根名不再解析，恢复命中。</p>
 */
public final class M8Rule implements Rule {

    private static final Pattern DOLLAR_PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)\\}");

    @Override
    public String id() {
        return "M8";
    }

    @Override
    public RiskLevel defaultLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public int priority() {
        return -100;
    }

    @Override
    public boolean shortCircuit() {
        return true;
    }

    @Override
    public List<Finding> check(RuleContext ctx) {
        String rawText = ctx.rawText();
        if (rawText == null) {
            return RuleSupport.none();
        }
        Matcher matcher = DOLLAR_PLACEHOLDER.matcher(rawText);
        while (matcher.find()) {
            if (!ctx.wrapperSqlParams().contains(rootOf(matcher.group(1)))) {
                return RuleSupport.single(RuleSupport.finding(id(), defaultLevel(),
                        "存在 ${} 拼串，归属中危，不再继续判断其他规则", ctx));
            }
        }
        return RuleSupport.none();
    }

    /** 占位符根名：内容首个 '.' 前的部分（`ew.customSqlSegment` → `ew`；空 → 不匹配任何参数）。 */
    static String rootOf(String placeholderContent) {
        String content = placeholderContent == null ? "" : placeholderContent.trim();
        int dot = content.indexOf('.');
        return dot >= 0 ? content.substring(0, dot) : content;
    }
}
