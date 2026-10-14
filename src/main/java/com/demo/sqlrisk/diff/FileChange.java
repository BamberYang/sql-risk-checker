package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.model.DiffType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单文件变更（详细设计 §8.3）：仅产出候选文件（*.java / *.xml）；
 * DELETE 不产出（删除类不分析）；RENAME 视同新增侧、hunks 为空（决策 D15）。
 */
public final class FileChange {

    private final String path;      // 新文件路径（RENAME 时为新路径）
    private final DiffType type;    // ADD / MODIFY / RENAME
    private final List<Hunk> hunks; // RENAME 为空：整文件按新增处理

    public FileChange(String path, DiffType type, List<Hunk> hunks) {
        this.path = Objects.requireNonNull(path, "path");
        this.type = Objects.requireNonNull(type, "type");
        this.hunks = Collections.unmodifiableList(new ArrayList<Hunk>(Objects.requireNonNull(hunks, "hunks")));
    }

    public String path() {
        return path;
    }

    public DiffType type() {
        return type;
    }

    public List<Hunk> hunks() {
        return hunks;
    }

    /** 与 ADD 等价处理（整文件视为新增语句）。 */
    public boolean isAddedLike() {
        return type == DiffType.ADD || type == DiffType.RENAME;
    }
}
