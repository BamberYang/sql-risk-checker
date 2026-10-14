package com.demo.sqlrisk.extractor.lambda;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 链式调用（详细设计 §9.7）。
 * {@code runtimeConditional=true}：该调用为布尔条件重载且条件非字面量 true
 * ——按保守策略在渲染期整体丢弃（暴露漏条件风险，FR-5）。
 */
public final class LambdaCall {

    private final String method;
    private final int line;
    private final List<LambdaArg> args;
    private final boolean runtimeConditional;
    private final List<LambdaCall> nested; // Consumer 组（and/or/nested/func）内部调用；其余为空
    private final List<LambdaBranchRef> branchPath; // 控制流分支坐标（外层→内层）；无条件调用为空

    public LambdaCall(String method, int line, List<LambdaArg> args, boolean runtimeConditional,
                      List<LambdaCall> nested) {
        this(method, line, args, runtimeConditional, nested, Collections.<LambdaBranchRef>emptyList());
    }

    public LambdaCall(String method, int line, List<LambdaArg> args, boolean runtimeConditional,
                      List<LambdaCall> nested, List<LambdaBranchRef> branchPath) {
        this.method = Objects.requireNonNull(method, "method");
        this.line = line;
        this.args = Collections.unmodifiableList(new ArrayList<LambdaArg>(args));
        this.runtimeConditional = runtimeConditional;
        this.nested = Collections.unmodifiableList(new ArrayList<LambdaCall>(nested));
        this.branchPath = Collections.unmodifiableList(new ArrayList<LambdaBranchRef>(
                Objects.requireNonNull(branchPath, "branchPath")));
    }

    public String method() {
        return method;
    }

    public int line() {
        return line;
    }

    public List<LambdaArg> args() {
        return args;
    }

    public boolean runtimeConditional() {
        return runtimeConditional;
    }

    /** Consumer 组内部调用（如 {@code w.and(x -> x.eq(a).or().eq(b))} 的两级调用）；非组调用为空列表。 */
    public List<LambdaCall> nested() {
        return nested;
    }

    /** 控制流分支坐标（外层→内层，§9.7-补充）；无条件调用为空列表。 */
    public List<LambdaBranchRef> branchPath() {
        return branchPath;
    }
}
