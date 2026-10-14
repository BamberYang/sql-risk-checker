package com.demo.sqlrisk.testutil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 黄金样例用例（详细设计 §18.2）目录约定：
 *
 * <pre>
 * cases/&lt;caseId&gt;/
 * ├── repo/                 初始提交（prod）的全部文件（UTF-8 文本，至少一个文件）
 * ├── changes/              可选：第二次提交（head）新增/修改的文件（同名覆盖）
 * ├── deleted.txt           可选：head 提交时删除的文件路径（每行一个，# 开头为注释）
 * ├── config.yaml           可选
 * ├── schema.sql            可选：H2 兼容 DDL，测试启动时执行到内存库（v1.8 替代 metadata.json）
 * ├── args                  可选：手工场景的命令行参数文本
 * └── expected/report.json  期望报告（JSON，必需）
 * </pre>
 */
public final class GoldenCase {

    private final Path caseDir;
    private final Map<String, String> initialFiles;
    private final Map<String, String> changedFiles;
    private final Set<String> deletedFiles;
    private final String expectedReportJson;
    private final String configYaml;   // nullable
    private final String schemaSql;    // nullable：H2 内存库建表 DDL（元数据来源，v1.8/FR-8）
    private final String argsText;     // nullable

    private GoldenCase(Path caseDir,
                       Map<String, String> initialFiles,
                       Map<String, String> changedFiles,
                       Set<String> deletedFiles,
                       String expectedReportJson,
                       String configYaml,
                       String schemaSql,
                       String argsText) {
        this.caseDir = caseDir;
        this.initialFiles = initialFiles;
        this.changedFiles = changedFiles;
        this.deletedFiles = deletedFiles;
        this.expectedReportJson = expectedReportJson;
        this.configYaml = configYaml;
        this.schemaSql = schemaSql;
        this.argsText = argsText;
    }

    public static GoldenCase load(Path caseDir) throws IOException {
        if (!Files.isDirectory(caseDir)) {
            throw new IllegalArgumentException("用例目录不存在: " + caseDir);
        }
        Path repoDir = caseDir.resolve("repo");
        if (!Files.isDirectory(repoDir)) {
            throw new IllegalArgumentException("用例缺少 repo/ 目录: " + caseDir);
        }
        Path expected = caseDir.resolve("expected").resolve("report.json");
        if (!Files.isRegularFile(expected)) {
            throw new IllegalArgumentException("用例缺少 expected/report.json: " + caseDir);
        }
        Path changesDir = caseDir.resolve("changes");
        return new GoldenCase(
                caseDir,
                readTree(repoDir),
                Files.isDirectory(changesDir) ? readTree(changesDir) : Collections.<String, String>emptyMap(),
                readDeleted(caseDir.resolve("deleted.txt")),
                new String(Files.readAllBytes(expected), StandardCharsets.UTF_8),
                readOptional(caseDir.resolve("config.yaml")),
                readOptional(caseDir.resolve("schema.sql")),
                readOptional(caseDir.resolve("args")));
    }

    public String caseId() {
        return caseDir.getFileName().toString();
    }

    public Path caseDir() {
        return caseDir;
    }

    public Map<String, String> initialFiles() {
        return initialFiles;
    }

    public Map<String, String> changedFiles() {
        return changedFiles;
    }

    public Set<String> deletedFiles() {
        return deletedFiles;
    }

    public String expectedReportJson() {
        return expectedReportJson;
    }

    /** 可为 null。 */
    public String configYaml() {
        return configYaml;
    }

    /** 可为 null：H2 兼容 DDL（v1.8 起元数据经内存库加载，见 AnalysisEngineIT）。 */
    public String schemaSql() {
        return schemaSql;
    }

    /** 可为 null。 */
    public String argsText() {
        return argsText;
    }

    private static Map<String, String> readTree(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            out.put(rel, new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Set<String> readDeleted(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    private static String readOptional(Path file) throws IOException {
        return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
    }
}
