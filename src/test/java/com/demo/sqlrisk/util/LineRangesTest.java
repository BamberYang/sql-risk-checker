package com.demo.sqlrisk.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1.3 自测：行集合运算（§8.1 判定表的底层原语）。 */
class LineRangesTest {

    private static Set<Integer> lines(Integer... values) {
        Set<Integer> set = new TreeSet<Integer>();
        Collections.addAll(set, values);
        return set;
    }

    @Test
    void overlaps_normal_and_boundary() {
        Set<Integer> changed = lines(10, 15);
        assertTrue(LineRanges.overlaps(8, 12, changed), "区间覆盖变更行");
        assertTrue(LineRanges.overlaps(10, 10, changed), "恰好命中首行");
        assertTrue(LineRanges.overlaps(15, 20, changed), "恰好命中末行");
        assertFalse(LineRanges.overlaps(11, 14, changed), "变更行在区间之外");
        assertFalse(LineRanges.overlaps(1, 5, changed));
        assertFalse(LineRanges.overlaps(1, 5, Collections.<Integer>emptySet()));
    }

    @Test
    void is_all_within_strictness() {
        Set<Integer> added = lines(3, 4, 5);
        assertTrue(LineRanges.isAllWithin(3, 5, added));
        assertTrue(LineRanges.isAllWithin(4, 4, added));
        assertFalse(LineRanges.isAllWithin(3, 6, added), "区间有一行不在集合内即 false");
        assertFalse(LineRanges.isAllWithin(2, 5, added));
        assertFalse(LineRanges.isAllWithin(5, 3, added), "非法区间（end<start）返回 false");
    }

    @Test
    void anchor_within_covers_end_plus_one() {
        // 纯删除 hunk 锚点：允许落在语句范围内部或紧邻位置（[start, end+1]）
        assertTrue(LineRanges.anchorWithin(40, 50, 40));
        assertTrue(LineRanges.anchorWithin(40, 50, 50));
        assertTrue(LineRanges.anchorWithin(40, 50, 51), "end+1 是闭区间上界");
        assertFalse(LineRanges.anchorWithin(40, 50, 52));
        assertFalse(LineRanges.anchorWithin(40, 50, 39));
    }
}
