package com.demo.sqlrisk.model;

/**
 * 语句变更类型（详细设计 §4.1）：ADDED/MODIFIED 用于 DIFF 模式；
 * FULL 用于全量模式（决策 D5，报告契约的追加值）。
 */
public enum ChangeType {
    ADDED,
    MODIFIED,
    FULL
}
