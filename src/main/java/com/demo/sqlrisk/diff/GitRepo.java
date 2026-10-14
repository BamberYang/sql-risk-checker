package com.demo.sqlrisk.diff;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * 目标仓库只读访问（详细设计 §8.1）：打开、commit 解析（ref / 完整 sha / 缩写 sha 双校验）、
 * 提交树内容读取。绝不写 refs 或工作区；不读取系统 git 环境变量。
 *
 * <p>{@link RevWalk} 非线程安全：本类实例供 P2 串行阶段使用（§16）。</p>
 */
public final class GitRepo implements Closeable {

    private static final String HEX = "[0-9a-fA-F]+";

    private final Repository repository;
    private final RevWalk revWalk;

    private GitRepo(Repository repository) {
        this.repository = repository;
        this.revWalk = new RevWalk(repository);
    }

    public static GitRepo open(String repoPath) {
        if (repoPath == null || repoPath.trim().isEmpty()) {
            throw new DiffException("仓库路径为空");
        }
        Path path = Paths.get(repoPath);
        if (!Files.isDirectory(path)) {
            throw new DiffException("仓库路径不存在或不是目录: " + repoPath);
        }
        try {
            Repository repository = new FileRepositoryBuilder()
                    .findGitDir(path.toFile())
                    .setMustExist(true)
                    .build();
            return new GitRepo(repository);
        } catch (IOException | IllegalArgumentException e) {
            throw new DiffException("非 git 仓库或无法打开: " + repoPath + "（" + e.getMessage() + "）");
        }
    }

    public Repository repository() {
        return repository;
    }

    /**
     * 解析 commit：支持 ref（HEAD/分支）、完整 sha 与缩写 sha（≥4 位 hex，唯一匹配）。
     *
     * @throws DiffException 无法解析 / 不存在 / 非 commit 对象
     */
    public RevCommit resolveCommit(String refOrSha) {
        try {
            ObjectId id = tryResolve(refOrSha);
            if (id == null) {
                throw new DiffException("非法 commit：无法解析 " + refOrSha);
            }
            return revWalk.parseCommit(id);
        } catch (DiffException e) {
            throw e;
        } catch (MissingObjectException e) {
            throw new DiffException("commit 不存在: " + refOrSha);
        } catch (IncorrectObjectTypeException e) {
            throw new DiffException("对象不是 commit: " + refOrSha);
        } catch (IOException e) {
            throw new DiffException("解析 commit 失败: " + refOrSha + "（" + e.getMessage() + "）");
        }
    }

    /** 当前 HEAD 提交（--head-commit 缺省时使用）。 */
    public RevCommit head() {
        return resolveCommit("HEAD");
    }

    /** 指定提交的树内容读取器（工作区文件永不参与，D21/D22）。 */
    public RepoContentReader contentReader(RevCommit commit) {
        return new RepoContentReader(repository, commit);
    }

    private ObjectId tryResolve(String rev) throws IOException {
        ObjectId id = repository.resolve(rev);
        if (id != null) {
            return id;
        }
        if (rev != null && rev.length() >= 4 && rev.length() < 40 && rev.matches(HEX)) {
            try (ObjectReader reader = repository.newObjectReader()) {
                Collection<ObjectId> candidates = reader.resolve(AbbreviatedObjectId.fromString(rev));
                if (candidates.size() == 1) {
                    return candidates.iterator().next();
                }
                if (candidates.size() > 1) {
                    throw new DiffException("commit id 不明确（缩写匹配多个对象）: " + rev);
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        revWalk.close();
        repository.close();
    }
}
