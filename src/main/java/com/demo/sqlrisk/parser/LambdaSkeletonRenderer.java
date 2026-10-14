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
            LambdaBranchRef decidingRef = null;
            boolean conservative = false;
            for (LambdaBranchRef ref : call.branchPath()) {
                boolean excluded = ref.pathIndex() < 0 || combo[ref.groupId()] != ref.pathIndex();
                if (!excluded) {
                    continue;
                }
                if (ref.pathIndex() < 0) {
                    conservative = true;
                }
                if (decidingRef == null) {
                    decidingRef = ref; // 最外层排除因（溯源用）
                }
            }
            if (decidingRef == null) {
                included.add(call);
                continue;
            }
            where.dropped.add(new DroppedBranch(
                    branchTypeOf(chain.branchGroups(), decidingRef),
                    conservative ? DroppedBranch.Reason.CONSERVATIVE_FALSE
                            : DroppedBranch.Reason.RUNTIME_CONDITION,
                    roleOf(call), call.method() + "()"));
        }
        return included;
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
        return "set".equals(method) || "setIncrBy".equals(method) || "setDecrBy".equals(method)
                ? DroppedBranch.Role.SET : DroppedBranch.Role.CONDITION;
    }

    private static String strategyTag(List<LambdaBranchGroup> groups, int[] combo) {
        List<String> parts = new ArrayList<String>();
        for (LambdaBranchGroup group : groups) {
            if (group.enumerable()) {
                parts.add(group.pathLabels().get(combo[group.groupId()]));
            }
        }
        return parts.isEmpty() ? SqlSkeleton.TAG_BASE : "branch:" + String.join(", ", parts);
    }

    // ---------- 归约 ----------

    private static void applyCalls(List<LambdaCall> calls, PredicateBuilder pb, TopLevel top,
                                   EntityMeta entity) {
        for (LambdaCall call : calls) {
            if (call.runtimeConditional()) {
                // 保守策略：运行期条件视为不成立 → 整体丢弃（复用 IF 语义，见 DroppedBranch javadoc）
                pb.dropped.add(new DroppedBranch(DroppedBranch.BranchType.IF,
                        DroppedBranch.Reason.RUNTIME_CONDITION, DroppedBranch.Role.CONDITION,
                        call.method() + "()"));
                continue;
            }
            String method = call.method();
            boolean noArg = call.args().isEmpty() && call.nested().isEmpty();

            if ("or".equals(method) && noArg) {
                if (pb.any) {
                    pb.connector = " OR ";
                }
                continue;
            }
            if ("and".equals(method) && noArg) {
                if (pb.any) {
                    pb.connector = " AND ";
                }
                continue;
            }
            if (CONSUMER_GROUP_METHODS.contains(method) && !call.nested().isEmpty()) {
                PredicateBuilder group = new PredicateBuilder();
                applyCalls(call.nested(), group, null, entity);
                pb.mergeSignalsFrom(group);
                if (group.any) {
                    pb.add("(" + group.text + ")");
                }
                continue;
            }
            if (COMPARISON_OPS.contains(method)) {
                pb.add(renderComparison(call, entity, pb));
                continue;
            }
            switch (method) {
                case "between":
                case "notBetween":
                    pb.add(columnOf(firstArg(call), entity, pb)
                            + ("between".equals(method) ? " BETWEEN ? AND ?" : " NOT BETWEEN ? AND ?"));
                    break;
                case "isNull":
                case "isNotNull":
                    pb.add(columnOf(firstArg(call), entity, pb)
                            + ("isNull".equals(method) ? " IS NULL" : " IS NOT NULL"));
                    break;
                case "in":
                case "notIn":
                    pb.add(columnOf(firstArg(call), entity, pb)
                            + ("in".equals(method) ? " IN (?)" : " NOT IN (?)"));
                    break;
                case "inSql":
                case "notInSql": {
                    String raw = literalText(secondArg(call));
                    if (raw == null) {
                        pb.unknown = true;
                    } else {
                        pb.add(columnOf(firstArg(call), entity, pb)
                                + ("inSql".equals(method) ? " IN (" : " NOT IN (") + raw + ")");
                    }
                    break;
                }
                case "exists":
                case "notExists": {
                    String raw = literalText(firstArg(call));
                    if (raw == null) {
                        pb.unknown = true;
                    } else {
                        pb.add(("exists".equals(method) ? "EXISTS (" : "NOT EXISTS (") + raw + ")");
                    }
                    break;
                }
                case "apply": {
                    String raw = literalText(firstArg(call));
                    if (raw == null) {
                        pb.unknown = true;
                    } else {
                        pb.add(APPLY_PLACEHOLDER.matcher(raw).replaceAll("?"));
                    }
                    break;
                }
                case "select":
                    appendColumnArgs(call, entity, top == null ? null : top.selectColumns, pb, null);
                    break;
                case "groupBy":
                    appendColumnArgs(call, entity, top == null ? null : top.groupBy, pb, null);
                    break;
                case "orderByAsc":
                    appendColumnArgs(call, entity, top == null ? null : top.orderBy, pb, "ASC");
                    break;
                case "orderByDesc":
                    appendColumnArgs(call, entity, top == null ? null : top.orderBy, pb, "DESC");
                    break;
                case "having": {
                    String raw = literalText(firstArg(call));
                    if (top != null && raw != null) {
                        top.having.add(raw);
                    } else {
                        pb.unknown = true;
                    }
                    break;
                }
                case "set": {
                    String col = columnOf(firstArg(call), entity, pb);
                    if (top != null) {
                        top.setItems.add(col + " = ?");
                    } else {
                        pb.unknown = true;
                    }
                    break;
                }
                case "setSql": {
                    String raw = literalText(firstArg(call));
                    if (top != null && raw != null) {
                        top.setItems.add(raw);
                    } else {
                        pb.unknown = true;
                    }
                    break;
                }
                case "setIncrBy":
                case "setDecrBy": {
                    String col = columnOf(firstArg(call), entity, pb);
                    if (top != null) {
                        top.setItems.add(col + " = " + col + ("setIncrBy".equals(method) ? " + ?" : " - ?"));
                    } else {
                        pb.unknown = true;
                    }
                    break;
                }
                case "last": {
                    String raw = literalText(firstArg(call));
                    if (top != null && raw != null) {
                        top.lastSuffix = top.lastSuffix == null ? raw : top.lastSuffix + " " + raw;
                    } else {
                        pb.unknown = true;
                    }
                    break;
                }
                default:
                    if (!IGNORED_METHODS.contains(method)) {
                        pb.unknown = true; // DD-8：未收录方法不猜测语义
                    }
                    break;
            }
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
        String connector = " AND ";
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
                sb.append(" HAVING ").append(String.join(" AND ", having));
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
