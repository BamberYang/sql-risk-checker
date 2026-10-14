package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6 正例/反例/边界（空分支视为不满足；元数据缺失保守命中）。 */
class M6RuleTest {

    private final M6Rule rule = new M6Rule();

    @Test
    void unindexed_or_branch_fires() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE status = 1 OR order_no = 'x'",
                RuleTestSupport.orderMetadata(), null));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("OR 连接"));
    }

    @Test
    void all_indexed_branches_do_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = 1 OR id = 2",
                RuleTestSupport.orderMetadata(), null)).isEmpty());
    }

    @Test
    void no_or_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE status = 1", RuleTestSupport.orderMetadata(), null)).isEmpty());
    }

    @Test
    void literal_only_branch_counts_as_not_satisfied() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE 1 = 1 OR user_id = 1",
                RuleTestSupport.orderMetadata(), null)).size() == 1, "无列分支（1=1）→ 命中");
    }

    @Test
    void missing_metadata_fires_conservatively() {
        assertEquals(1, rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = 1 OR id = 2")).size(),
                "无索引信息可判定 → 视为未命中（宁可误报）");
    }
}
