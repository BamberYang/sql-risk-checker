package com.demo.sqlrisk.core;

import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.Summary;

import java.util.List;

/**
 * 汇总计算（详细设计 §13.4 口径）：
 * total = 参与分析语句数；high/medium/low 按语句最高命中级别互斥归类；skip = SKIP 类
 * （解析失败 + 无法提取 + 读取失败，不影响退出码）；ignored = 白名单命中。
 * 恒等式（单测断言）：提取总数 = total + skip + ignored。
 */
public final class SummaryCalculator {

    private SummaryCalculator() {
    }

    public static Summary calc(List<StatementResult> results, List<IgnoredItem> ignored,
                               List<SkippedItem> skipped) {
        int high = 0;
        int medium = 0;
        int low = 0;
        for (StatementResult result : results) {
            if (result.level() == RiskLevel.HIGH) {
                high++;
            } else if (result.level() == RiskLevel.MEDIUM) {
                medium++;
            } else if (result.level() == RiskLevel.LOW) {
                low++;
            }
        }
        return new Summary(results.size(), high, medium, low, skipped.size(), ignored.size());
    }
}
