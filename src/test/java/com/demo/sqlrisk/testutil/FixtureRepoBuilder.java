package com.demo.sqlrisk.testutil;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 夹具 Git 仓库构造器（详细设计 §18.2）：程序化构造 prod/head 两次提交的临时仓库。
 *
 * <pre>
 * FixtureRepo repo = FixtureRepoBuilder.init(dir)
 *         .commit("prod", files1)
 *         .commit("head", files2, deletedPaths)
 *         .finish();
 * </pre>
 *
 * 作者/提交者固定为 fixture/fixture@test，不读取系统 git 配置；
 * 提交时间固定为 {@link TestClocks#FIXED_INSTANT}（+08:00），使提交 SHA 完全由内容决定
 * ——黄金样例 expected/report.json 中的 headCommit/prodCommit 可确定性比对（§18.2/§16）。
 * {@link #finish()} 自动关闭底层 Git 资源并返回只读值对象。
 */
public final class FixtureRepoBuilder {

    private static final PersonIdent FIXED_COMMITTER = new PersonIdent("fixture", "fixture@test",
            Date.from(TestClocks.FIXED_INSTANT), TimeZone.getTimeZone("GMT+08:00"));

    private final Path workDir;
    private final Git git;
    private final Map<String, String> shasByName = new LinkedHashMap<String, String>();

    private FixtureRepoBuilder(Path workDir, Git git) {
        this.workDir = workDir;
        this.git = git;
    }

    public static FixtureRepoBuilder init(Path workDir) throws Exception {
        Files.createDirectories(workDir);
        Git git = Git.init().setDirectory(workDir.toFile()).call();
        return new FixtureRepoBuilder(workDir, git);
    }

    public FixtureRepoBuilder commit(String name, Map<String, String> files) throws Exception {
        return commit(name, files, Collections.<String>emptySet());
    }

    /**
     * 提交一轮变更。
     *
     * @param name    逻辑名（如 "prod"/"head"），finish() 后经 {@link FixtureRepo#sha(String)} 查询
     * @param files   相对路径 → UTF-8 文本内容（新增或覆盖）；至少改动一个文件（JGit 不接受空提交）
     * @param deleted 相对路径集合：从工作区删除
     */
    public FixtureRepoBuilder commit(String name, Map<String, String> files, Set<String> deleted) throws Exception {
        for (Map.Entry<String, String> e : files.entrySet()) {
            writeFile(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return commitStaged(name, deleted);
    }

    /** 以原始字节写入并提交（编码场景，如 GBK 编码的 XML）。 */
    public FixtureRepoBuilder commitBytes(String name, Map<String, byte[]> files) throws Exception {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            writeFile(e.getKey(), e.getValue());
        }
        return commitStaged(name, Collections.<String>emptySet());
    }

    private void writeFile(String path, byte[] content) throws Exception {
        Path p = workDir.resolve(path);
        Path parent = p.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(p, content);
    }

    private FixtureRepoBuilder commitStaged(String name, Set<String> deleted) throws Exception {
        for (String d : deleted) {
            Files.deleteIfExists(workDir.resolve(d));
        }
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call(); // 暂存删除

        // 注意：不可用 new PersonIdent(who) 拷贝——JGit 拷贝构造器只复制身份，时间会被重置为当前时间
        RevCommit commit = git.commit().setMessage(name)
                .setAuthor(FIXED_COMMITTER).setCommitter(FIXED_COMMITTER).call();
        if (shasByName.containsKey(name)) {
            throw new IllegalStateException("重复的提交逻辑名: " + name);
        }
        shasByName.put(name, commit.getName());
        return this;
    }

    /** 关闭底层 Git 资源并返回只读的 {@link FixtureRepo}。 */
    public FixtureRepo finish() {
        git.close();
        return new FixtureRepo(workDir, shasByName);
    }
}
