package com.demo.sqlrisk.testutil;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.config.DataSourceSetting;
import com.demo.sqlrisk.config.RuleSetting;
import com.demo.sqlrisk.extractor.AnnotationSqlText;
import com.demo.sqlrisk.metadata.FieldMeta;
import com.demo.sqlrisk.metadata.IndexMeta;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.metadata.TableMetadata;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.parser.DroppedBranch;
import com.demo.sqlrisk.parser.DruidSqlParser;
import com.demo.sqlrisk.parser.NormalizedSql;
import com.demo.sqlrisk.parser.ParseContext;
import com.demo.sqlrisk.parser.ParseOutcome;
import com.demo.sqlrisk.parser.ParsedSql;
import com.demo.sqlrisk.parser.ParsedStatement;
import com.demo.sqlrisk.parser.PlaceholderNormalizer;
import com.demo.sqlrisk.parser.QueryFactsBuilder;
import com.demo.sqlrisk.parser.SqlSkeleton;
import com.demo.sqlrisk.parser.StatementParser;
import com.demo.sqlrisk.rule.RuleContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** M7 规则测试共用工具：真实解析/事实提取构造 RuleContext、元数据桩、配置桩。 */
public final class RuleTestSupport {

    private RuleTestSupport() {
    }

    public static RuleContext ctx(String sql) {
        return ctx(sql, null, null);
    }

    public static RuleContext ctx(String sql, MetadataProvider metadata, AppConfig config) {
        return build(parsedSql(sql, metadata, SqlSkeleton.base(sql)), sql, metadata, config);
    }

    /** 带指定角色丢弃溯源的上下文（H1/H2/H3 提示附注测试）。 */
    public static RuleContext ctxWithDropped(String sql, DroppedBranch.Role role) {
        SqlSkeleton skeleton = new SqlSkeleton(sql, Collections.singletonList(
                new DroppedBranch(DroppedBranch.BranchType.IF, DroppedBranch.Reason.CONSERVATIVE_FALSE,
                        role, "AND x = 1")),
                SqlSkeleton.TAG_BASE, false, false, false, false);
        return build(parsedSql(sql, null, skeleton), sql, null, null);
    }

    /** 语句级上下文（parsedSql 为 null，M8 测试用）。 */
    public static RuleContext statementCtx(String rawText) {
        return new RuleContext(null, meta(rawText), null, null, rawText);
    }

    /** 语句级上下文 + Wrapper SQL 段根名（§9.5-6，M8 排除项测试用）。 */
    public static RuleContext statementCtx(String rawText, List<String> wrapperSqlParams) {
        return new RuleContext(null, meta(rawText), null, null, rawText, wrapperSqlParams);
    }

    /** 经真实解析编排（StatementParser）生成语句（引擎测试用）。 */
    public static ParsedStatement parsedStatement(String sql) {
        ExtractedStatement statement = new ExtractedStatement("m.s", StatementSource.ANNOTATION,
                ChangeType.FULL, "A.java", 1, sql, new AnnotationSqlText(sql), null);
        ParseOutcome outcome = StatementParser.parse(statement, new ParseContext(null, null, null));
        if (outcome.parsed() == null) {
            throw new AssertionError("测试语句解析失败: " + sql + " → " + outcome.skipped().reason());
        }
        return outcome.parsed();
    }

    public static ParsedStatement parsedStatement(List<ParsedSql> parsedSqls, String rawText) {
        ExtractedStatement statement = new ExtractedStatement("m.s", StatementSource.MAPPER_XML,
                ChangeType.FULL, "A.xml", 1, rawText, "carrier", null);
        return new ParsedStatement(statement, parsedSqls);
    }

    public static ParsedSql parsedSql(String sql, MetadataProvider metadata, SqlSkeleton skeleton) {
        NormalizedSql normalized = PlaceholderNormalizer.normalize(skeleton.text());
        com.alibaba.druid.sql.ast.SQLStatement ast = DruidSqlParser.parse(normalized.text());
        return new ParsedSql(skeleton, normalized, ast,
                QueryFactsBuilder.build(ast, metadata, skeleton));
    }

    /** t_order：PRIMARY(id) + idx_user_id(user_id)；其余表不在元数据。 */
    public static MetadataProvider orderMetadata() {
        Map<String, FieldMeta> fields = new LinkedHashMap<String, FieldMeta>();
        fields.put("id", new FieldMeta("id", "BIGINT"));
        fields.put("user_id", new FieldMeta("user_id", "BIGINT"));
        fields.put("order_no", new FieldMeta("order_no", "VARCHAR(64)"));
        fields.put("status", new FieldMeta("status", "INT"));
        final TableMetadata tOrder = new TableMetadata("t_order", fields, Arrays.asList(
                new IndexMeta("PRIMARY", Arrays.asList("id")),
                new IndexMeta("idx_user_id", Arrays.asList("user_id"))));
        return new MetadataProvider() {
            @Override
            public Optional<TableMetadata> find(String tableName) {
                return "t_order".equalsIgnoreCase(tableName) ? Optional.of(tOrder) : Optional.empty();
            }
        };
    }

    public static AppConfig config(List<String> keywords, Map<String, RuleSetting> rules) {
        return new AppConfig(rules, new DataSourceSetting("jdbc:h2:mem:rule-support;DB_CLOSE_DELAY=-1",
                "sa", "", 10), "mysql", keywords, Collections.<String>emptyList());
    }

    private static RuleContext build(ParsedSql parsedSql, String rawText,
                                     MetadataProvider metadata, AppConfig config) {
        return new RuleContext(parsedSql, meta(rawText), metadata, config, rawText);
    }

    private static StatementMeta meta(String rawText) {
        return StatementMeta.from(new ExtractedStatement("m.s", StatementSource.MAPPER_XML,
                ChangeType.FULL, "A.xml", 1, rawText, "carrier", null));
    }
}
