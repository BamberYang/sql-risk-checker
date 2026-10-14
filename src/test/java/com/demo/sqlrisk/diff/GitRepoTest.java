package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
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

/** T4.1 自测：仓库打开、commit 解析（ref/完整/缩写 sha）、提交树内容读取与编码探测。 */
class GitRepoTest {

    @TempDir
    Path tempDir;

    @Test
    void open_non_git_directory_fails_with_exit_2() throws Exception {
        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        DiffException e = assertThrows(DiffException.class, () -> GitRepo.open(plain.toString()));
        assertEquals(2, e.exitCode());
        assertTrue(e.getMessage().contains("非 git 仓库"));
    }

    @Test
    void open_missing_path_fails() {
        assertThrows(DiffException.class, () -> GitRepo.open(tempDir.resolve("nope").toString()));
    }

    @Test
    void resolve_supports_ref_full_and_abbreviated_sha() throws Exception {
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("r1"))
                .commit("prod", Collections.singletonMap("a.xml",
                        "<mapper namespace=\"m\"><select id=\"s\">SELECT 1</select></mapper>"))
                .finish();

        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            String sha = fixture.sha("prod");
            assertEquals(sha, git.resolveCommit(sha).getName(), "完整 sha");
            assertEquals(sha, git.resolveCommit("HEAD").getName(), "ref");
            assertEquals(sha, git.resolveCommit(sha.substring(0, 8)).getName(), "缩写 sha");
            assertThrows(DiffException.class,
                    () -> git.resolveCommit("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"), "不存在的完整 sha");
            assertThrows(DiffException.class, () -> git.resolveCommit("not-a-commit"), "非法输入");
        }
    }

    @Test
    void content_reader_reads_committed_content_ignores_working_tree() throws Exception {
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("src/A.java", "class A {}\n");
        files.put("mapper/OrderMapper.xml",
                "<mapper namespace=\"com.demo.OrderMapper\"><select id=\"s1\">SELECT 1</select></mapper>");
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("r2")).commit("prod", files).finish();

        // 工作区改动（不提交）与未跟踪文件：均不得影响读取结果（D21/D22）
        Files.write(fixture.path().resolve("src/A.java"),
                "class A { /* working tree */ }".getBytes(StandardCharsets.UTF_8));
        Files.write(fixture.path().resolve("src/Untracked.java"),
                "class U {}".getBytes(StandardCharsets.UTF_8));

        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            RepoContentReader reader = git.contentReader(git.head());
            assertEquals("class A {}\n", reader.readText("src/A.java").get(), "须读取提交内容而非工作区");
            assertFalse(reader.read("src/Untracked.java").isPresent(), "未提交文件不可见");
            assertEquals(Collections.singletonList("src/A.java"),
                    reader.listFiles(path -> path.endsWith(".java")));
        }
    }

    @Test
    void xml_encoding_declaration_is_respected() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"GBK\"?>\n"
                + "<mapper namespace=\"m\"><select id=\"s\">SELECT '中文值'</select></mapper>";
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("r3"))
                .commitBytes("prod", Collections.singletonMap("mapper/A.xml", xml.getBytes(Charset.forName("GBK"))))
                .finish();

        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            String text = git.contentReader(git.head()).readText("mapper/A.xml").get();
            assertEquals(xml, text, "GBK 声明应被探测并正确解码");
        }
    }
}
