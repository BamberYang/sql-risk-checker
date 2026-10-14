package com.demo.sqlrisk.diff;

import java.util.Objects;

/** {@link ProductionCommitProvider} 的上下文（FR-1 预留）：当前仅承载仓库路径。 */
public final class RepoContext {

    private final String repoPath;

    public RepoContext(String repoPath) {
        this.repoPath = Objects.requireNonNull(repoPath, "repoPath");
    }

    public String repoPath() {
        return repoPath;
    }
}
