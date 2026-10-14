package com.demo.sqlrisk.metadata;

import java.util.Optional;

/**
 * 表元数据来源（详细设计 §7，SPI 预留：后续可替换为 DbMetadataProvider / DictMetadataProvider）。
 *
 * <p>语义：表名匹配大小写不敏感；未命中返回 {@link Optional#empty()}，
 * 表示"该表不在元数据中 = 无任何索引"（M1/M6 按未命中索引判定，宁可误报）。</p>
 */
public interface MetadataProvider {

    Optional<TableMetadata> find(String tableName);
}
