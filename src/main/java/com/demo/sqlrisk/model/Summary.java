package com.demo.sqlrisk.model;

/**
 * 报告汇总（详细设计 §13.4 口径）：
 * <ul>
 *   <li>total = 参与分析的语句数（results.size）；</li>
 *   <li>high/medium/low 按语句最高命中级别互斥归类，三者之和 ≤ total；</li>
 *   <li>skip = SKIP 类语句数（解析失败 + 无法提取 + 读取失败），不影响退出码；</li>
 *   <li>ignored = 白名单命中数；提取总数 = total + skip + ignored。</li>
 * </ul>
 */
public final class Summary {

    private final int total;
    private final int high;
    private final int medium;
    private final int low;
    private final int skip;
    private final int ignored;

    public Summary(int total, int high, int medium, int low, int skip, int ignored) {
        this.total = total;
        this.high = high;
        this.medium = medium;
        this.low = low;
        this.skip = skip;
        this.ignored = ignored;
    }

    public int total() {
        return total;
    }

    public int high() {
        return high;
    }

    public int medium() {
        return medium;
    }

    public int low() {
        return low;
    }

    public int skip() {
        return skip;
    }

    public int ignored() {
        return ignored;
    }
}
