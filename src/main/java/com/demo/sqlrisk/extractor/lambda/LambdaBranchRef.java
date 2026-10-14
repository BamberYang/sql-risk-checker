package com.demo.sqlrisk.extractor.lambda;

/**
 * 调用的控制流分支坐标（详细设计 §9.7-补充，M15）：
 * 指向所属分支组与组内路径序号；{@code pathIndex < 0} 表示该调用所在分支
 * **恒不成立**（无 else 的 if / 循环体：保守策略视为不执行，渲染期整体丢弃）。
 */
public final class LambdaBranchRef {

    /** 恒不成立分支的哨兵路径序号（无 else 的 if / 循环体）。 */
    public static final int NEVER_TAKEN = -1;

    private final int groupId;
    private final int pathIndex;

    public LambdaBranchRef(int groupId, int pathIndex) {
        this.groupId = groupId;
        this.pathIndex = pathIndex;
    }

    public int groupId() {
        return groupId;
    }

    public int pathIndex() {
        return pathIndex;
    }
}
