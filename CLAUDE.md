# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

SQL 上线风险静态检查工具：Java 8 CLI，基于 Git diff 仅提取本次变更涉及的 MyBatis SQL（mapper.xml / 注解 / MyBatis-Plus Lambda），按三级规则做静态风险分析，输出控制台摘要 + JSON 报告。

**设计三件套是唯一事实来源（`docs/`），实现与其冲突时以文档为准（或先改文档）**：

| 文档   | 版本   | 用途                                                    |
| ---- | ---- | ----------------------------------------------------- |
| 需求文档 | v1.0 | FR / AC / 边界                                          |
| 概要设计 | v1.7 | 架构、模块、决策 D1~D23（均已确认）                                 |
| 详细设计 | v1.2 | 编码直接依据：类结构、方法签名、算法伪码、规则详设、测试用例（DD-1~DD-12）             |

实现已完成（M0~M9；263 用例全绿）。改动前先读详细设计对应章节，并保持文档与代码同步（实现偏差 → 回写文档，见详设 v1.2 变更记录）。

## 常用命令

```bash
mvn -o clean verify                                        # 离线构建 + 全部测试（不访问网络是产品要求的一部分）
mvn -o test -Dtest=H1RuleTest                              # 单个测试类
mvn -o test -Dtest=H1RuleTest#methodName                   # 单个测试方法
mvn -o clean package                                       # 产出 target/sql-risk-checker-shaded.jar
java -jar target/sql-risk-checker-shaded.jar --repo <path> --prod-commit <sha> --metadata <path>
```

## 架构要点

处理管线（P0~P9）：参数装配 → 配置/元数据 → diff（FULL 模式遍历 head 提交树）→ 语句提取（xml/注解/Lambda）→ 白名单 → 骨架生成（保守求值）→ Druid 解析 → 规则引擎 → 汇总 → 报告。三种承载形态统一收敛为「SQL 骨架文本」后走同一解析与规则管道（原则 P1）。

改动前必须知道的硬约束（跨文档才能看全，容易被违背）：

- **Java 8 语法基线**：禁用 var/record/switch 表达式/List.of 等新语法；模型类不可变、公共方法不返回 null（空集合 + Optional）。
- **保守策略固定启用、无开关**：`<if>` 恒视为 FALSE；`<choose>` 生成 when/otherwise 两份骨架，任一骨架命中即停止后续解析与判定；Lambda 侧同构——wrapper 变量在 if/else、switch/case 中追加条件按分支枚举骨架（对齐 `<choose>`），无 else 的 if / 循环体保守丢弃（对齐 `<if>`，详设 §9.7/§10.3）。
- **数据源恒为提交树**：DIFF 与 FULL 两模式都只读已提交内容，工作区文件永不读取（D21/D22）。
- **增量语义**：行级命中即提取完整语句；`<sql>` 片段改动经 `XmlFragmentIndex` 反向闭包传播到全部（含传递、跨文件）引用语句并记 `changedVia`（D20）；Lambda 语句范围含 wrapper 执行调用行。
- **风险归类与退出码**：HIGH/MEDIUM/LOW + SKIP（解析失败等入 SKIP，不影响退出码）；退出码 0 无高危 / 1 有高危 / 2 参数或环境错误。
- **规则集固定**：H1~H3、M1~M6、M8、L1/L2/L4；L3、M7 编号保留不复用。M8 为语句级短路规则（扫语句**原文**，含被丢弃分支，优先级 -100 先行），**排除根名解析到 Wrapper 参数的 `${…}`**（如 `${ew.customSqlSegment}`，详设 §9.5-6/§10.6）；**空骨架（含 `${}` 解析失败）未命中短路规则 → SKIP**（不输出为无风险结果，§11.2-④ v1.6）。规则优先级/短路标记硬编码，仅 level/enabled 可配置。
- **范围边界**：普通 QueryWrapper（字符串列名链式）与 BaseMapper 内置方法不解析（D23）；链式方法表中未收录的方法 → 忽略该条件并标 `hasUnknownFragment`，不猜测语义（DD-8）。
- **报告契约**：需求 §10.2 字段 + 追加字段（mode/source/uncertain/changedVia/skeleton、changeType:FULL，D5 已确认）；口径：total=results.length，提取总数=total+skip+ignored。`--output` 默认双产出：JSON 为机器契约（黄金样例基准），HTML 为阅读层（§12.5，不参与黄金比对）。
- **元数据为必需输入**：缺失（含默认路径仓库根 `sql-risk-metadata.json`）或格式错误 → exit 2；表不在元数据中 = 无索引（M1 保守命中）。

模块依赖硬约束（概要设计 §3.3）：`cli → core → 其余`；`rule` 不得依赖 `diff`/`extractor`（规则只读 QueryFacts + 上下文）；`model` 零依赖。跨模块 SPI：`ProductionCommitProvider`（diff）、`MetadataProvider`（metadata）、`RuleProvider`（rule，ServiceLoader 扩展）。

## 技术栈（版本已锁定，概要设计 §3.5）

Java 8 · JGit 5.13.x（6.x 需 Java 11）· Druid 1.2.x · SnakeYAML 1.33 · JavaParser 3.25.x · Gson 2.10.1 · mybatis-plus-core 3.5.7 · slf4j-nop · JUnit 5.10。XML 解析用 JDK SAX + Locator（不用 dom4j，决策 D3）。

## 测试约定

- 黄金样例驱动：夹具仓库由 `FixtureRepoBuilder`（JGit）程序化构造，固定 `Clock` 注入；报告与 `src/test/resources/cases/<id>/expected/report.json` 全量比对。用例清单见详细设计 §18.3（ac01~ac11、fragment-propagation、skip 等）。
- 每条内置规则测试须含 正例 / 反例 / 边界 三类用例（详细设计 §18.4）。
- 确定性要求：同一输入（固定时钟）产生逐字节一致的报告；所有输出集合按 `(file, line, statementId)` 排序后进入汇总。
