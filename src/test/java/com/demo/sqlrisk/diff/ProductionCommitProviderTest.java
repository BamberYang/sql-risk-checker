package com.demo.sqlrisk.diff;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** T4.4 自测：CLI 参数 provider 语义（FR-1 预留 SPI 的 v1 实现）。 */
class ProductionCommitProviderTest {

    @Test
    void returns_cli_value_or_empty() {
        RepoContext ctx = new RepoContext("/repo");
        assertEquals(Optional.of("abc123"), new CliArgProductionCommitProvider("abc123").provide(ctx));
        assertFalse(new CliArgProductionCommitProvider(null).provide(ctx).isPresent());
    }
}
