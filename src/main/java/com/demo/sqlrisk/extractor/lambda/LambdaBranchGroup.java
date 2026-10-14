package com.demo.sqlrisk.extractor.lambda;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 控制流分支组（详细设计 §9.7-补充，M15）：一个 if/else 或 switch 语句。
 * 渲染期按组枚举行路径组合（对齐 XML {@code <choose>} 语义，组合上限 MAX_SKELETONS）。
 *
 * <p>路径约定：if/else → {@code [if-then, if-else]}；无 else 的 if / 循环体 →
 * 单路径 {@code [if-skip / loop-skip]}（组内调用 pathIndex 为 {@link LambdaBranchRef#NEVER_TAKEN}）；
 * switch → 每个 case/default 条目一条路径（空条目的落穿并入后继条目），无 default 时追加 {@code switch-none}。</p>
 */
public final class LambdaBranchGroup {

    public enum Kind { IF, SWITCH }

    private final int groupId;
    private final Kind kind;
    private final List<String> pathLabels; // 全部为该组可选路径（渲染期枚举其一）

    public LambdaBranchGroup(int groupId, Kind kind, List<String> pathLabels) {
        this.groupId = groupId;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.pathLabels = Collections.unmodifiableList(new ArrayList<String>(
                Objects.requireNonNull(pathLabels, "pathLabels")));
    }

    public int groupId() {
        return groupId;
    }

    public Kind kind() {
        return kind;
    }

    public List<String> pathLabels() {
        return pathLabels;
    }

    /** 多路径组参与骨架枚举；单路径组（保守丢弃组）不产生组合。 */
    public boolean enumerable() {
        return pathLabels.size() > 1;
    }
}
