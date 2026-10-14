package com.demo.sqlrisk.parser;

import java.util.Objects;

/**
 * 保守折叠溯源（详细设计 §4.2/§10.2）：仅作 Finding 提示证据与不确定标记，
 * 不衍生独立风险规则（决策 D19）。Lambda 运行期条件复用 {@link BranchType#IF} 语义。
 */
public final class DroppedBranch {

    public enum BranchType { IF, WHEN, OTHERWISE }

    public enum Reason { CONSERVATIVE_FALSE, RUNTIME_CONDITION }

    /** 丢弃内容在 SQL 结构中的角色（H1/H2/H3 提示附注用）。 */
    public enum Role { CONDITION, SET, JOIN_ON, RAW }

    private final BranchType branchType;
    private final Reason reason;
    private final Role role;
    private final String fragmentText;

    public DroppedBranch(BranchType branchType, Reason reason, Role role, String fragmentText) {
        this.branchType = Objects.requireNonNull(branchType, "branchType");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.role = Objects.requireNonNull(role, "role");
        this.fragmentText = Objects.requireNonNull(fragmentText, "fragmentText");
    }

    public BranchType branchType() {
        return branchType;
    }

    public Reason reason() {
        return reason;
    }

    public Role role() {
        return role;
    }

    /** 被丢弃片段文本（诊断/提示，允许为空串）。 */
    public String fragmentText() {
        return fragmentText;
    }
}
