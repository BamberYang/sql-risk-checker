package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1 正例/反例/边界（元数据缺失语义 D13、无谓词列不判定）。 */
class M1RuleTest {

    private final M1Rule rule = new M1Rule();

    @Test
    void unindexed_column_fires_medium() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE status = 1", RuleTestSupport.orderMetadata(), null));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("t_order(status)"), findings.get(0).message());
    }

    @Test
    void indexed_column_does_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE user_id = 1", RuleTestSupport.orderMetadata(), null)).isEmpty());
    }

    @Test
    void table_not_in_metadata_counts_as_unindexed() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_other WHERE x = 1", RuleTestSupport.orderMetadata(), null));
        assertTrue(findings.get(0).message().contains("不在元数据中"), findings.get(0).message());
    }

    @Test
    void missing_metadata_provider_fires_conservatively() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx("SELECT id FROM t_order WHERE user_id = 1"));
        assertEquals(1, findings.size(), "元数据缺失 → 默认无索引（宁可误报）");
    }

    @Test
    void no_predicate_columns_means_not_applicable() {
        assertTrue(rule.check(RuleTestSupport.ctx("SELECT * FROM t_order", RuleTestSupport.orderMetadata(), null))
                .isEmpty(), "无过滤职责的语句由 H 组覆盖，M1 不判定");
    }
}
