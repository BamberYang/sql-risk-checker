package com.demo.sqlrisk.core;

import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.StatementSource;

import java.util.List;

/**
 * 白名单过滤（详细设计 §13.3、FR-6）：
 * <ul>
 *   <li>仅 mapper.xml / 注解语句参与；Lambda 语句 ID 为「文件名:行号」，<b>不参与校验</b>（显式跳过）；</li>
 *   <li>匹配模式仅两种：完整匹配 与 {@code namespace.*} 前缀匹配（配置层已校验，§6）；</li>
 *   <li>命中 → {@link IgnoredItem}（reason=whitelist），不参与 P5 之后任何阶段。</li>
 * </ul>
 */
public final class WhitelistFilter {

    public static final String REASON_WHITELIST = "whitelist";

    private WhitelistFilter() {
    }

    public static boolean isIgnored(ExtractedStatement statement, List<String> patterns) {
        if (statement.source() == StatementSource.LAMBDA) {
            return false; // Lambda 不参与白名单（FR-6）
        }
        String id = statement.statementId();
        for (String pattern : patterns) {
            if (id.equals(pattern)) {
                return true; // 完整匹配
            }
            if (pattern.endsWith(".*")
                    && id.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true; // namespace.* 前缀匹配
            }
        }
        return false;
    }

    public static IgnoredItem toIgnored(ExtractedStatement statement) {
        return new IgnoredItem(statement.statementId(), statement.file(), statement.line(), REASON_WHITELIST);
    }
}
