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
        List<StatementSelection> out = new ArrayList<StatementSelection>();
        for (XmlFragmentIndex.StatementSite site : index.allStatements()) {
            out.add(new StatementSelection(site.key(), site.namespace(), site.file(),
                    site.startLine(), site.endLine(), ChangeType.FULL, null));
        }
        return out;
    }

    /** DIFF 模式：直接变更 ∪ 片段派生。 */
    public static List<StatementSelection> compute(ChangeSet changeSet, XmlFragmentIndex index) {
        Map<String, StatementSelection> direct = new LinkedHashMap<String, StatementSelection>();
        Set<String> changedFragments = new TreeSet<String>();

        for (FileChange change : changeSet.files()) {
            if (!change.path().endsWith(".xml")) {
                continue;
            }
            for (XmlFragmentIndex.StatementSite site : index.statementsOfFile(change.path())) {
                Optional<ChangeType> type = ChangeClassifier.classify(change, site.startLine(), site.endLine());
                if (type.isPresent()) {
                    direct.put(site.key(), new StatementSelection(site.key(), site.namespace(), site.file(),
                            site.startLine(), site.endLine(), type.get(), null));
                }
            }
            if (!change.isAddedLike()) { // ADDED 文件：语句已整体新增；片段变更仅对既有文件传播
                for (XmlFragmentIndex.FragmentDef def : index.fragmentsOfFile(change.path())) {
                    if (ChangeClassifier.classify(change, def.startLine(), def.endLine()).isPresent()) {
                        changedFragments.add(def.key());
                    }
                }
            }
        }

        Map<String, StatementSelection> merged = new LinkedHashMap<String, StatementSelection>(direct);
        if (!changedFragments.isEmpty()) {
            for (Map.Entry<String, String> entry : index.closureOf(changedFragments).entrySet()) {
                if (merged.containsKey(entry.getKey())) {
                    continue; // DD-9：直接变更优先
                }
                Optional<XmlFragmentIndex.StatementSite> site = index.statement(entry.getKey());
                if (!site.isPresent()) {
                    continue; // 防御：站点缺失（不应发生）
                }
                merged.put(entry.getKey(), new StatementSelection(site.get().key(), site.get().namespace(),
                        site.get().file(), site.get().startLine(), site.get().endLine(),
                        ChangeType.MODIFIED, entry.getValue()));
            }
        }

        List<StatementSelection> out = new ArrayList<StatementSelection>(merged.values());
        Collections.sort(out, Comparator.comparing(StatementSelection::file)
                .thenComparingInt(StatementSelection::startLine));
        return out;
    }
}
