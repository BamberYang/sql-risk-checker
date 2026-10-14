package com.demo.sqlrisk.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件读写工具（详细设计 §14）：统一 UTF-8；写文件自动建父目录并覆盖。
 */
public final class IoUtils {

    private IoUtils() {
    }

    public static void writeUtf8(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    public static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
