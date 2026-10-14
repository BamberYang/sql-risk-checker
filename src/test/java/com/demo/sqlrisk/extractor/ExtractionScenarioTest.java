package com.demo.sqlrisk.extractor;

import com.demo.sqlrisk.diff.ChangeSet;
import com.demo.sqlrisk.diff.DiffService;
import com.demo.sqlrisk.diff.GitRepo;
import com.demo.sqlrisk.diff.RepoContentReader;
import com.demo.sqlrisk.extractor.lambda.LambdaChainModel;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.testutil.FixtureRepo;
import com.demo.sqlrisk.testutil.FixtureRepoBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 场景测试（需求方要求）：真实夹具仓库 + 真实 diff 驱动完整提取管线
 * （ExtractionService：索引 → 片段传播 → 三类提取器 → 去重排序）。
 *
 * <ul>
 *   <li>ac05b：&lt;sql&gt; 片段变更 → 全部（含传递、跨文件）引用语句 MODIFIED + changedVia；</li>
 *   <li>ac06：注解与 Lambda 语句的真实 diff 提取；</li>
 *   <li>FULL 模式：提交树全量提取。</li>
 * </ul>
 */
class ExtractionScenarioTest {

    @TempDir
    Path tempDir;

    // ---------- ac05b 场景文件 ----------

    private static final String BASE_XML = "<mapper namespace=\"com.demo.mapper.BaseMapper\">\n"
            + "  <sql id=\"baseColumns\">\n"
            + "    id, user_id\n"
            + "  </sql>\n"
            + "</mapper>";

    private static final String ORDER_XML = "<mapper namespace=\"com.demo.mapper.OrderMapper\">\n"
            + "  <select id=\"selectOne\">\n"
            + "    SELECT <include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>\n"
            + "    FROM t_order WHERE id = #{id}\n"
            + "  </select>\n"
            + "</mapper>";

    private static final String USER_XML = "<mapper namespace=\"com.demo.mapper.UserMapper\">\n"
            + "  <sql id=\"composite\">\n"
            + "    <include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>, name\n"
            + "  </sql>\n"
            + "  <select id=\"selectList\">\n"
            + "    SELECT <include refid=\"com.demo.mapper.UserMapper.composite\"/>\n"
            + "    FROM t_user\n"
            + "  </select>\n"
            + "</mapper>";

    // ---------- ac06 场景文件 ----------

    private static final String ENTITY_JAVA = "package com.demo.entity;\n"
            + "public class User { private Long id; private String status; }\n";

    private static final String USER_MAPPER_JAVA = "package com.demo.mapper;\n"
            + "\n"
            + "public interface UserMapper extends BaseMapper<User> {\n"
            + "    @Select(\"SELECT id FROM t_user WHERE status = #{status}\")\n"
            + "    Long selectByStatus(String status);\n"
            + "}\n";

    private static final String USER_MAPPER_JAVA_HEAD = "package com.demo.mapper;\n"
            + "\n"
            + "public interface UserMapper extends BaseMapper<User> {\n"
            + "    @Select(\"SELECT id FROM t_user WHERE status = #{status} AND id > 0\")\n"
            + "    Long selectByStatus(String status);\n"
            + "}\n";

    private static final String USER_SERVICE_JAVA = "package com.demo.service;\n"
            + "\n"
            + "public class UserService {\n"
            + "    private UserMapper userMapper;\n"
            + "\n"
            + "    public Object findByStatus(String status) {\n"
            + "        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();\n"
            + "        w.eq(User::getStatus, status);\n"
            + "        return userMapper.selectList(w);\n"
            + "    }\n"
            + "}\n";

    private static final String USER_SERVICE_JAVA_HEAD = "package com.demo.service;\n"
            + "\n"
            + "public class UserService {\n"
            + "    private UserMapper userMapper;\n"
            + "\n"
            + "    public Object findByStatus(String status) {\n"
            + "        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();\n"
            + "        w.eq(User::getStatus, status);\n"
            + "        w.isNotNull(User::getId);\n"
            + "        return userMapper.selectList(w);\n"
            + "    }\n"
            + "}\n";

    // ---------- 场景 1/2：片段传播 ----------

    @Test
    void scenario_fragment_change_propagates_to_all_referencing_statements() throws Exception {
        Map<String, String> prod = new LinkedHashMap<String, String>();
        prod.put("src/main/resources/mapper/BaseMapper.xml", BASE_XML);
        prod.put("src/main/resources/mapper/OrderMapper.xml", ORDER_XML);
        prod.put("src/main/resources/mapper/UserMapper.xml", USER_XML);
        Map<String, String> head = new LinkedHashMap<String, String>();
        head.put("src/main/resources/mapper/BaseMapper.xml",
                BASE_XML.replace("id, user_id", "id, user_id, status")); // baseColumns 片段定义内改动

        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("fragment"))
                .commit("prod", prod).commit("head", head).finish();
        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            ExtractResult result = runDiff(git, fixture);

            assertEquals(2, result.statements().size(), "片段派生命中（含未变更文件中的语句）");
            assertTrue(result.skipped().isEmpty());

            ExtractedStatement one = byId(result.statements(), "com.demo.mapper.OrderMapper.selectOne");
            assertEquals(ChangeType.MODIFIED, one.changeType());
            assertEquals("com.demo.mapper.BaseMapper#baseColumns", one.changedVia());
            assertTrue(one.rawText().contains("<include refid=\"com.demo.mapper.BaseMapper.baseColumns\"/>"),
                    "整句提取（含 include 原文）");

            ExtractedStatement list = byId(result.statements(), "com.demo.mapper.UserMapper.selectList");
            assertEquals(ChangeType.MODIFIED, list.changeType());
            assertEquals("com.demo.mapper.BaseMapper#baseColumns", list.changedVia(),
                    "经 composite 传递传播，changedVia = 变更片段");
        }
    }

    @Test
    void scenario_direct_change_does_not_touch_other_files() throws Exception {
        Map<String, String> prod = new LinkedHashMap<String, String>();
        prod.put("src/main/resources/mapper/BaseMapper.xml", BASE_XML);
        prod.put("src/main/resources/mapper/OrderMapper.xml", ORDER_XML);
        prod.put("src/main/resources/mapper/UserMapper.xml", USER_XML);
        Map<String, String> head = new LinkedHashMap<String, String>();
        head.put("src/main/resources/mapper/OrderMapper.xml",
                ORDER_XML.replace("FROM t_order WHERE id = #{id}", "FROM t_order WHERE order_no = #{no}"));

        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("direct"))
                .commit("prod", prod).commit("head", head).finish();
        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            ExtractResult result = runDiff(git, fixture);

            assertEquals(1, result.statements().size(), "仅直接变更语句；未变更文件不提取");
            ExtractedStatement one = result.statements().get(0);
            assertEquals("com.demo.mapper.OrderMapper.selectOne", one.statementId());
            assertEquals(ChangeType.MODIFIED, one.changeType());
            assertNull(one.changedVia(), "直接变更优先（DD-9）");
        }
    }

    // ---------- 场景 3/4：注解 + Lambda ----------

    private static Map<String, String> javaProd() {
        Map<String, String> prod = new LinkedHashMap<String, String>();
        prod.put("src/main/java/com/demo/entity/User.java", ENTITY_JAVA);
        prod.put("src/main/java/com/demo/mapper/UserMapper.java", USER_MAPPER_JAVA);
        prod.put("src/main/java/com/demo/service/UserService.java", USER_SERVICE_JAVA);
        return prod;
    }

    private static Map<String, String> javaHead() {
        Map<String, String> head = new LinkedHashMap<String, String>();
        head.put("src/main/java/com/demo/entity/User.java", ENTITY_JAVA);
        head.put("src/main/java/com/demo/mapper/UserMapper.java", USER_MAPPER_JAVA_HEAD);
        head.put("src/main/java/com/demo/service/UserService.java", USER_SERVICE_JAVA_HEAD);
        return head;
    }

    @Test
    void scenario_annotation_and_lambda_extraction() throws Exception {
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("java"))
                .commit("prod", javaProd()).commit("head", javaHead()).finish();
        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            ExtractResult result = runDiff(git, fixture);

            assertEquals(2, result.statements().size());
            ExtractedStatement annotation =
                    byId(result.statements(), "com.demo.mapper.UserMapper.selectByStatus");
            assertEquals(ChangeType.MODIFIED, annotation.changeType());
            assertTrue(annotation.rawText().contains("AND id > 0"), "注解 SQL 提取为变更后文本");

            String serviceLine = String.valueOf(lineOf(USER_SERVICE_JAVA_HEAD, "new LambdaQueryWrapper<>()"));
            ExtractedStatement lambda = byId(result.statements(),
                    "UserService.java:" + serviceLine);
            assertEquals(ChangeType.MODIFIED, lambda.changeType());
            LambdaChainModel model = (LambdaChainModel) lambda.carrier();
            assertEquals("User", model.entityTypeName(), "钻石泛型 → 变量声明类型 → 实体");
            assertEquals(2, model.calls().size());
            assertEquals("eq", model.calls().get(0).method());
            assertEquals("isNotNull", model.calls().get(1).method());
            assertEquals(lineOf(USER_SERVICE_JAVA_HEAD, "return userMapper.selectList(w);"), model.rangeEndLine(),
                    "执行调用行纳入语句范围（DD-7）");
        }
    }

    @Test
    void scenario_full_mode_extracts_everything_from_tree() throws Exception {
        FixtureRepo fixture = FixtureRepoBuilder.init(tempDir.resolve("full"))
                .commit("prod", javaProd()).commit("head", javaHead()).finish();
        try (GitRepo git = GitRepo.open(fixture.path().toString())) {
            RevCommit head = git.resolveCommit(fixture.sha("head"));
            RepoContentReader reader = git.contentReader(head);
            ExtractResult result = ExtractionService.extract(Mode.FULL, null,
                    load(reader, ".xml"), load(reader, ".java"), null);
            assertEquals(2, result.statements().size());
            for (ExtractedStatement statement : result.statements()) {
                assertEquals(ChangeType.FULL, statement.changeType());
            }
        }
    }

    // ---------- 工具 ----------

    private static ExtractResult runDiff(GitRepo git, FixtureRepo fixture) {
        RevCommit prod = git.resolveCommit(fixture.sha("prod"));
        RevCommit head = git.resolveCommit(fixture.sha("head"));
        ChangeSet changeSet = DiffService.diff(git, prod, head);
        RepoContentReader reader = git.contentReader(head);
        return ExtractionService.extract(Mode.DIFF, changeSet,
                load(reader, ".xml"), load(reader, ".java"), null);
    }

    private static List<CandidateFile> load(RepoContentReader reader, final String suffix) {
        List<CandidateFile> out = new ArrayList<CandidateFile>();
        for (String path : reader.listFiles(p -> p.endsWith(suffix))) {
            out.add(CandidateFile.of(path, reader.readText(path).get()));
        }
        return out;
    }

    private static ExtractedStatement byId(List<ExtractedStatement> statements, String id) {
        for (ExtractedStatement statement : statements) {
            if (statement.statementId().equals(id)) {
                return statement;
            }
        }
        throw new AssertionError("缺少语句: " + id);
    }

    private static int lineOf(String content, String needle) {
        int index = content.indexOf(needle);
        assertTrue(index >= 0, "内容中缺少: " + needle);
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
