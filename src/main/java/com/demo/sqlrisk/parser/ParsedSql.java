package com.demo.sqlrisk.parser;

import com.alibaba.druid.sql.ast.SQLStatement;

import java.util.Objects;

/** 单骨架解析产物（详细设计 §10.6）：骨架 → 归一化 → Druid AST → 语句事实。 */
public final class ParsedSql {

    private final SqlSkeleton skeleton;
    private final NormalizedSql normalized;
    private final SQLStatement ast;
    private final QueryFacts facts;

    public ParsedSql(SqlSkeleton skeleton, NormalizedSql normalized, SQLStatement ast, QueryFacts facts) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.normalized = Objects.requireNonNull(normalized, "normalized");
        this.ast = Objects.requireNonNull(ast, "ast");
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    public SqlSkeleton skeleton() {
        return skeleton;
    }

    public NormalizedSql normalized() {
        return normalized;
    }

    public SQLStatement ast() {
        return ast;
    }

    public QueryFacts facts() {
        return facts;
    }
}
