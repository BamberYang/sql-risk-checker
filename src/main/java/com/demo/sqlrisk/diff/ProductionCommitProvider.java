package com.demo.sqlrisk.diff;

import java.util.Optional;

/**
 * 生产 commit 获取扩展点（FR-1 预留，概要设计 §5.4）：
 * 本版由 CLI 参数提供（{@link CliArgProductionCommitProvider}）；
 * 后续接入"调用接口获取线上运行版本 commit id"时替换实现，主流程不变。
 */
public interface ProductionCommitProvider {

    Optional<String> provide(RepoContext ctx);
}
