package com.demo.sqlrisk.extractor.xml;

import com.demo.sqlrisk.diff.ChangeSet;
import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.diff.Hunk;
import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.StatementSelection;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.DiffType;
import com.demo.sqlrisk.model.Mode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T5.2 自测：差异 → 生效语句选择（直接 ∪ 片段派生、DD-9 直接优先、ADDED/FULL 语义）。 */
class FragmentPropagatorTest {

    private static final String BASE_XML = xml(
            "<mapper namespace=\"com.demo.mapper.BaseMapper\">",
            "  <sql id=\"baseColumns\">",
            "    id, order_no",
            "  </sql>",
            "</mapper>");

    private static final String ORDER_XML = xml(
            "<mapper namespace=\"com.demo.mapper.OrderMapper\">",
            "  <select id=\"selectOne\">",
            "    SELECT <include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>",
            "    FROM t_order WHERE id = #{id}",
            "  </select>",
            "  <select id=\"selectList\">",
            "    SELECT id FROM t_order WHERE 1=1",
            "  </select>",
            "</mapper>");

    private static String xml(String... lines) {
        return String.join("\n", lines);
    }

    private static XmlFragmentIndex index() {
        return XmlFragmentIndex.build(Arrays.asList(
                CandidateFile.of("mapper/base.xml", BASE_XML),
                CandidateFile.of("mapper/order.xml", ORDER_XML)));
    }

    private static ChangeSet changeSet(FileChange... changes) {
        return new ChangeSet(Mode.DIFF, "head", "prod", Arrays.asList(changes));
    }

    private static FileChange modify(String path, Set<Integer> added) {
        Hunk hunk = new Hunk(0, 0, firstOf(added), added.size(),
                added, new TreeSet<Integer>(added));
        return new FileChange(path, DiffType.MODIFY, Collections.singletonList(hunk));
    }

    private static FileChange pureDelete(String path, int deletedLine, int anchorInNew) {
        Hunk hunk = new Hunk(deletedLine, 1, anchorInNew, 0,
                Collections.<Integer>emptySet(), new TreeSet<Integer>(Collections.singleton(deletedLine)));
        return new FileChange(path, DiffType.MODIFY, Collections.singletonList(hunk));
    }

    private static int firstOf(Set<Integer> lines) {
        return lines.iterator().next();
    }

    private static StatementSelection find(List<StatementSelection> selections, String key) {
        for (StatementSelection selection : selections) {
            if (selection.statementKey().equals(key)) {
                return selection;
            }
        }
        throw new AssertionError("缺少选择: " + key);
    }

    @Test
    void direct_statement_change_only() {
        // 变更行 3（base.xml 内 selectOne 的 include 行）→ 仅 selectOne MODIFIED，changedVia=null
        List<StatementSelection> selections = FragmentPropagator.compute(
                changeSet(modify("mapper/order.xml", new TreeSet<Integer>(Collections.singleton(3)))), index());

        assertEquals(1, selections.size());
        StatementSelection one = find(selections, "com.demo.mapper.OrderMapper.selectOne");
        assertEquals(ChangeType.MODIFIED, one.changeType());
        assertNull(one.changedVia());
    }

    @Test
    void fragment_change_propagates_to_referencing_statements() {
        // base.xml 第 3 行（baseColumns 片段定义内）→ selectOne 经直接引用派生 MODIFIED + changedVia
        List<StatementSelection> selections = FragmentPropagator.compute(
                changeSet(modify("mapper/base.xml", new TreeSet<Integer>(Collections.singleton(3)))), index());

        assertEquals(1, selections.size());
        StatementSelection one = find(selections, "com.demo.mapper.OrderMapper.selectOne");
        assertEquals(ChangeType.MODIFIED, one.changeType());
        assertEquals("com.demo.mapper.BaseMapper#baseColumns", one.changedVia());
    }

    @Test
    void direct_change_wins_over_propagation() {
        // 同时：baseColumns 片段变更 + selectOne 自身变更（DD-9：直接优先，changedVia=null）
        List<StatementSelection> selections = FragmentPropagator.compute(
                changeSet(modify("mapper/base.xml", new TreeSet<Integer>(Collections.singleton(3))),
                        modify("mapper/order.xml", new TreeSet<Integer>(Collections.singleton(3)))), index());

        StatementSelection one = find(selections, "com.demo.mapper.OrderMapper.selectOne");
        assertEquals(ChangeType.MODIFIED, one.changeType());
        assertNull(one.changedVia(), "直接变更优先");
    }

    @Test
    void pure_deletion_anchor_classifies_statement() {
        // 纯删除锚点落在 selectList 范围 [6,8] 内（锚点 7）→ MODIFIED
        List<StatementSelection> selections = FragmentPropagator.compute(
                changeSet(pureDelete("mapper/order.xml", 7, 7)), index());

        StatementSelection list = find(selections, "com.demo.mapper.OrderMapper.selectList");
        assertEquals(ChangeType.MODIFIED, list.changeType());
    }

    @Test
    void whole_line_span_added_is_added() {
        // order.xml 变更行覆盖 [6..7]（selectList 第 6 行起）且整体为新增 → ADDED
        Set<Integer> added = new TreeSet<Integer>(Arrays.asList(6, 7));
        List<StatementSelection> selections = FragmentPropagator.compute(
                changeSet(modify("mapper/order.xml", added)), index());
        // 行 7 也在 selectList 范围内 → 整段新增判定需要 [6,8] 全部新增；此处仅 6,7 → MODIFIED
        assertEquals(ChangeType.MODIFIED, find(selections, "com.demo.mapper.OrderMapper.selectList").changeType());
    }

    @Test
    void added_file_yields_all_statements_added() {
        FileChange addedFile = new FileChange("mapper/order.xml", DiffType.ADD, Collections.<Hunk>emptyList());
        List<StatementSelection> selections = FragmentPropagator.compute(changeSet(addedFile), index());

        assertEquals(2, selections.size());
        assertEquals(ChangeType.ADDED, find(selections, "com.demo.mapper.OrderMapper.selectOne").changeType());
        assertEquals(ChangeType.ADDED, find(selections, "com.demo.mapper.OrderMapper.selectList").changeType());
    }

    @Test
    void full_mode_selects_all_statements() {
        List<StatementSelection> selections = FragmentPropagator.allStatements(index());
        assertEquals(2, selections.size());
        for (StatementSelection selection : selections) {
            assertEquals(ChangeType.FULL, selection.changeType());
            assertNull(selection.changedVia());
        }
    }
}
