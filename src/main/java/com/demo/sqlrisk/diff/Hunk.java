package com.demo.sqlrisk.diff;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 差异 hunk（详细设计 §8.3，行号 1 基）：
 * {@code addedLines} 为新文件新增行号、{@code deletedLines} 为旧文件删除行号；
 * {@code anchorLineInNew}（= newStart）为纯删除 hunk 在新文件中的落点，
 * 供 §8.1 语句映射覆盖"一行删一行改 → MODIFIED"。
 */
public final class Hunk {

    private final int oldStart;
    private final int oldCount;
    private final int newStart;
    private final int newCount;
    private final Set<Integer> addedLines;
    private final Set<Integer> deletedLines;

    public Hunk(int oldStart, int oldCount, int newStart, int newCount,
                Set<Integer> addedLines, Set<Integer> deletedLines) {
        this.oldStart = oldStart;
        this.oldCount = oldCount;
        this.newStart = newStart;
        this.newCount = newCount;
        this.addedLines = Collections.unmodifiableSet(new TreeSet<Integer>(addedLines));
        this.deletedLines = Collections.unmodifiableSet(new TreeSet<Integer>(deletedLines));
    }

    /** 旧文件起始行（1 基）。 */
    public int oldStart() {
        return oldStart;
    }

    public int oldCount() {
        return oldCount;
    }

    /** 新文件起始行（1 基）。 */
    public int newStart() {
        return newStart;
    }

    public int newCount() {
        return newCount;
    }

    public Set<Integer> addedLines() {
        return addedLines;
    }

    public Set<Integer> deletedLines() {
        return deletedLines;
    }

    /** 纯删除 hunk 在新文件中的锚点落点（= newStart）。 */
    public int anchorLineInNew() {
        return newStart;
    }

    public boolean isPureDeletion() {
        return addedLines.isEmpty() && !deletedLines.isEmpty();
    }
}
