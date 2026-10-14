package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.parser.DroppedBranch;
import com.demo.sqlrisk.parser.QueryFacts;
import com.demo.sqlrisk.rule.RuleContext;
import com.demo.sqlrisk.util.TextUtils;

import java.util.Collections;
import java.util.List;

/** 内置规则共用工具（§11.4 通用约定：snippet 截断、保守折叠提示附注）。 */
final class RuleSupport {

    private static final int SNIPPET_LIMIT = 120; // §17

    private RuleSupport() {
    }

    static String snippet(RuleContext ctx) {
        String sql = ctx.parsedSql() != null ? ctx.parsedSql().skeleton().text()
                : TextUtils.collapseWhitespace(ctx.rawText());
        return TextUtils.truncate(TextUtils.collapseWhitespace(sql), SNIPPET_LIMIT);
    }

    /**
     * H1/H2/H3 的保守折叠提示附注（决策 D19）：
     * WHERE 缺失因动态分支被折叠（role=CONDITION）→ 附注说明；否则若骨架不确定（还原不完整等）→ 人工复核提示。
     */
    static String conservativeNote(RuleContext ctx) {
        QueryFacts facts = ctx.facts();
        if (facts == null) {
            return "";
        }
        for (DroppedBranch branch : facts.droppedBranches()) {
            if (branch.role() == DroppedBranch.Role.CONDITION) {
                return "（WHERE 条件位于动态分支内，按保守策略视为不成立）";
            }
        }
        if (facts.uncertain()) {
            return "（语句还原不完全，条件可能在其他位置构造，需人工复核）";
        }
        return "";
    }

    static Finding finding(String ruleId, RiskLevel level, String message, RuleContext ctx) {
        return new Finding(ruleId, level, message, snippet(ctx), 0);
    }

    static List<Finding> none() {
        return Collections.emptyList();
    }

    static List<Finding> single(Finding finding) {
        return Collections.singletonList(finding);
    }
}
