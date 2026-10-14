package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.model.DiffType;

import java.util.Objects;

/**
 * 候选承载文件（详细设计 §9.1）：路径 + 内容（来自提交树）+ 差异信息。
 * FULL 模式无差异信息（diffType/change 为 null）。
 */
public final class CandidateFile {

    private final String path;
    private final String content;
    private final DiffType diffType;  // nullable
    private final FileChange change;  // nullable

    private CandidateFile(String path, String content, DiffType diffType, FileChange change) {
        this.path = Objects.requireNonNull(path, "path");
        this.content = Objects.requireNonNull(content, "content");
        this.diffType = diffType;
        this.change = change;
    }

    /** FULL 模式 / 仅索引用途。 */
    public static CandidateFile of(String path, String content) {
        return new CandidateFile(path, content, null, null);
    }

    /** DIFF 模式：携带该文件的差异信息。 */
    public static CandidateFile of(String path, String content, DiffType diffType, FileChange change) {
        return new CandidateFile(path, content, diffType, change);
    }

    /** 仓库相对路径。 */
    public String path() {
        return path;
    }

    public String content() {
        return content;
    }

    /** 可为 null（FULL 模式）。 */
    public DiffType diffType() {
        return diffType;
    }

    /** 可为 null（FULL 模式或文件未变更）。 */
    public FileChange change() {
        return change;
    }
}
