package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.lambda.EntityIndex;
import com.demo.sqlrisk.extractor.lambda.EntityMeta;
import com.demo.sqlrisk.extractor.lambda.LambdaArg;
import com.demo.sqlrisk.extractor.lambda.LambdaBranchGroup;
import com.demo.sqlrisk.extractor.lambda.LambdaBranchRef;
import com.demo.sqlrisk.extractor.lambda.LambdaCall;
import com.demo.sqlrisk.extractor.lambda.LambdaChainModel;
import com.demo.sqlrisk.util.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lambda 骨架渲染（详细设计 §10.3）：将 {@link LambdaChainModel} 还原为 SQL 骨架文本。
 *
 * <ul>
 *   <li>谓词树：默认 AND；无参 {@code or()} 切换连接符；{@code and/or/nested/func(Consumer)} 括号嵌套组（递归）；</li>
 *   <li>值统一 {@code ?} 占位；{@code in()} 单元素展开；{@code apply/last/exists/inSql} 仅接受字面量片段（{n}→?）；</li>
 *   <li>运行期条件渲染期丢弃并记 DroppedBranch（保守策略 FR-5）；</li>
 *   <li>未收录方法 → 忽略条件 + unknown（DD-8，不猜测语义）；实体缺失 → 表名占位 + 列名按驼峰兜底（D13）。</li>
 * </ul>
 */
public final class LambdaSkeletonRenderer {

    private static final Set<String> COMPARISON_OPS = new HashSet<String>(Arrays.asList(
            "eq", "ne", "gt", "ge", "lt", "le", "like", "notLike", "likeLeft", "likeRight"));
    private static final Set<String> CONSUMER_GROUP_METHODS = new HashSet<String>(Arrays.asList(
            "and", "or", "nested", "func"));
    /** 不改变 SQL 结构的方法：静默忽略（不置 unknown）。 */
    private static final Set<String> IGNORED_METHODS = new HashSet<String>(Arrays.asList(
            "comment", "hints", "setEntityClass", "setParamAlias"));

    private static final Pattern APPLY_PLACEHOLDER = Pattern.compile("\\{\\d+\\}");

    private static final String METHOD_SET_INCR_BY = "setIncrBy";
    private static final String METHOD_SET_DECR_BY = "setDecrBy";
    private static final String AND_CONNECTOR = " AND ";

    private LambdaSkeletonRenderer() {
    }

    /** 兼容入口：渲染首个骨架（无分支链 = 唯一骨架；分支链全量见 {@link #renderAll}）。 */
    public static SqlSkeleton render(LambdaChainModel chain, EntityIndex entityIndex) {
        return renderAll(chain, entityIndex).get(0);
    }

    /**
     * 全骨架渲染（详细设计 §10.3-补充，M15）：控制流分支组按 {@code <choose>} 语义枚举行路径组合
     * ——if/else 与 switch/case 每个分支生成一个骨架，未选中分支的调用记 {@link DroppedBranch}
     * （{@code RUNTIME_CONDITION}）；无 else 的 if / 循环体中的调用保守丢弃
     * （{@code CONSERVATIVE_FALSE}，对齐 XML {@code <if>} 语义）。组合数超
     * {@link DynamicEvaluator#MAX_SKELETONS} 截断（DD-4，骨架标记 truncated）。
     */
    public static List<SqlSkeleton> renderAll(LambdaChainModel chain, EntityIndex entityIndex) {
        EntityMeta entity = null;
        if (entityIndex != null && chain.entityTypeName() != null) {
            entity = entityIndex.entity(chain.entityTypeName()).orElse(null);
        }
        String table = entity != null ? entity.table() : "?"; // 实体解析失败 → 表名占位（D13）

        List<int[]> combos = combinations(chain.branchGroups());
        boolean truncated = totalCombinations(chain.branchGroups()) > combos.size();
        List<SqlSkeleton> out = new ArrayList<SqlSkeleton>(combos.size());
        for (int[] combo : combos) {
            PredicateBuilder where = new PredicateBuilder();
            TopLevel top = new TopLevel();
            applyCalls(filterCalls(chain, combo, where), where, top, entity);
            out.add(new SqlSkeleton(buildSql(chain, table, where, top), where.dropped,
                    strategyTag(chain.branchGroups(), combo), where.unknown, truncated,
                    where.leadingWildcard, chain.incomplete()));
        }
        return out;
    }

    private static String buildSql(LambdaChainModel chain, String table, PredicateBuilder where, TopLevel top) {
        if (chain.updateWrapper()) {
            if (top.setItems.isEmpty()) {
                // 实体式 update(entity, wrapper)：SET 项来自实体、静态不可见 → 占位列保证可解析 + unknown（如实标注）
                where.unknown = true;
                top.setItems.add("update_placeholder = ?");
            }
            return "UPDATE " + table + " SET " + String.join(", ", top.setItems)
                    + (where.any ? " WHERE " + where.text : "")
                    + top.suffixText();
        }
        String columns = top.selectColumns.isEmpty() ? "*" : String.join(", ", top.selectColumns);
        return "SELECT " + columns + " FROM " + table
                + (where.any ? " WHERE " + where.text : "")
                + top.suffixText();
    }

    // ---------- 分支枚举（§10.3-补充，M15） ----------

    /** 路径组合枚举（索引 = 组全量 id；单路径组固定 0，其调用 pathIndex=-1 天然排除）。 */
    private static List<int[]> combinations(List<LambdaBranchGroup> groups) {
        List<int[]> out = new ArrayList<int[]>();
        expand(groups, 0, new int[groups.size()], out);
        if (out.isEmpty()) {
            out.add(new int[groups.size()]);
        }
        return out;
    }

    private static void expand(List<LambdaBranchGroup> groups, int position, int[] combo, List<int[]> out) {
        if (out.size() >= DynamicEvaluator.MAX_SKELETONS) {
            return; // DD-4 截断
        }
        if (position == groups.size()) {
            out.add(combo.clone());
            return;
        }
        LambdaBranchGroup group = groups.get(position);
        if (!group.enumerable()) {
            expand(groups, position + 1, combo, out);
            return;
        }
        for (int path = 0; path < group.pathLabels().size(); path++) {
            combo[position] = path;
            expand(groups, position + 1, combo, out);
        }
    }

    private static int totalCombinations(List<LambdaBranchGroup> groups) {
        int total = 1;
        for (LambdaBranchGroup group : groups) {
            if (group.enumerable()) {
                total *= group.pathLabels().size();
            }
        }
        return total;
    }

    /** 按组合过滤调用：未选中分支（含恒不成立分支）记 DroppedBranch。 */
    private static List<LambdaCall> filterCalls(LambdaChainModel chain, int[] combo, PredicateBuilder where) {
        List<LambdaCall> included = new ArrayList<LambdaCall>();
        for (LambdaCall call : chain.calls()) {
            Exclusion exclusion = exclusionOf(call, combo);
            if (exclusion == null) {
                included.add(call);
            } else {
                where.dropped.add(new DroppedBranch(
                        branchTypeOf(chain.branchGroups(), exclusion.decidingRef()),
                        exclusion.conservative() ? DroppedBranch.Reason.CONSERVATIVE_FALSE
                                : DroppedBranch.Reason.RUNTIME_CONDITION,
                        roleOf(call), call.method() + "()"));
            }
        }
        return included;
    }

    /** 调用在分支组合下的排除因；null = 未排除。 */
    private static Exclusion exclusionOf(LambdaCall call, int[] combo) {
        LambdaBranchRef decidingRef = null;
        boolean conservative = false;
        for (LambdaBranchRef ref : call.branchPath()) {
            if (ref.pathIndex() >= 0 && combo[ref.groupId()] == ref.pathIndex()) {
                continue; // 选中分支
            }
            if (ref.pathIndex() < 0) {
                conservative = true; // 恒不成立（循环体/无 else 的 if 内）
            }
            if (decidingRef == null) {
                decidingRef = ref; // 最外层排除因（溯源用）
            }
        }
        return decidingRef == null ? null : new Exclusion(decidingRef, conservative);
    }

    /** 排除因：最外层决定分支 + 是否恒不成立。 */
    private static final class Exclusion {

        private final LambdaBranchRef decidingRef;
        private final boolean conservative;

        Exclusion(LambdaBranchRef decidingRef, boolean conservative) {
            this.decidingRef = decidingRef;
            this.conservative = conservative;
        }

        LambdaBranchRef decidingRef() {
            return decidingRef;
        }

        boolean conservative() {
            return conservative;
        }
    }

    /** switch 默认条目 → OTHERWISE，其余 case 路径 → WHEN；if/循环 → IF（对齐 XML 分支类型）。 */
    private static DroppedBranch.BranchType branchTypeOf(List<LambdaBranchGroup> groups, LambdaBranchRef ref) {
        LambdaBranchGroup group = groups.get(ref.groupId());
        if (group.kind() == LambdaBranchGroup.Kind.IF || ref.pathIndex() < 0) {
            return DroppedBranch.BranchType.IF;
        }
        return group.pathLabels().get(ref.pathIndex()).startsWith("default")
                ? DroppedBranch.BranchType.OTHERWISE : DroppedBranch.BranchType.WHEN;
    }

    private static DroppedBranch.Role roleOf(LambdaCall call) {
        String method = call.method();
        return "set".equals(method) || METHOD_SET_INCR_BY.equals(method) || METHOD_SET_DECR_BY.equals(method)
                ? DroppedBranch.Role.SET : DroppedBranch.Role.CONDITION;
    }

    private static String strategyTag(List<LambdaBranchGroup> groups, int[] combo) {
        List<String> parts = groups.stream()
                .filter(LambdaBranchGroup::enumerable)
                .map(group -> group.pathLabels().get(combo[group.groupId()]))
                .collect(Collectors.toList());
        return parts.isEmpty() ? SqlSkeleton.TAG_BASE : "branch:" + String.join(", ", parts);
    }

    // ---------- 归约 ----------

    private static void applyCalls(List<LambdaCall> calls, PredicateBuilder pb, TopLevel top,
                                   EntityMeta entity) {
        for (LambdaCall call : calls) {
            applyCall(call, pb, top, entity);
        }
    }

    /** 单调用归约：运行期条件 → 连接符 → Consumer 组 → 比较 → 具名子句。 */
    private static void applyCall(LambdaCall call, PredicateBuilder pb, TopLevel top, EntityMeta entity) {
        if (call.runtimeConditional()) {
            // 保守策略：运行期条件视为不成立 → 整体丢弃（复用 IF 语义，见 DroppedBranch javadoc）
            pb.dropped.add(new DroppedBranch(DroppedBranch.BranchType.IF,
                    DroppedBranch.Reason.RUNTIME_CONDITION, DroppedBranch.Role.CONDITION,
                    call.method() + "()"));
            return;
        }
        String method = call.method();
        if (isNoArgConnector(call, "or")) {
            pb.switchConnector(" OR ");
            return;
        }
        if (isNoArgConnector(call, "and")) {
            pb.switchConnector(AND_CONNECTOR);
            return;
        }
        if (CONSUMER_GROUP_METHODS.contains(method) && !call.nested().isEmpty()) {
            applyGroup(call, pb, entity);
            return;
        }
        if (COMPARISON_OPS.contains(method)) {
            pb.add(renderComparison(call, entity, pb));
            return;
        }
        applyClauseCall(call, method, pb, top, entity);
    }

    /** 无参 and()/or()（无实参且无嵌套组）。 */
    private static boolean isNoArgConnector(LambdaCall call, String name) {
        return name.equals(call.method()) && call.args().isEmpty() && call.nested().isEmpty();
    }

    /** Consumer 组（and/or/nested/func(λ)）：组内独立组装后以括号并入。 */
    private static void applyGroup(LambdaCall call, PredicateBuilder pb, EntityMeta entity) {
        PredicateBuilder group = new PredicateBuilder();
        applyCalls(call.nested(), group, null, entity);
        pb.mergeSignalsFrom(group);
        if (group.any) {
            pb.add("(" + group.text + ")");
        }
    }

    /** 具名子句分发（顺序：比较关键词 → 字面量片段 → 顶层从句 → SET 族；均非本组 → unknown）。 */
    private static void applyClauseCall(LambdaCall call, String method, PredicateBuilder pb,
                                        TopLevel top, EntityMeta entity) {
        if (applyComparisonKeywords(call, method, pb, entity)
                || applyLiteralKeywords(call, method, pb, entity)
                || applyTopClauses(call, method, pb, top, entity)
                || applySetClauses(call, method, pb, top, entity)) {
            return;
        }
        if (!IGNORED_METHODS.contains(method)) {
            pb.unknown = true; // DD-8：未收录方法不猜测语义
        }
    }

    /** 关键词谓词：BETWEEN / IS NULL / IN；false = 非本组方法。 */
    private static boolean applyComparisonKeywords(LambdaCall call, String method, PredicateBuilder pb,
                                                   EntityMeta entity) {
        switch (method) {
            case "between":
            case "notBetween":
                pb.add(columnOf(firstArg(call), entity, pb)
                        + ("between".equals(method) ? " BETWEEN ? AND ?" : " NOT BETWEEN ? AND ?"));
                return true;
            case "isNull":
            case "isNotNull":
                pb.add(columnOf(firstArg(call), entity, pb)
                        + ("isNull".equals(method) ? " IS NULL" : " IS NOT NULL"));
                return true;
            case "in":
            case "notIn":
                pb.add(columnOf(firstArg(call), entity, pb)
                        + ("in".equals(method) ? " IN (?)" : " NOT IN (?)"));
                return true;
            default:
                return false;
        }
    }

    /** 字面量片段谓词：IN SQL / EXISTS / APPLY（片段非字面量 → unknown，不猜测）。 */
    private static boolean applyLiteralKeywords(LambdaCall call, String method, PredicateBuilder pb,
                                                EntityMeta entity) {
        if ("apply".equals(method)) {
            appendApply(call, pb);
            return true;
        }
        if (!isLiteralKeyword(method)) {
            return false;
        }
        String raw = isSqlKeyword(method) ? literalText(secondArg(call)) : literalText(firstArg(call));
        if (raw == null) {
            pb.unknown = true;
            return true;
        }
        pb.add(predicateTextOf(method, call, entity, pb, raw));
        return true;
    }

    private static boolean isLiteralKeyword(String method) {
        return isSqlKeyword(method) || "exists".equals(method) || "notExists".equals(method);
    }

    private static boolean isSqlKeyword(String method) {
        return "inSql".equals(method) || "notInSql".equals(method);
    }

    /** 片段谓词文本：{@code inSql → 列 IN (raw)}；{@code exists → EXISTS (raw)}。 */
    private static String predicateTextOf(String method, LambdaCall call, EntityMeta entity,
                                          PredicateBuilder pb, String raw) {
        if (isSqlKeyword(method)) {
            return columnOf(firstArg(call), entity, pb)
                    + ("inSql".equals(method) ? " IN (" : " NOT IN (") + raw + ")";
        }
        return ("exists".equals(method) ? "EXISTS (" : "NOT EXISTS (") + raw + ")";
    }

    private static void appendApply(LambdaCall call, PredicateBuilder pb) {
        String raw = literalText(firstArg(call));
        if (raw == null) {
            pb.unknown = true;
            return;
        }
        pb.add(APPLY_PLACEHOLDER.matcher(raw).replaceAll("?"));
    }

    /** 顶层从句：SELECT 列 / GROUP BY / ORDER BY / HAVING；false = 非本组方法。 */
    private static boolean applyTopClauses(LambdaCall call, String method, PredicateBuilder pb,
                                           TopLevel top, EntityMeta entity) {
        switch (method) {
            case "select":
                appendColumnArgs(call, entity, top == null ? null : top.selectColumns, pb, null);
                return true;
            case "groupBy":
                appendColumnArgs(call, entity, top == null ? null : top.groupBy, pb, null);
                return true;
            case "orderByAsc":
                appendColumnArgs(call, entity, top == null ? null : top.orderBy, pb, "ASC");
                return true;
            case "orderByDesc":
                appendColumnArgs(call, entity, top == null ? null : top.orderBy, pb, "DESC");
                return true;
            case "having": {
                String raw = literalText(firstArg(call));
                if (top == null || raw == null) {
                    pb.unknown = true;
                } else {
                    top.having.add(raw);
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** UPDATE 子句：SET 项族与 last 后缀；false = 非本组方法。 */
    private static boolean applySetClauses(LambdaCall call, String method, PredicateBuilder pb,
                                           TopLevel top, EntityMeta entity) {
        switch (method) {
            case "set":
                addSetItem(top, pb, columnOf(firstArg(call), entity, pb) + " = ?");
                return true;
            case "setSql": {
                String raw = literalText(firstArg(call));
                if (raw == null) {
                    pb.unknown = true;
                } else {
                    addSetItem(top, pb, raw);
                }
                return true;
            }
            case "setIncrBy":
            case "setDecrBy":
                addSetItem(top, pb, incrementItem(call, method, entity, pb));
                return true;
            case "last": {
                String raw = literalText(firstArg(call));
                if (top == null || raw == null) {
                    pb.unknown = true;
                } else {
                    top.lastSuffix = top.lastSuffix == null ? raw : top.lastSuffix + " " + raw;
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** SET 增减项：{@code 列 = 列 + ?} / {@code 列 = 列 - ?}。 */
    private static String incrementItem(LambdaCall call, String method, EntityMeta entity,
                                        PredicateBuilder pb) {
        String col = columnOf(firstArg(call), entity, pb);
        return col + " = " + col + (METHOD_SET_INCR_BY.equals(method) ? " + ?" : " - ?");
    }

    /** SET 项加入（top 缺失 → unknown，保守）。 */
    private static void addSetItem(TopLevel top, PredicateBuilder pb, String item) {
        if (top == null) {
            pb.unknown = true;
        } else {
            top.setItems.add(item);
        }
    }

    private static String renderComparison(LambdaCall call, EntityMeta entity, PredicateBuilder pb) {
        String column = columnOf(firstArg(call), entity, pb);
        switch (call.method()) {
            case "eq":
                return column + " = ?";
            case "ne":
                return column + " <> ?";
            case "gt":
                return column + " > ?";
            case "ge":
                return column + " >= ?";
            case "lt":
                return column + " < ?";
            case "le":
                return column + " <= ?";
            case "notLike":
                return column + " NOT LIKE ?";
            case "likeLeft":
                pb.leadingWildcard = true; // 确定性前导通配符（M3 判定输入）
                return column + " LIKE ?";
            case "like":
            case "likeRight":
            default:
                return column + " LIKE ?";
        }
    }

    private static void appendColumnArgs(LambdaCall call, EntityMeta entity, List<String> target,
                                         PredicateBuilder pb, String direction) {
        if (target == null) {
            pb.unknown = true;
            return;
        }
        for (LambdaArg arg : call.args()) {
            if (arg.kind() == LambdaArg.Kind.S_FUNCTION) {
                String column = columnOfArg(arg, entity, pb);
                target.add(direction == null ? column : column + " " + direction);
            } else if (arg.kind() == LambdaArg.Kind.STRING_LITERAL) {
                target.add(arg.text());
            } else {
                pb.unknown = true;
            }
        }
    }

    private static String columnOf(LambdaArg arg, EntityMeta entity, PredicateBuilder pb) {
        if (arg == null || arg.kind() != LambdaArg.Kind.S_FUNCTION) {
            pb.unknown = true;
            return "?";
        }
        return columnOfArg(arg, entity, pb);
    }

    private static String columnOfArg(LambdaArg arg, EntityMeta entity, PredicateBuilder pb) {
        String property = EntityMeta.accessorToProperty(arg.text());
        if (property == null) {
            pb.unknown = true;
            return "?";
        }
        if (entity != null) {
            Optional<String> column = entity.columnOf(property);
            if (column.isPresent()) {
                return column.get();
            }
            pb.unknown = true; // exist=false / 显式排除 → 保守标记
        }
        return TextUtils.camelToSnake(property);
    }

    private static LambdaArg firstArg(LambdaCall call) {
        return call.args().isEmpty() ? null : call.args().get(0);
    }

    private static LambdaArg secondArg(LambdaCall call) {
        return call.args().size() < 2 ? null : call.args().get(1);
    }

    private static String literalText(LambdaArg arg) {
        return arg != null && arg.kind() == LambdaArg.Kind.STRING_LITERAL ? arg.text() : null;
    }

    // ---------- 渲染状态 ----------

    /** WHERE 谓词组装状态（顶层与嵌套组各持一份）。 */
    private static final class PredicateBuilder {

        final StringBuilder text = new StringBuilder();
        String connector = AND_CONNECTOR;
        boolean any;
        boolean unknown;
        boolean leadingWildcard;
        final List<DroppedBranch> dropped = new ArrayList<DroppedBranch>();

        void add(String predicate) {
            if (any) {
                text.append(connector);
            }
            text.append(predicate);
            any = true;
        }

        /** 无参 or()/and()：切换后续连接符（无既有谓词时无效果）。 */
        void switchConnector(String newConnector) {
            if (any) {
                connector = newConnector;
            }
        }

        void mergeSignalsFrom(PredicateBuilder group) {
            unknown = unknown || group.unknown;
            leadingWildcard = leadingWildcard || group.leadingWildcard;
            dropped.addAll(group.dropped);
        }
    }

    /** 顶层从句状态（SELECT 列表 / SET 项 / GROUP BY / HAVING / ORDER BY / last 后缀）。 */
    private static final class TopLevel {

        final List<String> selectColumns = new ArrayList<String>();
        final List<String> setItems = new ArrayList<String>();
        final List<String> groupBy = new ArrayList<String>();
        final List<String> having = new ArrayList<String>();
        final List<String> orderBy = new ArrayList<String>();
        String lastSuffix; // nullable

        String suffixText() {
            StringBuilder sb = new StringBuilder();
            if (!groupBy.isEmpty()) {
                sb.append(" GROUP BY ").append(String.join(", ", groupBy));
            }
            if (!having.isEmpty()) {
                sb.append(" HAVING ").append(String.join(AND_CONNECTOR, having));
            }
            if (!orderBy.isEmpty()) {
                sb.append(" ORDER BY ").append(String.join(", ", orderBy));
            }
            if (lastSuffix != null) {
                sb.append(' ').append(lastSuffix);
            }
            return sb.toString();
        }
    }
}
