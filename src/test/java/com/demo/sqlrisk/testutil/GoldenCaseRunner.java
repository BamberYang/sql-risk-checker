package com.demo.sqlrisk.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 黄金样例执行器（详细设计 §18.2）：
 * 装载用例 → 程序化构造临时夹具仓库（prod/head 两次提交）→ 调用被测管线 → JSON 深度比对。
 */
public final class GoldenCaseRunner {

    private GoldenCaseRunner() {
    }

    public static void runCase(Path caseDir, GoldenPipeline pipeline) throws Exception {
        GoldenCase goldenCase = GoldenCase.load(caseDir);
        Path tempRepo = Files.createTempDirectory("sql-risk-fixture-");
        try {
            FixtureRepo repo = FixtureRepoBuilder.init(tempRepo)
                    .commit("prod", goldenCase.initialFiles())
                    .commit("head", goldenCase.changedFiles(), goldenCase.deletedFiles())
                    .finish();
            String actual = pipeline.execute(new CaseInput(repo, goldenCase));
            JsonAssertions.assertEqualsJson("黄金样例 [" + goldenCase.caseId() + "]", goldenCase.expectedReportJson(), actual);
        } finally {
            deleteRecursively(tempRepo);
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path p : paths) {
            Files.deleteIfExists(p);
        }
    }
}
