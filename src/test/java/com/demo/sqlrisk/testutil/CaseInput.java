package com.demo.sqlrisk.testutil;

import java.nio.file.Path;

/**
 * {@link GoldenPipeline} 的输入：夹具仓库定位 + 用例附加配置（config/metadata/args）。
 */
public final class CaseInput {

    private final FixtureRepo repo;
    private final GoldenCase goldenCase;

    public CaseInput(FixtureRepo repo, GoldenCase goldenCase) {
        this.repo = repo;
        this.goldenCase = goldenCase;
    }

    public FixtureRepo repo() {
        return repo;
    }

    public GoldenCase goldenCase() {
        return goldenCase;
    }

    public Path repoPath() {
        return repo.path();
    }

    public String prodSha() {
        return repo.sha("prod");
    }

    public String headSha() {
        return repo.sha("head");
    }
}
