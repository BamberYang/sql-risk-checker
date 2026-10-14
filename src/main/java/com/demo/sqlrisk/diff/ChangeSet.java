package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.model.Mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 差异结果（详细设计 §8.3）：DIFF 模式的语句提取输入。
 * 提交均以完整 sha 表达（数据源恒为提交树，D21）。
 */
public final class ChangeSet {

    private final Mode mode;
    private final String headCommit;
    private final String prodCommit; // DIFF 模式恒有值
    private final List<FileChange> files;

    public ChangeSet(Mode mode, String headCommit, String prodCommit, List<FileChange> files) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.headCommit = Objects.requireNonNull(headCommit, "headCommit");
        this.prodCommit = prodCommit;
        this.files = Collections.unmodifiableList(new ArrayList<FileChange>(Objects.requireNonNull(files, "files")));
    }

    public Mode mode() {
        return mode;
    }

    public String headCommit() {
        return headCommit;
    }

    public Optional<String> prodCommit() {
        return Optional.ofNullable(prodCommit);
    }

    public List<FileChange> files() {
        return files;
    }

    /** 按路径查找变更文件。 */
    public Optional<FileChange> file(String path) {
        for (FileChange file : files) {
            if (file.path().equals(path)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }
}
