package com.demo.sqlrisk.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6.1 自测：占位符归一化与 ${} 标志。 */
class PlaceholderNormalizerTest {

    @Test
    void hash_placeholders_become_question_marks() {
        NormalizedSql normalized = PlaceholderNormalizer.normalize(
                "SELECT * FROM t WHERE id = #{id} AND name = #{ name , jdbcType=VARCHAR }");
        assertEquals("SELECT * FROM t WHERE id = ? AND name = ?", normalized.text());
        assertFalse(normalized.hasDollarPlaceholder());
    }

    @Test
    void dollar_placeholder_flags_and_replaces() {
        NormalizedSql normalized = PlaceholderNormalizer.normalize(
                "SELECT ${columns} FROM t WHERE id = #{id}");
        assertEquals("SELECT ? FROM t WHERE id = ?", normalized.text());
        assertTrue(normalized.hasDollarPlaceholder());
    }

    @Test
    void null_input_is_tolerated() {
        NormalizedSql normalized = PlaceholderNormalizer.normalize(null);
        assertEquals("", normalized.text());
        assertFalse(normalized.hasDollarPlaceholder());
    }
}
