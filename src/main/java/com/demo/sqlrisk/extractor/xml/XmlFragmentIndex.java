package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.extractor.CandidateFile;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * XML 片段与语句站点索引（详细设计 §9.3）：对全仓 mapper.xml 单趟 SAX 构建。
 *
 * <ul>
 *   <li>key 规则：语句 = {@code namespace.id}；片段 = {@code namespace#sqlId}；</li>
 *   <li>refid 规范化：含 '.' → 拆出 namespace 前缀（跨文件）；裸 id → 本 namespace 前缀；</li>
 *   <li>反向闭包：{@link #closureOf} 支持传递引用与跨文件，循环引用由 visited 防重入；</li>
 *   <li>解析失败的文件整体丢弃并记入 {@link #parseFailedFiles()}（隔离，不静默）。</li>
 * </ul>
 */
public final class XmlFragmentIndex {

    public static final char SEP_STATEMENT = '.';
    public static final char SEP_FRAGMENT = '#';

    private static final Set<String> STATEMENT_TAGS =
            new HashSet<String>(java.util.Arrays.asList("select", "update", "insert", "delete"));

    private final Map<String, FragmentDef> fragments;
    private final Map<String, List<IncludeSite>> references; // fragKey → 引用它的站点（反向）
    private final Map<String, StatementSite> statements;
    private final Map<String, List<StatementSite>> statementsByFile;
    private final Map<String, List<FragmentDef>> fragmentsByFile;
    private final List<String> parseFailedFiles;
    private final List<IncludeSite> unresolvedIncludes; // 无法解析 refid 的引用点（诊断用）

    private XmlFragmentIndex(Map<String, FragmentDef> fragments,
                             Map<String, List<IncludeSite>> references,
                             Map<String, StatementSite> statements,
                             Map<String, List<StatementSite>> statementsByFile,
                             Map<String, List<FragmentDef>> fragmentsByFile,
                             List<String> parseFailedFiles,
                             List<IncludeSite> unresolvedIncludes) {
        this.fragments = fragments;
        this.references = references;
        this.statements = statements;
        this.statementsByFile = statementsByFile;
        this.fragmentsByFile = fragmentsByFile;
        this.parseFailedFiles = parseFailedFiles;
        this.unresolvedIncludes = unresolvedIncludes;
    }

    // ---------- 模型 ----------

    /** 语句站点（namespace + id + 行范围）。 */
    public static final class StatementSite {
        private final String namespace;
        private final String id;
        private final String tagName;
        private final String file;
        private final int startLine;
        private final int endLine;

        StatementSite(String namespace, String id, String tagName, String file, int startLine, int endLine) {
            this.namespace = namespace;
            this.id = id;
            this.tagName = tagName;
            this.file = file;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public String key() {
            return namespace + SEP_STATEMENT + id;
        }

        public String namespace() {
            return namespace;
        }

        public String id() {
            return id;
        }

        public String tagName() {
            return tagName;
        }

        public String file() {
            return file;
        }

        public int startLine() {
            return startLine;
        }

        public int endLine() {
            return endLine;
        }
    }

    /** &lt;sql&gt; 片段定义。 */
    public static final class FragmentDef {
        private final String namespace;
        private final String sqlId;
        private final String file;
        private final int startLine;
        private final int endLine;

        FragmentDef(String namespace, String sqlId, String file, int startLine, int endLine) {
            this.namespace = namespace;
            this.sqlId = sqlId;
            this.file = file;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public String key() {
            return namespace + SEP_FRAGMENT + sqlId;
        }

        public String namespace() {
            return namespace;
        }

        public String sqlId() {
            return sqlId;
        }

        public String file() {
            return file;
        }

        public int startLine() {
            return startLine;
        }

        public int endLine() {
            return endLine;
        }
    }

    /** &lt;include&gt; 引用站点（反向依赖边）。 */
    public static final class IncludeSite {

        public enum OwnerKind { STATEMENT, FRAGMENT }

        private final String file;
        private final int line;
        private final String refidRaw;
        private final String resolvedKey; // nullable：未解析
        private final OwnerKind ownerKind;
        private final String ownerKey;

        IncludeSite(String file, int line, String refidRaw, String resolvedKey,
                    OwnerKind ownerKind, String ownerKey) {
            this.file = file;
            this.line = line;
            this.refidRaw = refidRaw;
            this.resolvedKey = resolvedKey;
            this.ownerKind = ownerKind;
            this.ownerKey = ownerKey;
        }

        public String file() {
            return file;
        }

        public int line() {
            return line;
        }

        public String refidRaw() {
            return refidRaw;
        }

        /** 可为 null（未解析）。 */
        public String resolvedKey() {
            return resolvedKey;
        }

        public OwnerKind ownerKind() {
            return ownerKind;
        }

        /** 语句 key 或片段 key（按 ownerKind）。 */
        public String ownerKey() {
            return ownerKey;
        }
    }

    // ---------- 构建 ----------

    public static XmlFragmentIndex build(List<CandidateFile> xmlFiles) {
        Map<String, FragmentDef> fragments = new LinkedHashMap<String, FragmentDef>();
        Map<String, StatementSite> statements = new LinkedHashMap<String, StatementSite>();
        List<RawInclude> rawIncludes = new ArrayList<RawInclude>();
        List<String> failed = new ArrayList<String>();

        List<CandidateFile> sorted = new ArrayList<CandidateFile>(xmlFiles);
        Collections.sort(sorted, Comparator.comparing(CandidateFile::path));

        for (CandidateFile file : sorted) {
            if (!MapperXmlParser.looksLikeMapper(file.content())) {
                continue;
            }
            IndexHandler handler = new IndexHandler(file.path());
            try {
                MapperXmlParser.parse(file.content(), handler);
            } catch (MapperXmlParser.MapperXmlParseException e) {
                failed.add(file.path()); // 该文件数据整体丢弃（隔离），不作为提取来源
                continue;
            }
            for (FragmentDef def : handler.fragments) {
                if (!fragments.containsKey(def.key())) {
                    fragments.put(def.key(), def);
                }
            }
            for (StatementSite site : handler.statements) {
                if (!statements.containsKey(site.key())) {
                    statements.put(site.key(), site);
                }
            }
            rawIncludes.addAll(handler.includes);
        }

        // 两阶段：全部片段已知后解析 refid
        Map<String, List<IncludeSite>> references = new TreeMap<String, List<IncludeSite>>();
        List<IncludeSite> unresolved = new ArrayList<IncludeSite>();
        for (RawInclude raw : rawIncludes) {
            if (raw.owner == null) {
                continue; // include 出现在无效位置（不在语句/片段内）：忽略
            }
            String resolved = resolveRefid(raw.refidRaw, raw.ownerNamespace, fragments.keySet());
            IncludeSite site = new IncludeSite(raw.file, raw.line, raw.refidRaw, resolved,
                    raw.owner.kind, raw.owner.key);
            if (resolved == null) {
                unresolved.add(site);
            } else {
                List<IncludeSite> list = references.get(resolved);
                if (list == null) {
                    list = new ArrayList<IncludeSite>();
                    references.put(resolved, list);
                }
                list.add(site);
            }
        }

        Map<String, List<StatementSite>> statementsByFile = new LinkedHashMap<String, List<StatementSite>>();
        for (StatementSite site : statements.values()) {
            List<StatementSite> list = statementsByFile.get(site.file());
            if (list == null) {
                list = new ArrayList<StatementSite>();
                statementsByFile.put(site.file(), list);
            }
            list.add(site);
        }
        Map<String, List<FragmentDef>> fragmentsByFile = new LinkedHashMap<String, List<FragmentDef>>();
        for (FragmentDef def : fragments.values()) {
            List<FragmentDef> list = fragmentsByFile.get(def.file());
            if (list == null) {
                list = new ArrayList<FragmentDef>();
                fragmentsByFile.put(def.file(), list);
            }
            list.add(def);
        }
        Comparator<StatementSite> byLine = Comparator.comparingInt(StatementSite::startLine);
        for (List<StatementSite> list : statementsByFile.values()) {
            Collections.sort(list, byLine);
        }
        Comparator<FragmentDef> fragByLine = Comparator.comparingInt(FragmentDef::startLine);
        for (List<FragmentDef> list : fragmentsByFile.values()) {
            Collections.sort(list, fragByLine);
        }

        return new XmlFragmentIndex(
                Collections.unmodifiableMap(fragments),
                Collections.unmodifiableMap(references),
                Collections.unmodifiableMap(statements),
                Collections.unmodifiableMap(statementsByFile),
                Collections.unmodifiableMap(fragmentsByFile),
                Collections.unmodifiableList(failed),
                Collections.unmodifiableList(unresolved));
    }

    /** refid 规范化：含 '.' → 拆出 namespace 前缀；裸 id → 本 namespace；均需在片段表中存在。 */
    static String resolveRefid(String refid, String ownerNamespace, Set<String> fragmentKeys) {
        if (refid == null || refid.isEmpty()) {
            return null;
        }
        String candidate;
        int lastDot = refid.lastIndexOf('.');
        if (lastDot > 0) {
            candidate = refid.substring(0, lastDot) + SEP_FRAGMENT + refid.substring(lastDot + 1);
        } else {
            candidate = ownerNamespace + SEP_FRAGMENT + refid;
        }
        return fragmentKeys.contains(candidate) ? candidate : null;
    }

    // ---------- 查询 ----------

    public Optional<StatementSite> statement(String key) {
        return Optional.ofNullable(statements.get(key));
    }

    public Optional<FragmentDef> fragment(String key) {
        return Optional.ofNullable(fragments.get(key));
    }

    public Collection<StatementSite> allStatements() {
        List<StatementSite> out = new ArrayList<StatementSite>(statements.values());
        Collections.sort(out, Comparator.comparing(StatementSite::file).thenComparingInt(StatementSite::startLine));
        return Collections.unmodifiableList(out);
    }

    public List<StatementSite> statementsOfFile(String path) {
        List<StatementSite> list = statementsByFile.get(path);
        return list == null ? Collections.<StatementSite>emptyList() : list;
    }

    public List<FragmentDef> fragmentsOfFile(String path) {
        List<FragmentDef> list = fragmentsByFile.get(path);
        return list == null ? Collections.<FragmentDef>emptyList() : list;
    }

    public List<String> parseFailedFiles() {
        return parseFailedFiles;
    }

    /** 无法解析 refid 的 include 引用点（诊断；对应语句在 M6 展开时按解析失败处理）。 */
    public List<IncludeSite> unresolvedIncludes() {
        return unresolvedIncludes;
    }

    /**
     * 反向依赖闭包（详细设计 §9.3）：变更片段集合 → 受影响语句 → 来源片段 key（根因种子）。
     *
     * <p>传递传播时 changedVia 一律指向**被变更的种子片段**（即 diff 行所在片段），而非途经的中间片段——
     * 便于排查直接定位改动点。同一语句可达多个种子时取排序最小者（确定性）；
     * BFS + visited 防循环；种子与站点均按排序遍历。</p>
     */
    public Map<String, String> closureOf(Set<String> changedFragmentKeys) {
        Map<String, String> affected = new TreeMap<String, String>();
        Deque<String[]> queue = new ArrayDeque<String[]>(); // [当前片段, 根因种子]
        for (String seed : new TreeSet<String>(changedFragmentKeys)) {
            queue.add(new String[]{seed, seed});
        }
        Set<String> visited = new HashSet<String>();
        while (!queue.isEmpty()) {
            String[] entry = queue.poll();
            String fragmentKey = entry[0];
            String seed = entry[1];
            if (!visited.add(fragmentKey)) {
                continue;
            }
            List<IncludeSite> sites = references.get(fragmentKey);
            if (sites == null) {
                continue;
            }
            List<IncludeSite> sortedSites = new ArrayList<IncludeSite>(sites);
            Collections.sort(sortedSites, Comparator.comparing(IncludeSite::file).thenComparingInt(IncludeSite::line));
            for (IncludeSite site : sortedSites) {
                if (site.ownerKind() == IncludeSite.OwnerKind.STATEMENT) {
                    if (!affected.containsKey(site.ownerKey())) {
                        affected.put(site.ownerKey(), seed); // 来源 = 根因变更片段
                    }
                } else {
                    queue.add(new String[]{site.ownerKey(), seed}); // 片段 → 上级片段（传递传播）
                }
            }
        }
        return affected;
    }

    // ---------- 单趟 SAX 收集 ----------

    private static final class RawInclude {
        final String file;
        final int line;
        final String refidRaw;
        final String ownerNamespace;
        final Owner owner; // nullable

        RawInclude(String file, int line, String refidRaw, String ownerNamespace, Owner owner) {
            this.file = file;
            this.line = line;
            this.refidRaw = refidRaw;
            this.ownerNamespace = ownerNamespace;
            this.owner = owner;
        }
    }

    private static final class Owner {
        final IncludeSite.OwnerKind kind;
        final String key;

        Owner(IncludeSite.OwnerKind kind, String key) {
            this.kind = kind;
            this.key = key;
        }
    }

    private static final class IndexHandler extends DefaultHandler {

        private static final int K_OTHER = 0;
        private static final int K_STATEMENT = 1;
        private static final int K_FRAGMENT = 2;

        private final String file;
        private final Deque<Frame> stack = new ArrayDeque<Frame>();
        final List<StatementSite> statements = new ArrayList<StatementSite>();
        final List<FragmentDef> fragments = new ArrayList<FragmentDef>();
        final List<RawInclude> includes = new ArrayList<RawInclude>();
        private Locator locator;
        private String namespace = "";

        IndexHandler(String file) {
            this.file = file;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            int line = locator.getLineNumber();
            if ("mapper".equals(qName)) {
                String ns = attrs.getValue("namespace");
                namespace = ns == null ? "" : ns.trim();
                push("mapper", K_OTHER, null, line);
                return;
            }
            if (STATEMENT_TAGS.contains(qName)) {
                String id = attrs.getValue("id");
                if (id != null && !id.trim().isEmpty()) {
                    push(qName, K_STATEMENT, namespace + SEP_STATEMENT + id.trim(), line);
                } else {
                    push(qName, K_OTHER, null, line);
                }
                return;
            }
            if ("sql".equals(qName)) {
                String id = attrs.getValue("id");
                if (id != null && !id.trim().isEmpty()) {
                    push(qName, K_FRAGMENT, namespace + SEP_FRAGMENT + id.trim(), line);
                } else {
                    push(qName, K_OTHER, null, line);
                }
                return;
            }
            if ("include".equals(qName)) {
                String refid = attrs.getValue("refid");
                includes.add(new RawInclude(file, line, refid == null ? "" : refid.trim(),
                        namespace, currentOwner()));
                push(qName, K_OTHER, null, line);
                return;
            }
            push(qName, K_OTHER, null, line);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            int line = locator.getLineNumber(); // spike 验证：= 闭合标签行
            Frame frame = stack.isEmpty() ? null : stack.pop();
            if (frame == null) {
                return;
            }
            if (frame.kind == K_STATEMENT) {
                int dot = frame.key.lastIndexOf(SEP_STATEMENT);
                statements.add(new StatementSite(frame.key.substring(0, dot),
                        frame.key.substring(dot + 1), frame.name, file, frame.startLine, line));
            } else if (frame.kind == K_FRAGMENT) {
                int hash = frame.key.lastIndexOf(SEP_FRAGMENT);
                fragments.add(new FragmentDef(frame.key.substring(0, hash),
                        frame.key.substring(hash + 1), file, frame.startLine, line));
            }
        }

        private void push(String name, int kind, String key, int line) {
            stack.push(new Frame(name, kind, key, line));
        }

        /** 最近的语句/片段祖先（include 的引用归属）。 */
        private Owner currentOwner() {
            for (Frame frame : stack) {
                if (frame.kind == K_STATEMENT) {
                    return new Owner(IncludeSite.OwnerKind.STATEMENT, frame.key);
                }
                if (frame.kind == K_FRAGMENT) {
                    return new Owner(IncludeSite.OwnerKind.FRAGMENT, frame.key);
                }
            }
            return null;
        }

        private static final class Frame {
            final String name;
            final int kind;
            final String key;
            final int startLine;

            Frame(String name, int kind, String key, int startLine) {
                this.name = name;
                this.kind = kind;
                this.key = key;
                this.startLine = startLine;
            }
        }
    }
}
