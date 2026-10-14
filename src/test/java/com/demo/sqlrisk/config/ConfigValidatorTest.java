package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T3.1 自测：白名单模式矩阵、level 解析、关键字校验（详设 §6）。 */
class ConfigValidatorTest {

    @Test
    void valid_whitelist_patterns_pass() {
        ConfigValidator.validateWhitelistPatterns(Arrays.asList(
                "com.demo.mapper.OrderMapper.selectById",
                "com.demo.mapper.*",
                "OrderMapper.selectById",
                "_handle$X.y"));
        ConfigValidator.validateWhitelistPatterns(Collections.<String>emptyList());
    }

    @Test
    void invalid_whitelist_patterns_fail() {
        for (String bad : Arrays.asList("*", "com.*.mapper", "com..a", "com.a.", "", "com.a**", "com.a.b*")) {
            assertThrows(ConfigException.class,
                    () -> ConfigValidator.validateWhitelistPatterns(Collections.singletonList(bad)),
                    "应拒绝: " + bad);
        }
    }

    @Test
    void parse_level_cases() {
        assertEquals(RiskLevel.HIGH, ConfigValidator.parseLevel("H1", "HIGH"));
        assertEquals(RiskLevel.MEDIUM, ConfigValidator.parseLevel("M1", " medium "));
        assertEquals(RiskLevel.LOW, ConfigValidator.parseLevel("L1", "low"));
        assertThrows(ConfigException.class, () -> ConfigValidator.parseLevel("H1", "CRITICAL"));
        assertThrows(ConfigException.class, () -> ConfigValidator.parseLevel("H1", null));
        assertThrows(ConfigException.class, () -> ConfigValidator.parseLevel("H1", " "));
    }

    @Test
    void keywords_blank_fails() {
        ConfigValidator.validateKeywords(Arrays.asList("SELECT *", "OR 1=1"));
        assertThrows(ConfigException.class,
                () -> ConfigValidator.validateKeywords(Collections.singletonList(" ")));
    }
}
