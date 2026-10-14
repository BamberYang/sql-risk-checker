package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;

import java.util.Locale;

/**
 * 测试替身方言（T11.2 验收）：验证"实现 {@link SqlDialect} → ServiceLoader 注册（test classpath 服务文件）
 * → 配置校验放开 → 解析/事实可用"的完整扩展路径。
 *
 * <p>模拟 PostgreSQL 形态：Druid {@code DbType.postgresql}；分页识别 {@code FETCH FIRST n ROWS ONLY}。</p>
 */
public final class TestDialect implements SqlDialect {

    @Override
    public String name() {
        return "test-dialect";
    }

    @Override
    public DbType dbType() {
        return DbType.postgresql;
    }

    @Override
    public boolean hasRowLimit(SQLStatement ast) {
        return ast instanceof SQLSelectStatement
                && ast.toString().toUpperCase(Locale.ROOT).contains("FETCH FIRST");
    }
}
