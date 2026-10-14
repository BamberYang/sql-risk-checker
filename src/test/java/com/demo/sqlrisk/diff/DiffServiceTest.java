package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.model.DiffType;
import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T4.2 自测：候选过滤、hunk 行集合与锚点、delete/rename 语义（详设 §8.3）。 */
class DiffServiceTest {

    @TempDir
    Path tempDir;

    private static String text(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    void maps_modified_added_deleted_and_filters_non_candidates() throws Exception {
        Map<String, String> prod = new LinkedHashMap<String, String>();
        prod.put("mapper/OrderMapper.xml", text("a", "b", "c"));
        prod.put("Old.java", "class Old {}");
        prod.put("docs/readme.md", "v1");

        Map<String, String> head = new LinkedHashMap<String, String>();
        head.put("mapper/OrderMapper.xml", text("a", "B", "c")); // 第 2 行修改
        head.put("New.java", "class New {}");
        head.put("docs/readme.md", "v2");                        // 非候选：忽略

        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("r"))
                .commit("prod", prod)
                .commit("head", head, Collections.singleton("Old.java")) // 删除：不产出
                .finish();

        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            ChangeSet changeSet = DiffService.diff(git,
                    git.resolveCommit(fixture.sha("prod")), git.resolveCommit(fixture.sha("head")));

            assertEquals(2, changeSet.files().size(), "仅 .java/.xml 候选：XML 修改 + Java 新增");
            assertEquals(fixture.sha("prod"), changeSet.prodCommit().get());
            assertEquals(fixture.sha("head"), changeSet.headCommit());
            assertFalse(changeSet.file("Old.java").isPresent(), "删除不产出（删除类不分析）");
            assertFalse(changeSet.file("docs/readme.md").isPresent(), "非候选扩展名不产出");

            FileChange xml = changeSet.file("mapper/OrderMapper.xml").get();
            assertEquals(DiffType.MODIFY, xml.type());
            Hunk hunk = xml.hunks().get(0);
            assertTrue(hunk.addedLines().contains(2), "新文件第 2 行在新增集合中");
            assertTrue(hunk.deletedLines().contains(2), "旧文件第 2 行在删除集合中");

            FileChange added = changeSet.file("New.java").get();
            assertEquals(DiffType.ADD, added.type());
            assertTrue(added.isAddedLike());
        }
    }

    @Test
    void pure_insertion_and_pure_deletion_anchors() throws Exception {
        FixtureRepo insertion = FixtureRepoBuilder.init(tempDir.resolve("ri"))
                .commit("prod", Collections.singletonMap("t.xml", text("a", "b", "c")))
                .commit("head", Collections.singletonMap("t.xml", text("a", "b", "NEW", "c")))
                .finish();
        try (GitRepo git = GitRepo.open(insertion.path().toString())) {
            Hunk hunk = DiffService.diff(git, git.resolveCommit(insertion.sha("prod")),
                    git.resolveCommit(insertion.sha("head"))).file("t.xml").get().hunks().get(0);
            assertEquals(Collections.singleton(3), hunk.addedLines());
            assertTrue(hunk.deletedLines().isEmpty());
            assertEquals(3, hunk.anchorLineInNew(), "插入 hunk 的锚点 = newStart");
        }

        FixtureRepo deletion = FixtureRepoBuilder.init(tempDir.resolve("rd"))
                .commit("prod", Collections.singletonMap("t.xml", text("a", "b", "c")))
                .commit("head", Collections.singletonMap("t.xml", text("a", "c")))
                .finish();
        try (GitRepo git = GitRepo.open(deletion.path().toString())) {
            Hunk hunk = DiffService.diff(git, git.resolveCommit(deletion.sha("prod")),
                    git.resolveCommit(deletion.sha("head"))).file("t.xml").get().hunks().get(0);
            assertTrue(hunk.addedLines().isEmpty());
            assertEquals(Collections.singleton(2), hunk.deletedLines());
            assertTrue(hunk.isPureDeletion());
            assertEquals(2, hunk.anchorLineInNew(), "纯删除锚点 = 新文件落点");
        }
    }

    @Test
    void rename_maps_to_rename_without_hunks() throws Exception {
        String content = "class A {}\n"; // 内容相同 → rename 检测应命中
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("rr"))
                .commit("prod", Collections.singletonMap("src/Old.java", content))
                .commit("head", Collections.singletonMap("src/New.java", content),
                        Collections.singleton("src/Old.java"))
                .finish();

        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            ChangeSet changeSet = DiffService.diff(git,
                    git.resolveCommit(fixture.sha("prod")), git.resolveCommit(fixture.sha("head")));
            assertEquals(1, changeSet.files().size());
            FileChange file = changeSet.files().get(0);
            assertEquals("src/New.java", file.path());
            assertEquals(DiffType.RENAME, file.type());
            assertTrue(file.hunks().isEmpty(), "RENAME 整文件按新增处理（D15）");
            assertTrue(file.isAddedLike());
        }
    }

    @Test
    void identical_trees_yield_no_files() throws Exception {
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("re"))
                .commit("prod", Collections.singletonMap("A.java", "class A {}"))
                .finish();
        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            ChangeSet changeSet = DiffService.diff(git,
                    git.resolveCommit(fixture.sha("prod")), git.resolveCommit(fixture.sha("prod")));
            assertTrue(changeSet.files().isEmpty());
        }
    }
}
