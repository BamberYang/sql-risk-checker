package com.demo.sqlrisk.core;

import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.StatementSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T8.1：白名单过滤（详细设计 §13.3）：完整/namespace.* 匹配、Lambda 不参与、reason 契约。 */
class WhitelistFilterTest {

    private static ExtractedStatement xmlStatement(String id) {
        return new ExtractedStatement(id, StatementSource.MAPPER_XML, ChangeType.MODIFIED,
                "mapper/A.xml", 1, "raw", "carrier", null);
    }

    private static ExtractedStatement lambdaStatement(String id) {
        return new ExtractedStatement(id, StatementSource.LAMBDA, ChangeType.MODIFIED,
                "src/main/java/OrderService.java", 10, "raw", "carrier", null);
    }

    @Test
    void exact_match_is_ignored() {
        List<String> patterns = Collections.singletonList("com.demo.mapper.OrderMapper.updateStatus");
        assertTrue(WhitelistFilter.isIgnored(xmlStatement("com.demo.mapper.OrderMapper.updateStatus"), patterns));
    }

    @Test
    void namespace_prefix_match_is_ignored() {
        List<String> patterns = Collections.singletonList("com.demo.mapper.*");
        assertTrue(WhitelistFilter.isIgnored(xmlStatement("com.demo.mapper.OrderMapper.updateStatus"), patterns));
        assertTrue(WhitelistFilter.isIgnored(xmlStatement("com.demo.mapper.OrderMapper.find"), patterns));
    }

    @Test
    void prefix_requires_namespace_dot_boundary() {
        List<String> patterns = Collections.singletonList("com.demo.mapper.*");
        // mapperX 不是 mapper 的子命名空间：模式去掉末尾 "*" 后为 "com.demo.mapper."，前缀不匹配
        assertFalse(WhitelistFilter.isIgnored(xmlStatement("com.demo.mapperX.update"), patterns));
    }

    @Test
    void non_matching_id_passes() {
        List<String> patterns = Arrays.asList("com.demo.mapper.*", "com.other.Exact");
        assertFalse(WhitelistFilter.isIgnored(xmlStatement("com.demo.other.doIt"), patterns));
    }

    @Test
    void empty_patterns_never_ignore() {
        assertFalse(WhitelistFilter.isIgnored(xmlStatement("m.s"), Collections.<String>emptyList()));
    }

    @Test
    void annotation_statements_participate() {
        ExtractedStatement statement = new ExtractedStatement("com.demo.mapper.A.find",
                StatementSource.ANNOTATION, ChangeType.MODIFIED, "A.java", 3, "raw", "carrier", null);
        assertTrue(WhitelistFilter.isIgnored(statement,
                Collections.singletonList("com.demo.mapper.*")));
    }

    @Test
    void lambda_statements_never_participate() {
        // Lambda 语句 ID 为「文件名:行号」；即使模式在字面上能前缀命中也不参与校验（FR-6）
        List<String> patterns = Arrays.asList("OrderService.*", "src/main/java/OrderService.java:10");
        assertFalse(WhitelistFilter.isIgnored(lambdaStatement("OrderService.java:10"), patterns));
    }

    @Test
    void to_ignored_builds_whitelist_reason_item() {
        ExtractedStatement statement = xmlStatement("com.demo.mapper.OrderMapper.updateStatus");
        IgnoredItem item = WhitelistFilter.toIgnored(statement);
        assertEquals("com.demo.mapper.OrderMapper.updateStatus", item.statementId());
        assertEquals("mapper/A.xml", item.file());
        assertEquals(1, item.line());
        assertEquals(WhitelistFilter.REASON_WHITELIST, item.reason());
        assertEquals("whitelist", item.reason());
    }
}
