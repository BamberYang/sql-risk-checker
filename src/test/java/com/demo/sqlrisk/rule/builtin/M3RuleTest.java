package com.demo.sqlrisk.rule.builtin;

import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.parser.DroppedBranch;
import com.demo.sqlrisk.parser.SqlSkeleton;
import com.demo.sqlrisk.rule.RuleContext;
import com.demo.sqlrisk.testutil.RuleTestSupport;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 正例/反例/边界（非字面量不判定；Lambda likeLeft 事实并入）。 */
class M3RuleTest {

    private final M3Rule rule = new M3Rule();

    @Test
    void leading_wildcard_literal_fires() {
        List<Finding> findings = rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE order_no LIKE '%x'"));
        assertEquals(1, findings.size());
        assertEquals(RiskLevel.MEDIUM, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("前导通配符"));
    }

    @Test
    void trailing_wildcard_and_placeholder_do_not_fire() {
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE order_no LIKE 'x%'")).isEmpty());
        assertTrue(rule.check(RuleTestSupport.ctx(
                "SELECT id FROM t_order WHERE order_no LIKE ?")).isEmpty(), "非字面量不判定");
    }

    @Test
    void lambda_like_left_fact_fires() {
        // Lambda likeLeft() 的确定性前导 %：由渲染器写入骨架事实（§10.3）
        SqlSkeleton skeleton = new SqlSkeleton("SELECT * FROM t_order WHERE order_no LIKE ?",
                Collections.<DroppedBranch>emptyList(), SqlSkeleton.TAG_BASE, false, false, true, false);
        StatementMeta meta = StatementMeta.from(new ExtractedStatement("OrderService.java:10",
                StatementSource.LAMBDA, ChangeType.FULL, "OrderService.java", 10, "raw", "carrier", null));
        RuleContext ctx = new RuleContext(
                RuleTestSupport.parsedSql("SELECT * FROM t_order WHERE order_no LIKE ?", null, skeleton),
                meta, null, null, "raw");
        assertEquals(1, rule.check(ctx).size());
    }
}
