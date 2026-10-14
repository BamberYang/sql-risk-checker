package com.demo.sqlrisk.testutil;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T0.2 自测：执行器正确装配夹具输入（提交/删除/内容），并按 JSON 深度等价比对报告。 */
class GoldenCaseRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runs_pipeline_against_fixture_and_compares_json_ignoring_key_order() throws Exception {
        Path caseDir = tempDir.resolve("cases/demo");
        write(caseDir.resolve("repo/hello.xml"), "v1");
        write(caseDir.resolve("repo/gone.txt"), "bye");
        write(caseDir.resolve("changes/hello.xml"), "v2");
        write(caseDir.resolve("deleted.txt"), "gone.txt\n");
        write(caseDir.resolve("expected/report.json"), "{\"mode\":\"DIFF\",\"value\":\"v2\"}");

        GoldenCaseRunner.runCase(caseDir, input -> {
            // 验证输入装配：两次提交不同、删除生效、head 内容为 v2
            try (Git git = Git.open(input.repoPath().toFile());
                 RevWalk walk = new RevWalk(git.getRepository())) {
                assertNotEquals(input.prodSha(), input.headSha());
                RevCommit head = walk.parseCommit(ObjectId.fromString(input.headSha()));
                try (TreeWalk tw = new TreeWalk(git.getRepository())) {
                    tw.addTree(head.getTree());
                    tw.setRecursive(true);
                    boolean sawGone = false;
                    String hello = null;
                    while (tw.next()) {
                        String path = tw.getPathString();
                        if ("gone.txt".equals(path)) {
                            sawGone = true;
                        }
                        if ("hello.xml".equals(path)) {
                            hello = new String(git.getRepository().open(tw.getObjectId(0)).getBytes(),
                                    StandardCharsets.UTF_8);
                        }
                    }
                    assertFalse(sawGone, "deleted.txt 中的文件应已从 head 树删除");
                    assertEquals("v2", hello);
                }
            }
            // 键序与 expected 相反，验证深度等价比对
            return "{\"value\":\"v2\",\"mode\":\"DIFF\"}";
        });
    }

    @Test
    void mismatch_fails_with_readable_message() throws Exception {
        Path caseDir = tempDir.resolve("cases/bad");
        write(caseDir.resolve("repo/a.txt"), "x");
        write(caseDir.resolve("changes/a.txt"), "y");
        write(caseDir.resolve("expected/report.json"), "{\"value\":\"expected\"}");

        AssertionError error = assertThrows(AssertionError.class,
                () -> GoldenCaseRunner.runCase(caseDir, input -> "{\"value\":\"actual\"}"));
        assertTrue(error.getMessage().contains("expected"));
        assertTrue(error.getMessage().contains("actual"));
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
