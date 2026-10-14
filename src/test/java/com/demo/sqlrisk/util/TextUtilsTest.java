package com.demo.sqlrisk.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1.3 自测：截断、空白折叠、驼峰转换与包含判定。 */
class TextUtilsTest {

    @Test
    void truncate_adds_ellipsis_only_when_needed() {
        assertEquals("abc", TextUtils.truncate("abc", 5));
        assertEquals("ab…", TextUtils.truncate("abcde", 2));
        assertEquals(2 + 1, TextUtils.truncate("abcde", 2).length(), "截断后长度 = max + 省略号");
    }

    @Test
    void collapse_whitespace_folds_newlines_and_tabs() {
        assertEquals("SELECT * FROM t WHERE a = ?",
                TextUtils.collapseWhitespace("  SELECT *\n\tFROM t   WHERE a = ?  "));
        assertEquals("", TextUtils.collapseWhitespace("   \n\t "));
    }

    @Test
    void camel_to_snake_cases() {
        assertEquals("user_id", TextUtils.camelToSnake("userId"));
        assertEquals("user_id", TextUtils.camelToSnake("userID"));
        assertEquals("id", TextUtils.camelToSnake("ID"));
        assertEquals("order_no", TextUtils.camelToSnake("orderNo"));
        assertEquals("user_id", TextUtils.camelToSnake("user_id"), "已下划线写法保持不变");
        assertEquals("", TextUtils.camelToSnake(""));
        assertEquals("a1_b2", TextUtils.camelToSnake("a1B2"));
    }

    @Test
    void contains_ignore_case_and_blank() {
        assertTrue(TextUtils.containsIgnoreCase("SELECT * FROM t", "select *"));
        assertFalse(TextUtils.containsIgnoreCase("SELECT id FROM t", "select *"));
        assertFalse(TextUtils.containsIgnoreCase(null, "x"));
        assertTrue(TextUtils.isBlank(null));
        assertTrue(TextUtils.isBlank(" \t\n"));
        assertFalse(TextUtils.isBlank("x"));
    }
}
