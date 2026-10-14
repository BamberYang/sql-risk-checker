package com.demo.sqlrisk.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * SQL 骨架（详细设计 §9.2/§10）：保守求值后的静态 SQL 文本 + 折叠溯源。
 *
 * <ul>
 *   <li>{@code strategyTag}：choose 多骨架路径（如 {@code choose:when#0/choose:otherwise}），无分支为 {@code base}；</li>
 *   <li>{@code truncated}：枚举超 MAX_SKELETONS 上限被截断（DD-4）；</li>
 *   <li>{@code leadingWildcardLike}：Lambda likeLeft 的确定性前导通配符（M3 判定输入）；</li>
 *   <li>{@code incomplete}：Lambda 还原不完整（D11）。</li>
 * </ul>
 */
public final class SqlSkeleton {

    public static final String TAG_BASE = "base";

    private final String text;
    private final List<DroppedBranch> droppedBranches;
    private final String strategyTag;
    private final boolean hasUnknownFragment;
    private final boolean truncated;
    private final boolean leadingWildcardLike;
    private final boolean incomplete;

    public SqlSkeleton(String text,
                       List<DroppedBranch> droppedBranches,
                       String strategyTag,
                       boolean hasUnknownFragment,
                       boolean truncated,
                       boolean leadingWildcardLike,
                       boolean incomplete) {
        this.text = Objects.requireNonNull(text, "text");
        this.droppedBranches = Collections.unmodifiableList(
                new ArrayList<DroppedBranch>(Objects.requireNonNull(droppedBranches, "droppedBranches")));
        this.strategyTag = Objects.requireNonNull(strategyTag, "strategyTag");
        this.hasUnknownFragment = hasUnknownFragment;
        this.truncated = truncated;
        this.leadingWildcardLike = leadingWildcardLike;
        this.incomplete = incomplete;
    }

    /** 无折叠/无不确定的简单骨架（纯注解文本等）。 */
    public static SqlSkeleton base(String text) {
        return new SqlSkeleton(text, Collections.<DroppedBranch>emptyList(), TAG_BASE,
                false, false, false, false);
    }

    public String text() {
        return text;
    }

    public List<DroppedBranch> droppedBranches() {
        return droppedBranches;
    }

    public String strategyTag() {
        return strategyTag;
    }

    public boolean hasUnknownFragment() {
        return hasUnknownFragment;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean leadingWildcardLike() {
        return leadingWildcardLike;
    }

    public boolean incomplete() {
        return incomplete;
    }

    /** 骨架不确定（未知片段 / 截断 / 还原不完整）→ 语句计入 uncertain。 */
    public boolean isUncertain() {
        return hasUnknownFragment || truncated || incomplete;
    }

    /** 折叠溯源中是否存在指定角色（H1/H2/H3 提示附注判定）。 */
    public boolean hasDroppedRole(DroppedBranch.Role role) {
        for (DroppedBranch branch : droppedBranches) {
            if (branch.role() == role) {
                return true;
            }
        }
        return false;
    }
}
