package com.demo.sqlrisk.util;

import java.util.Set;

/**
 * 行号集合运算（详细设计 §14 / §8.1）。所有行号均为 1 基。
 * 行集合以 {@code Set<Integer>} 表达（规模小，boxing 可接受）。
 */
public final class LineRanges {

    private LineRanges() {
    }

    /** [start,end] 与 lines 是否有交集。 */
    public static boolean overlaps(int start, int end, Set<Integer> lines) {
        for (Integer line : lines) {
            if (line != null && line >= start && line <= end) {
                return true;
            }
        }
        return false;
    }

    /** [start,end] 是否每一行都在 lines 中（空/非法区间返回 false，防止误判为"整段新增"）。 */
    public static boolean isAllWithin(int start, int end, Set<Integer> lines) {
        if (end < start) {
            return false;
        }
        for (int i = start; i <= end; i++) {
            if (!lines.contains(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 纯删除 hunk 的锚点是否落在 [start, end+1]（§8.1 判定表，
     * 覆盖"同一语句一行删一行改 → MODIFIED"）。
     */
    public static boolean anchorWithin(int start, int end, int anchorLine) {
        return anchorLine >= start && anchorLine <= end + 1;
    }
}
