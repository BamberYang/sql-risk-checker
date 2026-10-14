package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T4.3 自测：提交树遍历、目录排除、工作区未提交内容不参与（D22）。 */
class CommitTreeWalkerTest {

    @TempDir
    Path tempDir;

    @Test
    void collects_candidates_excluding_dirs_and_untracked_files() throws Exception {
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("src/main/java/A.java", "class A {}");
        files.put("mapper/OrderMapper.xml",
                "<mapper namespace=\"m\"><select id=\"s\">SELECT 1</select></mapper>");
        files.put("target/Generated.java", "class G {}");
        files.put("src/deep/target/Inner.java", "class I {}");
        files.put("node_modules/pkg/x.js", "x");
        files.put("docs/readme.md", "doc");

        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("r")).commit("prod", files).finish();

        // 工作区新增未跟踪文件：不参与（数据源为提交树，D22）
        Files.write(fixture.path().resolve("src/Untracked.java"),
                "class U {}".getBytes(StandardCharsets.UTF_8));

        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            List<String> candidates = CommitTreeWalker.collectCandidates(
                    git.repository(), git.head(),
                    path -> path.endsWith(".java") || path.endsWith(".xml"),
                    CommitTreeWalker.DEFAULT_EXCLUDED_DIRS);

            assertEquals(Arrays.asList("mapper/OrderMapper.xml", "src/main/java/A.java"), candidates,
                    "含 xml+java；排除 target（含嵌套）、node_modules、非候选 .md、未跟踪文件");
        }
    }
}
