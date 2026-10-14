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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T0.2 自测：夹具仓库能正确构造 两次提交 + 修改 + 删除，且 sha 映射可用。 */
class FixtureRepoBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void builds_two_commits_with_modification_and_deletion() throws Exception {
        Map<String, String> prodFiles = new LinkedHashMap<String, String>();
        prodFiles.put("src/main/resources/mapper/OrderMapper.xml",
                "<mapper namespace=\"a\"><select id=\"s\">SELECT 1</select></mapper>");
        prodFiles.put("src/A.java", "class A {}");
        prodFiles.put("obsolete.txt", "will be deleted");

        Map<String, String> headFiles = new LinkedHashMap<String, String>();
        headFiles.put("src/main/resources/mapper/OrderMapper.xml",
                "<mapper namespace=\"a\"><select id=\"s\">SELECT 2</select></mapper>");
        headFiles.put("src/B.java", "class B {}");

        FixtureRepo repo = FixtureRepoBuilder.init(tempDir.resolve("fixture-repo"))
                .commit("prod", prodFiles)
                .commit("head", headFiles, Collections.singleton("obsolete.txt"))
                .finish();

        assertTrue(Files.isDirectory(repo.path().resolve(".git")), "应为 git 仓库");

        try (Git git = Git.open(repo.path().toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit prod = walk.parseCommit(ObjectId.fromString(repo.sha("prod")));
            RevCommit head = walk.parseCommit(ObjectId.fromString(repo.sha("head")));
            assertTrue(walk.isMergedInto(prod, head), "prod 应为 head 的祖先");

            Map<String, String> prodTree = readTree(git, prod);
            Map<String, String> headTree = readTree(git, head);

            assertEquals(3, prodTree.size());
            assertTrue(prodTree.containsKey("obsolete.txt"));
            // head = prod 三文件 - obsolete.txt + src/B.java = 3；未改动的 src/A.java 保留
            assertEquals(3, headTree.size());
            assertFalse(headTree.containsKey("obsolete.txt"), "head 中应已删除 obsolete.txt");
            assertEquals("class A {}", headTree.get("src/A.java"), "未改动文件应保留");
            assertTrue(headTree.get("src/main/resources/mapper/OrderMapper.xml").contains("SELECT 2"));
            assertEquals("class B {}", headTree.get("src/B.java"));
        }
    }

    @Test
    void unknown_logical_name_fails_fast() throws Exception {
        FixtureRepo repo = FixtureRepoBuilder.init(tempDir.resolve("fixture-repo-2"))
                .commit("prod", Collections.singletonMap("a.txt", "a"))
                .finish();
        assertThrows(IllegalArgumentException.class, () -> repo.sha("head"));
    }

    private static Map<String, String> readTree(Git git, RevCommit commit) throws Exception {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try (TreeWalk tw = new TreeWalk(git.getRepository())) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                byte[] bytes = git.getRepository().open(tw.getObjectId(0)).getBytes();
                out.put(tw.getPathString(), new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
