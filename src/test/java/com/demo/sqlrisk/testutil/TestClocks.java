package com.demo.sqlrisk.testutil;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 确定性测试时钟（详细设计 §2/§16）：固定 instant 与 +08:00 时区，
 * 保证报告 generatedAt 在黄金样例比对中稳定（等价于 2026-10-14T08:00:00+08:00）。
 */
public final class TestClocks {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-10-14T00:00:00Z");
    public static final ZoneOffset FIXED_ZONE = ZoneOffset.ofHours(8);

    public static Clock fixed() {
        return Clock.fixed(FIXED_INSTANT, FIXED_ZONE);
    }

    private TestClocks() {
    }
}
