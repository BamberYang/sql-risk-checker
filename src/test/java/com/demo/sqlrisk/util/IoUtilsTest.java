package com.demo.sqlrisk.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1.3 自测：UTF-8 读写、自动建目录、覆盖写。 */
class IoUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void write_creates_parent_dirs_and_reads_back_utf8() throws Exception {
        Path file = tempDir.resolve("a/b/c/report.json");
        IoUtils.writeUtf8(file, "{\"值\":\"中文\"}");
        assertTrue(Files.isRegularFile(file));
        assertEquals("{\"值\":\"中文\"}", IoUtils.readUtf8(file));
    }

    @Test
    void write_overwrites_existing_file() throws Exception {
        Path file = tempDir.resolve("x.txt");
        IoUtils.writeUtf8(file, "第一版");
        IoUtils.writeUtf8(file, "第二版");
        assertEquals("第二版", IoUtils.readUtf8(file));
        assertEquals("第二版".getBytes(StandardCharsets.UTF_8).length, Files.readAllBytes(file).length,
                "覆盖写不应残留旧内容");
    }
}
