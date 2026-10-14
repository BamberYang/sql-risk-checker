package com.demo.sqlrisk.diff;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * FULL 模式候选收集（详细设计 §8.3，决策 D22）：遍历 head commit 的提交树
 * （不读工作目录，未提交内容不参与），按排除目录与扩展名过滤器筛选，字典序输出。
 */
public final class CommitTreeWalker {

    /** 默认排除目录名（任一路径段命中即排除）。 */
    public static final List<String> DEFAULT_EXCLUDED_DIRS = Collections.unmodifiableList(
            Arrays.asList("target", "build", "out", ".idea", "node_modules"));

    private CommitTreeWalker() {
    }

    public static List<String> collectCandidates(Repository repository, RevCommit head,
                                                 Predicate<String> filter, List<String> excludedDirs) {
        List<String> out = new ArrayList<String>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(head.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                String path = walk.getPathString();
                if (isExcluded(path, excludedDirs)) {
                    continue;
                }
                if (filter.test(path)) {
                    out.add(path);
                }
            }
        } catch (IOException e) {
            throw new DiffException("遍历提交树失败（" + head.getName() + "）：" + e.getMessage(), e);
        }
        Collections.sort(out);
        return out;
    }

    private static boolean isExcluded(String path, List<String> excludedDirs) {
        for (String dir : excludedDirs) {
            if (path.equals(dir) || path.startsWith(dir + "/") || path.contains("/" + dir + "/")) {
                return true;
            }
        }
        return false;
    }
}
