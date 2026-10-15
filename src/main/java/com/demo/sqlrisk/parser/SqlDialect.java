package com.demo.sqlrisk.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;

/**
 * SQL 方言 SPI（详细设计 §10.7，v1.8/FR-9/D25）：新增数据库 = 实现本接口 + 注册（ServiceLoader）+ 用例，
 * 核心管线与 13 条规则零改动。
 *
 * <p>内置实现：{@link MysqlDialect}（默认）、{@link PostgresDialect}（v1.10）；Oracle 为预留——实现要点：
 * {@code OracleDialect}：{@code DbType.oracle}，分页识别 {@code FETCH FIRST n ROWS ONLY} 或 WHERE 子树含 {@code ROWNUM}。</p>
 */
public interface SqlDialect {

    /** 配置值（如 "mysql"），须全局唯一。 */
    String name();

    /** Druid 方言映射（解析用）。 */
    DbType dbType();

    /** 分页/限制子句识别（H3/L4 的 hasLimit 事实来源）；无法识别 → false（保守：按无 LIMIT 处理）。 */
    boolean hasRowLimit(SQLStatement ast);
}
