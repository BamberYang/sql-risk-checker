package com.demo.sqlrisk.testutil;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已构造完成的夹具仓库：仓库路径 + 提交逻辑名 → commit sha 映射（详细设计 §18.2）。
 */
public final class FixtureRepo {

    private final Path path;
    private final Map<String, String> shasByName;

    FixtureRepo(Path path, Map<String, String> shasByName) {
        this.path = path;
        this.shasByName = Collections.unmodifiableMap(new LinkedHashMap<String, String>(shasByName));
    }

    public Path path() {
        return path;
    }

    /**
     * 按逻辑名查询 commit sha。
     *
     * @throws IllegalArgumentException 未知逻辑名（快速失败，避免用例静默用错提交）
     */
    public String sha(String name) {
        String sha = shasByName.get(name);
        if (sha == null) {
            throw new IllegalArgumentException("未知的提交逻辑名: " + name + "，已有: " + shasByName.keySet());
        }
        return sha;
    }

    public Map<String, String> allShas() {
        return shasByName;
    }
}
