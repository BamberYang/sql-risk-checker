package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.config.AppConfig;
import com.demo.sqlrisk.metadata.MetadataProvider;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.parser.ParsedSql;
import com.demo.sqlrisk.parser.QueryFacts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 规则执行上下文（详细设计 §11.1）：规则唯一读取的输入（原则 P6，不感知 diff/extractor）。 */
public final class RuleContext {

    private final ParsedSql parsedSql; // nullable：语句级短路规则（M8）不依赖骨架
    private final StatementMeta meta;
    private final MetadataProvider metadata; // nullable：缺失时元数据类规则按"无索引"保守判定
    private final AppConfig config;          // nullable
    private final String rawText;            // 语句原文（M8/L1 用，含被丢弃分支）
    private final List<String> wrapperSqlParams; // §9.5-6：M8 排除项（Wrapper 运行期条件段）

    public RuleContext(ParsedSql parsedSql, StatementMeta meta, MetadataProvider metadata,
                       AppConfig config, String rawText) {
        this(parsedSql, meta, metadata, config, rawText, Collections.<String>emptyList());
    }

    public RuleContext(ParsedSql parsedSql, StatementMeta meta, MetadataProvider metadata,
                       AppConfig config, String rawText, List<String> wrapperSqlParams) {
        this.parsedSql = parsedSql;
        this.meta = Objects.requireNonNull(meta, "meta");
        this.metadata = metadata;
        this.config = config;
        this.rawText = Objects.requireNonNull(rawText, "rawText");
        this.wrapperSqlParams = Collections.unmodifiableList(new ArrayList<String>(
                Objects.requireNonNull(wrapperSqlParams, "wrapperSqlParams")));
    }

    /** 可为 null（语句级短路规则）。 */
    public ParsedSql parsedSql() {
        return parsedSql;
    }

    /** 可为 null（语句级短路规则无骨架事实）。 */
    public QueryFacts facts() {
        return parsedSql == null ? null : parsedSql.facts();
    }

    public StatementMeta meta() {
        return meta;
    }

    /** 可为 null。 */
    public MetadataProvider metadata() {
        return metadata;
    }

    /** 可为 null。 */
    public AppConfig config() {
        return config;
    }

    /** 语句原文（含被丢弃分支，D8）。 */
    public String rawText() {
        return rawText;
    }

    /** 注解 SQL 中解析到 Wrapper 家族参数的 ${} 根名（§9.5-6；M8 判定的排除项），空=无。 */
    public List<String> wrapperSqlParams() {
        return wrapperSqlParams;
    }
}
