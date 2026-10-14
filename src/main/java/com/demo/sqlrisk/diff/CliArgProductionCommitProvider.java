package com.demo.sqlrisk.diff;

import java.util.Optional;

/** v1 实现：生产 commit id 由命令行参数（--prod-commit）提供。 */
public final class CliArgProductionCommitProvider implements ProductionCommitProvider {

    private final String prodCommitArg; // nullable：未传 → FULL 模式

    public CliArgProductionCommitProvider(String prodCommitArg) {
        this.prodCommitArg = prodCommitArg;
    }

    @Override
    public Optional<String> provide(RepoContext ctx) {
        return Optional.ofNullable(prodCommitArg);
    }
}
