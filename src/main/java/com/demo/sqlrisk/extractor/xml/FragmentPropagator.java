package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.diff.ChangeSet;
import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.extractor.ChangeClassifier;
import com.demo.sqlrisk.extractor.StatementSelection;
import com.demo.sqlrisk.model.ChangeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 差异 → 生效语句选择（详细设计 §9.4、决策 D20/DD-9）：
 * 直接变更（语句范围 ∩ diff）∪ 片段派生（&lt;sql&gt; 变更经 {@link XmlFragmentIndex#closureOf} 传播，
 * 含传递与跨文件），直接变更优先（派生命中 changedVia=null）。
 */
public final class FragmentPropagator {

    private FragmentPropagator() {
    }

    /** FULL 模式：提交树内全部语句（changeType=FULL）。 */
    public static List<StatementSelection> allStatements(XmlFragmentIndex index) {
        return index.allStatements().stream()
                .map(site -> new StatementSelection(site.key(), site.namespace(), site.file(),
                        site.startLine(), site.endLine(), ChangeType.FULL, null))
                .collect(Collectors.toList());
    }

    /** DIFF 模式：直接变更 ∪ 片段派生。 */
    public static List<StatementSelection> compute(ChangeSet changeSet, XmlFragmentIndex index) {
        Map<String, StatementSelection> direct = new LinkedHashMap<String, StatementSelection>();
        Set<String> changedFragments = new TreeSet<String>();
        for (FileChange change : changeSet.files()) {
            collectDirect(change, index, direct);
            collectChangedFragments(change, index, changedFragments);
        }

        Map<String, StatementSelection> merged = new LinkedHashMap<String, StatementSelection>(direct);
        addDerived(index, changedFragments, merged);

        List<StatementSelection> out = new ArrayList<StatementSelection>(merged.values());
        Collections.sort(out, Comparator.comparing(StatementSelection::file)
                .thenComparingInt(StatementSelection::startLine));
        return out;
    }

    /** 直接变更：语句范围 ∩ diff。 */
    private static void collectDirect(FileChange change, XmlFragmentIndex index,
                                      Map<String, StatementSelection> direct) {
        if (!change.path().endsWith(".xml")) {
            return;
        }
        for (XmlFragmentIndex.StatementSite site : index.statementsOfFile(change.path())) {
            Optional<ChangeType> type = ChangeClassifier.classify(change, site.startLine(), site.endLine());
            if (type.isPresent()) {
                direct.put(site.key(), new StatementSelection(site.key(), site.namespace(), site.file(),
                        site.startLine(), site.endLine(), type.get(), null));
            }
        }
    }

    /** 片段变更收集（ADDED 文件：语句已整体新增；片段变更仅对既有文件传播）。 */
    private static void collectChangedFragments(FileChange change, XmlFragmentIndex index,
                                                Set<String> changedFragments) {
        if (!change.path().endsWith(".xml") || change.isAddedLike()) {
            return;
        }
        for (XmlFragmentIndex.FragmentDef def : index.fragmentsOfFile(change.path())) {
            if (ChangeClassifier.classify(change, def.startLine(), def.endLine()).isPresent()) {
                changedFragments.add(def.key());
            }
        }
    }

    /** 片段派生语句并入（DD-9：直接变更优先；缺失站点防御跳过）。 */
    private static void addDerived(XmlFragmentIndex index, Set<String> changedFragments,
                                   Map<String, StatementSelection> merged) {
        if (changedFragments.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : index.closureOf(changedFragments).entrySet()) {
            Optional<XmlFragmentIndex.StatementSite> site = index.statement(entry.getKey());
            if (merged.containsKey(entry.getKey()) || !site.isPresent()) {
                continue; // DD-9：直接变更优先；站点缺失（不应发生）防御跳过
            }
            merged.put(entry.getKey(), new StatementSelection(site.get().key(), site.get().namespace(),
                    site.get().file(), site.get().startLine(), site.get().endLine(),
                    ChangeType.MODIFIED, entry.getValue()));
        }
    }
}
