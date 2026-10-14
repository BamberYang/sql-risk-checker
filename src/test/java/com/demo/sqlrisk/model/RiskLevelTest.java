package com.demo.sqlrisk.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1.1 自测：级别权重与 max 语义。 */
class RiskLevelTest {

    @Test
    void weight_order_is_high_medium_low() {
        assertTrue(RiskLevel.HIGH.weight() > RiskLevel.MEDIUM.weight());
        assertTrue(RiskLevel.MEDIUM.weight() > RiskLevel.LOW.weight());
    }

    @Test
    void max_returns_highest_level() {
        assertEquals(Optional.of(RiskLevel.HIGH),
                RiskLevel.max(Arrays.asList(RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.LOW)));
        assertEquals(Optional.of(RiskLevel.MEDIUM),
                RiskLevel.max(Arrays.asList(RiskLevel.MEDIUM, RiskLevel.LOW)));
    }

    @Test
    void max_handles_empty_and_null_elements() {
        assertEquals(Optional.empty(), RiskLevel.max(Collections.<RiskLevel>emptyList()));
        assertEquals(Optional.empty(), RiskLevel.max(null));
        assertEquals(Optional.of(RiskLevel.LOW),
                RiskLevel.max(Arrays.asList(null, RiskLevel.LOW, null)));
    }
}
