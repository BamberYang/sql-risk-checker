package com.demo.sqlrisk.config;

import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.util.TextUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 配置校验（详细设计 §6）：违反 → {@link ConfigException}（exit 2）。
 */
public final class ConfigValidator {

    /**
     * 白名单模式：仅支持 完整匹配 或 {@code namespace.*} 前缀匹配（FR-6），
     * {@code *} 仅可出现在末尾段。
     */
    private static final Pattern WHITELIST_PATTERN =
            Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*(\\.\\*)?$");

    private ConfigValidator() {
    }

    /** 解析规则级别文本；非法/缺失 → 异常。 */
    public static RiskLevel parseLevel(String ruleId, String levelText) {
        if (TextUtils.isBlank(levelText)) {
            throw new ConfigException("规则 " + ruleId + " 缺少 level（必须为 HIGH/MEDIUM/LOW）");
        }
        try {
            return RiskLevel.valueOf(levelText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("规则 " + ruleId + " 的 level 非法: " + levelText
                    + "（必须为 HIGH/MEDIUM/LOW）");
        }
    }

    /** 白名单模式合法性（完整匹配 / namespace.* 前缀匹配）。 */
    public static void validateWhitelistPatterns(List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || !WHITELIST_PATTERN.matcher(pattern.trim()).matches()) {
                throw new ConfigException("白名单模式非法: " + pattern
                        + "（仅支持 完整匹配 或 namespace.* 前缀匹配）");
            }
        }
    }

    /** 低危关键字表：元素不得为空。 */
    public static void validateKeywords(List<String> keywords) {
        for (String keyword : keywords) {
            if (TextUtils.isBlank(keyword)) {
                throw new ConfigException("low_risk_keywords 存在空关键字");
            }
        }
    }
}
