package com.demo.sqlrisk.parser;

import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.xml.XmlFragmentIndex;
import com.demo.sqlrisk.util.TextUtils;
import com.demo.sqlrisk.util.XmlSupport;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 动态 SQL 保守求值（详细设计 §10.2、FR-5、决策 D19/DD-4）：
 * 将 mapper.xml 语句片段 / 注解 &lt;script&gt; 折叠为 1~N 个 {@link SqlSkeleton}。
 *
 * <ul>
 *   <li>&lt;if&gt; 恒不纳入（CONSERVATIVE_FALSE，带 role 溯源）；</li>
 *   <li>&lt;choose&gt; 生成 when#0 / otherwise 两路骨架（规则引擎按"任一骨架命中即停"处理，AC-4）；</li>
 *   <li>&lt;where&gt;/&lt;set&gt;/&lt;trim&gt; 标准折叠；&lt;foreach&gt; 单元素展开；</li>
 *   <li>&lt;include&gt; 经 {@link XmlFragmentIndex} 展开（&lt;property&gt; 值替换 + 深度上限防循环）；</li>
 *   <li>未知标签透传内容并置 hasUnknownFragment；枚举上限 {@link #MAX_SKELETONS}（DD-4：超限丢弃后续
 *       分支组合并标记 truncated，存活骨架仍为完整语句）。</li>
 * </ul>
 */
public final class DynamicEvaluator {

    private static final String KEYWORD_WHERE = "where";

    public static final int MAX_SKELETONS = 8;        // §17
    public static final int INCLUDE_DEPTH_LIMIT = 32; // §17

    private DynamicEvaluator() {
    }

    /** 求值：失败（include 无法解析/深度超限/片段结构错误）抛 {@link DynamicEvaluationException}。 */
    public static List<SqlSkeleton> evaluate(DynamicXmlText carrier, DynamicEvalContext ctx) {
        DynNode root;
        try {
            root = DynNodeBuilder.build(carrier.fragmentText());
        } catch (XmlSupport.XmlParseException e) {
            throw new DynamicEvaluationException(e.getMessage());
        }
        List<Partial> partials = new Evaluator(ctx, carrier.ownerNamespace())
                .evalChildren(root, DroppedBranch.Role.RAW, 0);
        if (partials.isEmpty()) {
            return Collections.singletonList(SqlSkeleton.base(""));
        }
        List<SqlSkeleton> skeletons = new ArrayList<SqlSkeleton>(partials.size());
        for (Partial partial : partials) {
            String tag = partial.tags.isEmpty() ? SqlSkeleton.TAG_BASE : String.join("/", partial.tags);
            skeletons.add(new SqlSkeleton(partial.text.toString().trim(), partial.dropped, tag,
                    partial.unknown, partial.truncated, false, false));
        }
        return skeletons;
    }

    // ---------- 求值器 ----------

    private static final class Evaluator {

        private final DynamicEvalContext ctx;
        private final String ownerNamespace;

        Evaluator(DynamicEvalContext ctx, String ownerNamespace) {
            this.ctx = ctx;
            this.ownerNamespace = ownerNamespace;
        }

        List<Partial> evalChildren(DynNode parent, DroppedBranch.Role role, int depth) {
            List<Partial> partials = new ArrayList<Partial>();
            partials.add(new Partial());
            for (Object child : parent.children) {
                partials = crossCombine(partials, evalNode(child, role, depth));
            }
            return partials;
        }

        private List<Partial> evalNode(Object child, DroppedBranch.Role role, int depth) {
            if (child instanceof String) {
                Partial partial = new Partial();
                partial.text.append((String) child);
                return Collections.singletonList(partial);
            }
            DynNode node = (DynNode) child;
            switch (node.name.toLowerCase(Locale.ROOT)) {
                case "if":
                    return Collections.singletonList(dropFor(node, DroppedBranch.BranchType.IF, role));
                case "choose":
                    return evalChoose(node, role, depth);
                case KEYWORD_WHERE:
                    return foldWhereSet(node, depth, DroppedBranch.Role.CONDITION, true);
                case "set":
                    return foldWhereSet(node, depth, DroppedBranch.Role.SET, false);
                case "trim":
                    return evalTrim(node, role, depth);
                case "foreach":
                    return evalForeach(node, role, depth);
                case "include":
                    return evalInclude(node, role, depth);
                case "script":
                    return evalChildren(node, role, depth);
                case "selectkey":
                case "bind":
                    return Collections.singletonList(unknownOnly());
                case "property":
                    return Collections.singletonList(new Partial());
                default:
                    return markUnknown(evalChildren(node, role, depth));
            }
        }

        /** &lt;choose&gt;：when#0 与 otherwise 两路（AC-4）。 */
        private List<Partial> evalChoose(DynNode node, DroppedBranch.Role role, int depth) {
            List<DynNode> whens = node.childElements("when");
            Optional<DynNode> otherwise = node.firstChildElement("otherwise");
            List<Partial> results = new ArrayList<Partial>();

            if (!whens.isEmpty()) {
                Partial seed = new Partial();
                seed.tags.add("choose:when#0");
                for (int i = 1; i < whens.size(); i++) {
                    seed.dropped.add(dropBranchFor(whens.get(i), DroppedBranch.BranchType.WHEN, role));
                }
                if (otherwise.isPresent()) {
                    seed.dropped.add(dropBranchFor(otherwise.get(), DroppedBranch.BranchType.OTHERWISE, role));
                }
                results.addAll(crossCombine(Collections.singletonList(seed),
                        evalChildren(whens.get(0), role, depth)));
            }

            Partial otherwiseSeed = new Partial();
            otherwiseSeed.tags.add("choose:otherwise");
            for (DynNode when : whens) {
                otherwiseSeed.dropped.add(dropBranchFor(when, DroppedBranch.BranchType.WHEN, role));
            }
            if (otherwise.isPresent()) {
                results.addAll(crossCombine(Collections.singletonList(otherwiseSeed),
                        evalChildren(otherwise.get(), role, depth)));
            } else {
                results.add(otherwiseSeed);
            }
            return results;
        }

        /** &lt;where&gt;/&lt;set&gt; 折叠：去多余连接词/逗号、空块整体省略。 */
        private List<Partial> foldWhereSet(DynNode node, int depth,
                                           DroppedBranch.Role innerRole, boolean where) {
            List<Partial> inner = evalChildren(node, innerRole, depth);
            List<Partial> out = new ArrayList<Partial>(inner.size());
            for (Partial partial : inner) {
                String content = partial.text.toString().trim();
                if (where) {
                    content = stripLeadingConnector(content);
                    if (!content.isEmpty()) {
                        content = "WHERE " + content;
                    }
                } else {
                    while (content.endsWith(",")) {
                        content = content.substring(0, content.length() - 1).trim();
                    }
                    if (!content.isEmpty()) {
                        content = "SET " + content;
                    }
                }
                out.add(partial.withReplacedText(content));
            }
            return out;
        }

        private List<Partial> evalTrim(DynNode node, DroppedBranch.Role role, int depth) {
            String prefix = node.attr("prefix");
            String suffix = node.attr("suffix");
            String prefixOverrides = node.attr("prefixOverrides");
            String suffixOverrides = node.attr("suffixOverrides");
            List<Partial> inner = evalChildren(node, trimRole(prefixOverrides, prefix, role), depth);
            List<Partial> out = new ArrayList<Partial>(inner.size());
            for (Partial partial : inner) {
                String content = partial.text.toString().trim();
                content = stripOverrides(content, prefixOverrides, true);
                content = stripOverrides(content, suffixOverrides, false);
                if (!content.isEmpty()) {
                    if (prefix != null) {
                        content = prefix.trim() + " " + content;
                    }
                    if (suffix != null) {
                        content = content + " " + suffix.trim();
                    }
                }
                out.add(partial.withReplacedText(content));
            }
            return out;
        }

        /** &lt;foreach&gt;：单元素展开（open + 一次 body + close）。 */
        private List<Partial> evalForeach(DynNode node, DroppedBranch.Role role, int depth) {
            String open = node.attr("open");
            String close = node.attr("close");
            List<Partial> body = evalChildren(node, role, depth);
            List<Partial> out = new ArrayList<Partial>(body.size());
            for (Partial partial : body) {
                StringBuilder text = new StringBuilder();
                if (open != null) {
                    text.append(open);
                }
                text.append(partial.text);
                if (close != null) {
                    text.append(close);
                }
                out.add(partial.withReplacedText(text.toString()));
            }
            return out;
        }

        /** &lt;include&gt;：refid 解析 + 属性替换 + 原文切片 + 递归求值（深度上限防循环）。 */
        private List<Partial> evalInclude(DynNode node, DroppedBranch.Role role, int depth) {
            if (depth >= INCLUDE_DEPTH_LIMIT) {
                throw new DynamicEvaluationException("include 展开深度超限（可能循环引用）");
            }
            String refid = node.attr("refid");
            if (refid == null || refid.trim().isEmpty()) {
                throw new DynamicEvaluationException("include 缺少 refid");
            }
            refid = refid.trim();
            if (ctx.fragmentIndex() == null || ctx.fileContents() == null) {
                throw new DynamicEvaluationException("无法展开 include（缺少片段索引或文件内容）: " + refid);
            }
            String key = resolveRefid(refid);
            Optional<XmlFragmentIndex.FragmentDef> def = ctx.fragmentIndex().fragment(key);
            if (!def.isPresent()) {
                throw new DynamicEvaluationException("无法解析的 include refid: " + refid);
            }
            String fileContent = ctx.fileContents().get(def.get().file());
            if (fileContent == null) {
                throw new DynamicEvaluationException("片段所在文件内容缺失: " + def.get().file());
            }
            String slice = TextUtils.sliceLines(fileContent, def.get().startLine(), def.get().endLine());
            slice = applyProperties(node, slice);
            DynNode fragmentRoot = DynNodeBuilder.build(slice);
            return evalChildren(fragmentRoot, role, depth + 1);
        }

        private String resolveRefid(String refid) {
            int lastDot = refid.lastIndexOf('.');
            if (lastDot > 0) {
                return refid.substring(0, lastDot) + XmlFragmentIndex.SEP_FRAGMENT
                        + refid.substring(lastDot + 1);
            }
            return ownerNamespace + XmlFragmentIndex.SEP_FRAGMENT + refid;
        }

        private String applyProperties(DynNode includeNode, String slice) {
            String result = slice;
            for (DynNode property : includeNode.childElements("property")) {
                String name = property.attr("name");
                String value = property.attr("value");
                if (name != null && value != null) {
                    result = result.replace("${" + name + "}", value);
                }
            }
            return result;
        }

        // ---------- partial 操作 ----------

        private Partial dropFor(DynNode node, DroppedBranch.BranchType type, DroppedBranch.Role role) {
            Partial partial = new Partial();
            partial.dropped.add(dropBranchFor(node, type, role));
            return partial;
        }

        private DroppedBranch dropBranchFor(DynNode node, DroppedBranch.BranchType type,
                                            DroppedBranch.Role role) {
            String source = node.sourceText();
            return new DroppedBranch(type, DroppedBranch.Reason.CONSERVATIVE_FALSE,
                    resolveRole(role, node.innerText()), source);
        }

        private Partial unknownOnly() {
            Partial partial = new Partial();
            partial.unknown = true;
            return partial;
        }

        private List<Partial> markUnknown(List<Partial> partials) {
            for (Partial partial : partials) {
                partial.unknown = true;
            }
            return partials;
        }

        private List<Partial> crossCombine(List<Partial> left, List<Partial> right) {
            List<Partial> out = new ArrayList<Partial>();
            boolean truncated = false;
            for (Partial l : left) {
                for (Partial r : right) {
                    if (out.size() >= MAX_SKELETONS) {
                        truncated = true;
                        break;
                    }
                    out.add(l.combine(r));
                }
                if (truncated) {
                    break;
                }
            }
            if (truncated) {
                for (Partial partial : out) {
                    partial.truncated = true;
                }
            }
            return out;
        }

        private static DroppedBranch.Role resolveRole(DroppedBranch.Role contextRole, String content) {
            if (contextRole == DroppedBranch.Role.CONDITION || contextRole == DroppedBranch.Role.SET
                    || contextRole == DroppedBranch.Role.JOIN_ON) {
                return contextRole;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            if (lower.contains("join") || lower.contains(" on ")) {
                return DroppedBranch.Role.JOIN_ON;
            }
            // 裸 <if>WHERE …</if> 形态（§D19 回写）：片段文本以 WHERE 开头 → 折叠即 WHERE 消失，
            // 归 CONDITION 以驱动 H1/H2/H3 的保守策略附注
            String trimmed = lower.trim();
            if (KEYWORD_WHERE.equals(trimmed) || (trimmed.startsWith(KEYWORD_WHERE) && trimmed.length() > 5
                    && Character.isWhitespace(trimmed.charAt(5)))) {
                return DroppedBranch.Role.CONDITION;
            }
            return DroppedBranch.Role.RAW;
        }

        private static DroppedBranch.Role trimRole(String prefixOverrides, String prefix,
                                                   DroppedBranch.Role role) {
            if (prefixOverrides != null) {
                String lower = prefixOverrides.toLowerCase(Locale.ROOT);
                if (lower.contains("and") || lower.contains("or")) {
                    return DroppedBranch.Role.CONDITION;
                }
            }
            if (prefix != null && "set".equalsIgnoreCase(prefix.trim())) {
                return DroppedBranch.Role.SET;
            }
            return role;
        }

        private static String stripLeadingConnector(String content) {
            String upper = content.toUpperCase(Locale.ROOT);
            if (upper.startsWith("AND ")) {
                return content.substring(4).trim();
            }
            if (upper.startsWith("OR ")) {
                return content.substring(3).trim();
            }
            return content;
        }

        private static String stripOverrides(String content, String overrides, boolean leading) {
            if (overrides == null || content.isEmpty()) {
                return content;
            }
            for (String token : overrides.split("\\|")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String upper = content.toUpperCase(Locale.ROOT);
                String tokenUpper = trimmed.toUpperCase(Locale.ROOT);
                if (leading && upper.startsWith(tokenUpper)) {
                    return content.substring(trimmed.length()).trim();
                }
                if (!leading && upper.endsWith(tokenUpper)) {
                    return content.substring(0, content.length() - trimmed.length()).trim();
                }
            }
            return content;
        }
    }

    /** 求值中间态：文本 + 溯源 + choose 路径标签 + 标志。 */
    private static final class Partial {

        final StringBuilder text = new StringBuilder();
        final List<DroppedBranch> dropped = new ArrayList<DroppedBranch>();
        final List<String> tags = new ArrayList<String>();
        boolean unknown;
        boolean truncated;

        Partial combine(Partial other) {
            Partial combined = new Partial();
            combined.text.append(this.text).append(other.text);
            combined.dropped.addAll(this.dropped);
            combined.dropped.addAll(other.dropped);
            combined.tags.addAll(this.tags);
            combined.tags.addAll(other.tags);
            combined.unknown = this.unknown || other.unknown;
            combined.truncated = this.truncated || other.truncated;
            return combined;
        }

        /** 保留溯源/标签/标志，替换文本（where/set/trim/foreach 折叠用）。 */
        Partial withReplacedText(String newText) {
            Partial replaced = new Partial();
            replaced.text.append(newText);
            replaced.dropped.addAll(dropped);
            replaced.tags.addAll(tags);
            replaced.unknown = unknown;
            replaced.truncated = truncated;
            return replaced;
        }
    }

    // ---------- 轻量节点树 ----------

    static final class DynNode {

        final String name;
        final Map<String, String> attrs = new LinkedHashMap<String, String>();
        final List<Object> children = new ArrayList<Object>(); // String 文本 / DynNode

        DynNode(String name) {
            this.name = name;
        }

        String attr(String attrName) {
            return attrs.get(attrName);
        }

        List<DynNode> childElements(String elementName) {
            return children.stream()
                    .filter(child -> child instanceof DynNode
                            && ((DynNode) child).name.equalsIgnoreCase(elementName))
                    .map(child -> (DynNode) child)
                    .collect(Collectors.toList());
        }

        Optional<DynNode> firstChildElement(String elementName) {
            List<DynNode> elements = childElements(elementName);
            return elements.isEmpty() ? Optional.<DynNode>empty() : Optional.of(elements.get(0));
        }

        void appendText(String text) {
            if (!children.isEmpty() && children.get(children.size() - 1) instanceof String) {
                int last = children.size() - 1;
                children.set(last, (String) children.get(last) + text);
            } else {
                children.add(text);
            }
        }

        /** 诊断用：重建带标签原文。 */
        String sourceText() {
            StringBuilder sb = new StringBuilder();
            sb.append('<').append(name).append('>');
            for (Object child : children) {
                sb.append(child instanceof String ? (String) child : ((DynNode) child).sourceText());
            }
            sb.append("</").append(name).append('>');
            return sb.toString();
        }

        /** 角色推断用：仅拼接子内容文本（不含本元素标签），保留嵌套元素文本。 */
        String innerText() {
            StringBuilder sb = new StringBuilder();
            appendInnerText(this, sb);
            return sb.toString();
        }

        private static void appendInnerText(DynNode node, StringBuilder sb) {
            for (Object child : node.children) {
                if (child instanceof String) {
                    sb.append((String) child);
                } else {
                    appendInnerText((DynNode) child, sb);
                }
            }
        }
    }

    static final class DynNodeBuilder extends DefaultHandler {

        private final Deque<DynNode> stack = new ArrayDeque<DynNode>();
        private DynNode root;

        static DynNode build(String content) {
            DynNodeBuilder builder = new DynNodeBuilder();
            XmlSupport.parse(content, builder);
            if (builder.root == null) {
                throw new DynamicEvaluationException("动态片段为空或无法解析");
            }
            return builder.root;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            DynNode node = new DynNode(qName);
            for (int i = 0; i < attrs.getLength(); i++) {
                node.attrs.put(attrs.getQName(i), attrs.getValue(i));
            }
            if (stack.isEmpty()) {
                if (root == null) {
                    root = node;
                } else {
                    root.children.add(node); // 多根容错：并入首个根
                }
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!stack.isEmpty()) {
                stack.pop();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!stack.isEmpty()) {
                stack.peek().appendText(new String(ch, start, length));
            }
        }
    }
}
