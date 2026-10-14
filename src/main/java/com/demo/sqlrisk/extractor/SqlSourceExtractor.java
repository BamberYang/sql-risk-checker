package com.demo.sqlrisk.extractor;

/**
 * SQL 承载形态提取 SPI（详细设计 §9.1）：面向 Java 源码的两类提取器
 * （{@code AnnotationSqlExtractor} / {@code LambdaSqlExtractor}）。
 *
 * <p>mapper.xml 采用"选择驱动"提取（{@code MapperXmlExtractor}，需全仓语句站点与片段传播信息），
 * 不经本接口（详细设计实现细化，T9.5 回写）。</p>
 */
public interface SqlSourceExtractor {

    /** 提取器名称（诊断用），如 "annotation" / "lambda"。 */
    String name();

    /** 依据扩展名 + 内容特征判定是否由本提取器处理。 */
    boolean supports(CandidateFile file);

    ExtractResult extract(CandidateFile file, ExtractContext ctx);
}
