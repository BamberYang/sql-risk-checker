package com.demo.sqlrisk.model;

/**
 * SQL 承载形态（FR-2）：
 * MAPPER_XML = mapper.xml 语句元素；ANNOTATION = 注解式 SQL（含 &lt;script&gt; 与 Provider）；
 * LAMBDA = MyBatis-Plus Lambda 链式构造（statementId 为「文件名:行号」，不参与白名单）。
 */
public enum StatementSource {
    MAPPER_XML,
    ANNOTATION,
    LAMBDA
}
