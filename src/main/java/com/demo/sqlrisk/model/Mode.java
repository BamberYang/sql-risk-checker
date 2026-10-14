package com.demo.sqlrisk.model;

/**
 * 运行模式（概要设计 §4.3、决策 D21/D22）：
 * DIFF = 比较 prod 与 head 两个提交树；FULL = 遍历 head 提交树全量。
 * 两个模式的数据源恒为提交树，工作区未提交内容一律不参与。
 */
public enum Mode {
    DIFF,
    FULL
}
