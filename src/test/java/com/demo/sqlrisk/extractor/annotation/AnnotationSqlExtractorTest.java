package com.demo.sqlrisk.extractor.annotation;

import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.diff.Hunk;
import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.DynamicXmlText;
import com.demo.sqlrisk.extractor.ExtractContext;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.DiffType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.util.JavaSourceIndex;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5.3 自测：注解识别（字面量/数组/常量/script/Provider）、FULL 与 DIFF 语义、SKIP 分支。 */
class AnnotationSqlExtractorTest {

    private static final String MAPPER_PATH = "src/main/java/com/demo/mapper/OrderMapper.java";

    private static final String MAPPER =
            "package com.demo.mapper;\n"                                                // 1
                    + "\n"                                                              // 2
                    + "public interface OrderMapper {\n"                                // 3
                    + "    @Select(\"SELECT id FROM t_order WHERE id = #{id}\")\n"      // 4
                    + "    Long findById(Long id);\n"                                   // 5
                    + "\n"                                                              // 6
                    + "    @Select({\"SELECT id\", \"FROM t_order\"})\n"                // 7
                    + "    Long findAll();\n"                                           // 8
                    + "\n"                                                              // 9
                    + "    @Select(SqlConsts.FIND_USER)\n"                              // 10
                    + "    Long findUser();\n"                                          // 11
                    + "\n"                                                              // 12
                    + "    @Select(\"<script>SELECT id FROM t_order <if test='id != null'>WHERE id = #{id}</if></script>\")\n" // 13
                    + "    Long findDynamic(Long id);\n"                                // 14
                    + "\n"                                                              // 15
                    + "    List<Long> noSql(Long id);\n"                                // 16
                    + "}\n";                                                            // 17

    private static final String CONSTS_PATH = "src/main/java/com/demo/mapper/SqlConsts.java";
    private static final String CONSTS = "package com.demo.mapper;\n"
            + "public final class SqlConsts {\n"
            + "    public static final String FIND_USER = \"SELECT id FROM t_user\";\n"
            + "}\n";

    private static ExtractContext context() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put(MAPPER_PATH, MAPPER);
        sources.put(CONSTS_PATH, CONSTS);
        return new ExtractContext(null, null, JavaSourceIndex.build(sources), null);
    }

    private static FileChange change(ChangeParams params) {
        TreeSet<Integer> added = new TreeSet<Integer>(params.added);
        TreeSet<Integer> deleted = new TreeSet<Integer>(params.deleted);
        int anchor = added.isEmpty() && !deleted.isEmpty() ? deleted.first() : added.first();
        Hunk hunk = new Hunk(0, 0, anchor, 0, added, deleted);
        return new FileChange(MAPPER_PATH, DiffType.MODIFY, Collections.singletonList(hunk));
    }

    private static final class ChangeParams {
        final List<Integer> added;
        final List<Integer> deleted;

        ChangeParams(List<Integer> added, List<Integer> deleted) {
            this.added = added;
            this.deleted = deleted;
        }
    }

    private static ExtractedStatement find(List<ExtractedStatement> statements, String id) {
        for (ExtractedStatement statement : statements) {
            if (statement.statementId().equals(id)) {
                return statement;
            }
        }
        throw new AssertionError("缺少语句: " + id);
    }

    @Test
    void full_mode_extracts_all_annotated_methods() {
        ExtractResult result = new AnnotationSqlExtractor().extract(
                CandidateFile.of(MAPPER_PATH, MAPPER), context());

        assertEquals(4, result.statements().size(), "noSql 方法无目标注解，不提取");
        assertTrue(result.skipped().isEmpty());

        ExtractedStatement findById = find(result.statements(), "com.demo.mapper.OrderMapper.findById");
        assertEquals(StatementSource.ANNOTATION, findById.source());
        assertEquals(ChangeType.FULL, findById.changeType());
        assertEquals(4, findById.line());
        assertEquals("SELECT id FROM t_order WHERE id = #{id}", findById.rawText());
        assertEquals("SELECT id FROM t_order WHERE id = #{id}",
                ((AnnotationSqlText) findById.carrier()).sqlText());

        assertEquals("SELECT id\nFROM t_order",
                ((AnnotationSqlText) find(result.statements(), "com.demo.mapper.OrderMapper.findAll")
                        .carrier()).sqlText(), "数组拼接");
        assertEquals("SELECT id FROM t_user",
                ((AnnotationSqlText) find(result.statements(), "com.demo.mapper.OrderMapper.findUser")
                        .carrier()).sqlText(), "静态常量解析");

        ExtractedStatement dynamic = find(result.statements(), "com.demo.mapper.OrderMapper.findDynamic");
        assertTrue(dynamic.carrier() instanceof DynamicXmlText, "<script> 转动态载体（同 mapper.xml 管道）");
        assertEquals("com.demo.mapper.OrderMapper", ((DynamicXmlText) dynamic.carrier()).ownerNamespace());
    }

    @Test
    void diff_mode_uses_method_declaration_span() {
        AnnotationSqlExtractor extractor = new AnnotationSqlExtractor();

        // 变更行 5（findById 方法体内）→ 仅 findById MODIFIED
        ExtractResult modified = extractor.extract(
                CandidateFile.of(MAPPER_PATH, MAPPER, DiffType.MODIFY,
                        change(new ChangeParams(Arrays.asList(5), Arrays.asList(5)))),
                context());
        assertEquals(1, modified.statements().size());
        assertEquals("com.demo.mapper.OrderMapper.findById", modified.statements().get(0).statementId());
        assertEquals(ChangeType.MODIFIED, modified.statements().get(0).changeType());

        // 行 1（package 行）不在任何方法范围 → 无提取
        ExtractResult none = extractor.extract(
                CandidateFile.of(MAPPER_PATH, MAPPER, DiffType.MODIFY,
                        change(new ChangeParams(Arrays.asList(1), Arrays.asList(1)))),
                context());
        assertTrue(none.statements().isEmpty());

        // 方法范围整体新增（4、5 行全部新增）→ ADDED
        ExtractResult added = extractor.extract(
                CandidateFile.of(MAPPER_PATH, MAPPER, DiffType.MODIFY,
                        change(new ChangeParams(Arrays.asList(4, 5), Arrays.asList(4, 5)))),
                context());
        assertEquals(ChangeType.ADDED, find(added.statements(), "com.demo.mapper.OrderMapper.findById").changeType());
    }

    @Test
    void unresolvable_constant_yields_skip() {
        // 无源码索引：SqlConsts.FIND_USER 无法解析 → 该语句 SKIP，其余不受影响
        ExtractContext bare = new ExtractContext(null, null, null, null);
        ExtractResult result = new AnnotationSqlExtractor().extract(CandidateFile.of(MAPPER_PATH, MAPPER), bare);

        assertEquals(3, result.statements().size());
        assertEquals(1, result.skipped().size());
        SkippedItem skipped = result.skipped().get(0);
        assertEquals("com.demo.mapper.OrderMapper.findUser", skipped.statementId());
        assertEquals(SkippedItem.Kind.UNSUPPORTED_CARRIER, skipped.kind());
    }

    @Test
    void unresolvable_provider_yields_skip() {
        String mapper = "package com.demo.mapper;\n"
                + "public interface UserMapper {\n"
                + "    @SelectProvider(type = SqlProvider.class, method = \"provide\")\n"
                + "    Long find(String name);\n"
                + "}\n";
        String provider = "package com.demo.mapper;\n"
                + "public class SqlProvider {\n"
                + "    public String provide(String arg) { return decide(arg); }\n"
                + "}\n";
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put(MAPPER_PATH, mapper);
        sources.put(CONSTS_PATH, provider);
        ExtractContext ctx = new ExtractContext(null, null, JavaSourceIndex.build(sources), null);

        ExtractResult result = new AnnotationSqlExtractor().extract(CandidateFile.of(MAPPER_PATH, mapper), ctx);
        assertTrue(result.statements().isEmpty());
        assertEquals(1, result.skipped().size());
        assertEquals("无法静态解析 Provider 方法", result.skipped().get(0).reason());
    }

    @Test
    void broken_java_source_yields_parse_failed_skip() {
        String broken = "class {";
        ExtractResult result = new AnnotationSqlExtractor().extract(
                CandidateFile.of(MAPPER_PATH, broken), new ExtractContext(null, null, null, null));
        assertTrue(result.statements().isEmpty());
        assertEquals(1, result.skipped().size());
        assertEquals(SkippedItem.Kind.PARSE_FAILED, result.skipped().get(0).kind());
    }
}
