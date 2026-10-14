package com.demo.sqlrisk.model;

/**
 * 文件差异类型（JGit DiffEntry 归一化，详细设计 §8.3）：
 * RENAME 在提取层按「删除+新增」拆分为 DELETE+ADD（决策 D15，仅新增侧参与分析）。
 */
public enum DiffType {
    ADD,
    MODIFY,
    DELETE,
    RENAME
}
