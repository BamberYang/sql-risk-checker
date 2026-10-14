package com.demo.sqlrisk.diff;

import com.demo.sqlrisk.model.DiffType;
import com.demo.sqlrisk.model.Mode;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 差异计算（详细设计 §8.3）：
 * 仅保留候选文件（*.java / *.xml）；DELETE 不产出（删除类不分析）；
 * RENAME/COPY 视同新增侧（hunks 为空，整文件按新增语句处理，决策 D15）。
 */
public final class DiffService {

    private DiffService() {
    }

    /** 计算 prod → head 的提交树差异（数据源恒为提交树，工作区不参与）。 */
    public static ChangeSet diff(GitRepo repo, RevCommit prod, RevCommit head) {
        List<FileChange> files = new ArrayList<FileChange>();
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo.repository());
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);

            for (DiffEntry entry : formatter.scan(prod.getTree(), head.getTree())) {
                DiffType type = mapType(entry.getChangeType());
                if (type == DiffType.DELETE || !isCandidate(entry.getNewPath())) {
                    continue;
                }
                List<Hunk> hunks = type == DiffType.RENAME
                        ? Collections.<Hunk>emptyList()
                        : hunksOf(formatter, entry);
                files.add(new FileChange(entry.getNewPath(), type, hunks));
            }
        } catch (IOException e) {
            throw new DiffException("差异计算失败（" + prod.getName() + ".." + head.getName() + "）："
                    + e.getMessage(), e);
        }
        return new ChangeSet(Mode.DIFF, head.getName(), prod.getName(), files);
    }

    private static DiffType mapType(DiffEntry.ChangeType changeType) {
        switch (changeType) {
            case ADD:
                return DiffType.ADD;
            case DELETE:
                return DiffType.DELETE;
            case RENAME:
                return DiffType.RENAME;
            case COPY:
            case MODIFY:
            default:
                return DiffType.MODIFY;
        }
    }

    private static boolean isCandidate(String path) {
        return path != null && (path.endsWith(".java") || path.endsWith(".xml"));
    }

    /** Edit 为半开区间 [beginA,endA)/[beginB,endB)；Hunk 行号转为 1 基闭区间。 */
    private static List<Hunk> hunksOf(DiffFormatter formatter, DiffEntry entry) throws IOException {
        List<Hunk> hunks = new ArrayList<Hunk>();
        FileHeader header = formatter.toFileHeader(entry);
        for (Edit edit : header.toEditList()) {
            Set<Integer> added = new TreeSet<Integer>();
            for (int line = edit.getBeginB() + 1; line <= edit.getEndB(); line++) {
                added.add(line);
            }
            Set<Integer> deleted = new TreeSet<Integer>();
            for (int line = edit.getBeginA() + 1; line <= edit.getEndA(); line++) {
                deleted.add(line);
            }
            hunks.add(new Hunk(edit.getBeginA() + 1, edit.getEndA() - edit.getBeginA(),
                    edit.getBeginB() + 1, edit.getEndB() - edit.getBeginB(), added, deleted));
        }
        return hunks;
    }
}
