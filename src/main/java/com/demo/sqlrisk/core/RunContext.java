package com.demo.sqlrisk.core;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.model.Mode;

import java.time.Clock;
import java.util.Objects;

/**
 * 运行上下文（详细设计 §13.1）：由 cli 层装配后传入 {@link AnalysisEngine}。
 *
 * <p>mode 判定：prodCommitArg == null → FULL，否则 DIFF（详设 §13.1）。
 * 配置（含已解析的元数据绝对路径）在 cli 层完成加载后注入。</p>
 */
public final class RunContext {

    private final String repoPath;
    private final Mode mode;
    private final String headCommitArg;  // nullable
    private final String prodCommitArg;  // nullable
    private final AppConfig config;
    private final String outputDir;
    private final boolean verbose;
    private final Clock clock;

    public RunContext(String repoPath,
                      String headCommitArg,
                      String prodCommitArg,
                      AppConfig config,
                      String outputDir,
                      boolean verbose,
                      Clock clock) {
        this.repoPath = Objects.requireNonNull(repoPath, "repoPath");
        this.headCommitArg = headCommitArg;
        this.prodCommitArg = prodCommitArg;
        this.mode = prodCommitArg == null ? Mode.FULL : Mode.DIFF;
        this.config = Objects.requireNonNull(config, "config");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        this.verbose = verbose;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String repoPath() {
        return repoPath;
    }

    public Mode mode() {
        return mode;
    }

    /** 可为 null（缺省 HEAD）。 */
    public String headCommitArg() {
        return headCommitArg;
    }

    /** 可为 null（仅 FULL 模式）。 */
    public String prodCommitArg() {
        return prodCommitArg;
    }

    public AppConfig config() {
        return config;
    }

    public String outputDir() {
        return outputDir;
    }

    public boolean verbose() {
        return verbose;
    }

    public Clock clock() {
        return clock;
    }

    /** 有效 head commit：缺省 HEAD。 */
    public String effectiveHeadCommitArg() {
        return headCommitArg != null ? headCommitArg : "HEAD";
    }
}
