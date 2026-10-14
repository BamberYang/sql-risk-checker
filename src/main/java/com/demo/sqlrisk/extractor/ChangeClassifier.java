package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.diff.Hunk;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.util.LineRanges;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 语句级变更判定（详细设计 §8.1 判定表、决策 D20）：行级命中 → 整句提取。
 * XML 语句站点与 Java 方法/链式范围共用同一判定。
 */
public final class ChangeClassifier {

    private ChangeClassifier() {
    }

    /**
     * @return 语句命中的变更类型（ADDED/MODIFIED）；未变更 → empty
     */
    public static Optional<ChangeType> classify(FileChange change, int startLine, int endLine) {
        if (change.isAddedLike()) {
            return Optional.of(ChangeType.ADDED);
        }
        Set<Integer> added = addedLines(change);
        if (added.contains(startLine) && LineRanges.isAllWithin(startLine, endLine, added)) {
            return Optional.of(ChangeType.ADDED); // 整段新增
        }
        if (LineRanges.overlaps(startLine, endLine, added)) {
            return Optional.of(ChangeType.MODIFIED);
        }
        for (Hunk hunk : change.hunks()) {
            if (hunk.isPureDeletion()
                    && LineRanges.anchorWithin(startLine, endLine, hunk.anchorLineInNew())) {
                return Optional.of(ChangeType.MODIFIED); // 覆盖"一行删一行改"
            }
        }
        return Optional.empty();
    }

    /** 文件内全部新增行（片段变更判定复用）。 */
    public static Set<Integer> addedLines(FileChange change) {
        Set<Integer> out = new TreeSet<Integer>();
        for (Hunk hunk : change.hunks()) {
            out.addAll(hunk.addedLines());
        }
        return out;
    }
}
