# 开发任务拆解（TASKS）

> 依据：详细设计 v1.1、概要设计 v1.7。规模估算 S/M/L 仅用于排序参考（S≈半天内，M≈1~2 天，L≈3 天+）。
> **完成定义（DoD）**：`mvn -o clean verify` 通过；任务对应单测齐备（规则类须含 正例/反例/边界 三类）；不破坏既有黄金用例；勾选本文件并标注日期。
> 开发期每完成一个任务建议一个小 commit（`feat(模块): …` / `test(模块): …` / `docs: …`）。

## 里程碑总览

| 里程碑 | 内容                              | 规模 | 依赖                  | 关键验收（AC/用例）        |
| --- | ------------------------------- | -- | ------------------- | ------------------ |
| M0  | 工程与测试基建（含 3 个预研 spike）           | S  | —                   | 空工程离线构建通过           |
| M1  | model + util 打底                 | S  | M0                  | 纯单测                |
| M2  | cli + core 骨架（空管线端到端跑通）         | M  | M1                  | 空仓库 → 空报告 + exit 0  |
| M3  | config + metadata               | M  | M1（可与 M4 并行）        | ac09 配置/元数据分支      |
| M4  | diff（提交树数据源）                    | M  | M1                  | ac01/ac02 数据获取     |
| M5  | extractor（xml/片段/注解/Lambda）     | L  | M4                  | ac05/ac05b/ac06 提取 |
| M6  | parser（骨架/解析/事实）                | L  | M5                  | ac04/ac05 解析       |
| M7  | rule（引擎 + 12 条内置规则）             | M  | M6                  | ac03/ac11          |
| M8  | report + 汇总（白名单/口径/输出）          | M  | M7                  | ac07/ac08          |
| M9  | 端到端黄金用例与收尾                      | M  | M8                  | ac01~ac11 全绿       |

依赖链：`M0 → M1 → {M2, M3, M4}`；`M4 → M5 → M6 → M7 → M8 → M9`。M2 与 M3/M4 可并行；M5、M6、M7 各自内部子任务按序推进。

***

## M0 工程与测试基建 ✅ 2026-10-14（需求方已确认）

- [x] **T0.1**（2026-10-14）离线构建验证：`mvn -o clean verify` 空工程通过；依赖均可从本地仓库解析（首次需在线校准一次来源追踪，之后离线稳定可用）【S】
- [x] **T0.2**（2026-10-14）测试基建：`FixtureRepoBuilder/FixtureRepo`、`GoldenCase/GoldenCaseRunner/GoldenPipeline/CaseInput`、`TestClocks`、`JsonAssertions`（含自测）【S】→ 详细设计 §18.2
- [x] **T0.3**（2026-10-14）三个预研 spike 全部通过（`mvn -o clean verify` 15/15 测试绿）：
  - ✅ SAX `Locator`：endElement 行号 = 闭合标签行（多行元素 3~5、单行 6~6），按行切片语句原文可行；MyBatis DOCTYPE 离线可解析（DD-3 确认）；
  - ✅ Druid：`UPDATE/DELETE ... LIMIT` 的 `getLimit()` 方言 AST 可达；`SELECT *` 需经 `SQLSelectItem.getExpr()` 取 `SQLAllColumnExpr`（M6 编码注意点）；
  - ✅ JavaParser：`extends BaseMapper<T>` / `ServiceImpl<M,T>`、字段与构造泛型实参均可达（`getFields()` 列表 API），显式泛型场景无需符号求解器。

## M1 model + util ✅ 2026-10-14（需求方已确认）

- [x] **T1.1**（2026-10-14）枚举：`Mode/ChangeType/StatementSource/DiffType/RiskLevel`（含 `max/weight`）→ 详设 §4.1
- [x] **T1.2**（2026-10-14）值对象：`ExtractedStatement/StatementMeta/Finding/StatementResult/SkippedItem(IgnoredItem)/Summary/ReportModel`（不可变、集合包装、空值约定）→ 详设 §4.2
- [x] **T1.3**（2026-10-14）util：`LineRanges`（行集合运算）/`TextUtils`/`IoUtils`/`JavaSourceIndex` → 详设 §14
  - 备注：行集合实现采用 `Set<Integer>`（详设 §8.3 原文 `IntSortedSet` 系笔误），M4 `Hunk` 沿用，**已回写（详设 v1.2 §8.3）**
  - 备注：受控异常基类 `SqlRiskException`（§15）放置于 util 包（跨模块共享，避免污染 model 层）

## M2 cli + core 骨架 ✅ 2026-10-14（需求方已确认）

- [x] **T2.1**（2026-10-14）`CliArgs` 手写解析（8 参数 + `--help`）+ `ExitCode` + 参数校验矩阵（缺参/未知/重复/缺值/format 非 json）→ 详设 §5
- [x] **T2.2**（2026-10-14）`RunContext`（mode 判定）+ `AnalysisEngine` 管线占位（P0~P9 顺序 + 并行执行器框架 + 注入时钟）→ 详设 §13
- [x] **T2.3**（2026-10-14）`SqlRiskCheckerMain` 装配（可测试入口 `run()`）+ 顶层异常兜底 + usage 输出 → 详设 §5
- 验收（实物）：空仓库运行 → 空报告 + exit 0；缺参数 → `[错误]` + exit 2；`--help` → 用法；16 个新测试全绿（累计 50）
  - 备注：shade 产物名经 `<outputFile>` 固定为 `target/sql-risk-checker-shaded.jar`（与文档一致）；受控异常基类 `SqlRiskException` 落 util 包

## M3 config + metadata ✅ 2026-10-14（需求方已确认）

- [x] **T3.1**（2026-10-14）`ConfigLoader/AppConfig/RuleSetting/ConfigValidator/ConfigException`：三层合并（默认<用户 yaml<CLI）、rules 按 ruleId 覆盖（level 缺省继承）、已移除/未知键告警忽略、白名单模式校验 → 详设 §6
- [x] **T3.2**（2026-10-14）`JsonMetadataProvider`：加载校验矩阵（缺文件/语法/结构 → exit 2）、重复表名取首条、大小写不敏感、`find` 未命中 = 无索引语义 → 详设 §7
- [x] **T3.3**（2026-10-14）`default-config.yaml` 填充完备（13 条规则默认值 + 关键字表 + 白名单）
- 验收：3 个新测试类 + Engine/Main 测试更新，25 个新用例全绿（累计 75）；ac09 配置/元数据错误分支（CLI 侧）全通
  - 备注：`ConfigLoader.load(configPath, metadataArg, repoPath)` 代替详设示意 `load(CliArgs)`，避免 config 反向依赖 cli（§3.3），**已回写（详设 v1.2 §6）**
  - 备注：测试抓出白名单正则漏 `*` 的 bug（`(\.[A-Za-z_$][\w$])*` → `[\w$]*`）并修复

## M4 diff（提交树数据源）✅ 2026-10-14（需求方已确认）

- [x] **T4.1**（2026-10-14）`GitRepo`（只读打开、ref/完整/缩写 sha 解析、提交树内容读取 + 编码探测）+ `RepoContentReader` → 详设 §8.1/§8.2
- [x] **T4.2**（2026-10-14）`DiffService` + `ChangeSet/FileChange/Hunk`：候选过滤、DELETE 不产出、RENAME 按新增侧（D15）、hunk 行集合与锚点 → 详设 §8.3
- [x] **T4.3**（2026-10-14）`CommitTreeWalker`：遍历 head 提交树 + 排除目录（含嵌套）→ 详设 §8.3
- [x] **T4.4**（2026-10-14）`ProductionCommitProvider` SPI + `CliArgProductionCommitProvider` → 详设 §8.4
- 验收：16 个新用例全绿（累计 91）；P2 接线贯通（报告 head/prod = 解析后 sha；空差异短路；ac09 五类错误分支全集闭合）
  - 备注：实测 JGit 行为——不存在的完整 hex 走 `MissingObjectException`（消息"commit 不存在"）

## M5 extractor ✅ 2026-10-14（需求方已确认，含真实项目全量扫描验证）

- [x] **T5.1**（2026-10-14）`MapperXmlParser`（SAX+XXE 加固+失败隔离）+ `MapperXmlExtractor`（按行范围切片完整原文，D20）→ 详设 §9.2
- [x] **T5.2**（2026-10-14）`XmlFragmentIndex`（站点/片段/反向引用、refid 规范化、闭包传递/跨文件/防循环、未解析可见）+ `FragmentPropagator`（直接优先 DD-9）→ 详设 §9.3/§9.4
- [x] **T5.3**（2026-10-14）`AnnotationSqlExtractor + JavaConstantResolver + ProviderMethodResolver` → 详设 §9.5/§9.6
- [x] **T5.4**（2026-10-14）`LambdaSqlExtractor + EntityIndex`：六种入口、实体解析优先级链、语句范围含执行调用行（DD-7）、布尔重载、参数型 wrapper incomplete（D11）→ 详设 §9.7/§9.8
- [x] **T5.5**（2026-10-14）`ExtractionService` 编排（索引 → 传播 → 三类提取 → 去重排序）
- [x] **T5.6**（2026-10-14）引擎 P3 接线 + `--verbose` 提取清单（主入口端到端可扫描变动 SQL）
- 验收：7 个单元测试类 + **场景测试 4 例**（真实仓库+真实 diff：ac05b 传递/跨文件传播、直接变更、ac06 注解+Lambda、FULL）；31 个新用例（累计 122）
  - 备注：`closureOf` 语义修正——传递传播时 `changedVia` 记录**根因种子片段**（diff 行所在），非途经中间片段
  - 备注：`SqlSourceExtractor` SPI 由两类 Java 提取器实现；XML 为选择驱动独立入口（**已回写，详设 v1.2 §9.1**）
  - **真实项目验证**（union-order-app，HEAD=a32658d8，508 文件）：提取 57 条（Lambda 45 / 注解 2 / XML 10），SKIP 0，**5.8s**；观察到真实 `${ew.customSqlSegment}` 拼串与运行期布尔条件普遍存在（M7/M8 规则的现实命中面）

## M6 parser ✅ 2026-10-14（需求方已确认）

- [x] **T6.1**（2026-10-14）`PlaceholderNormalizer` + `DruidSqlParser`（别名包装）→ 详设 §10.1/§10.4
- [x] **T6.2**（2026-10-14）`DynamicEvaluator`：折叠规则表、choose 双路带路径标签、include 展开（property/深度防循环）、丢弃溯源 role、上限 8 截断（DD-4，实现为"带上限即时枚举"）→ 详设 §10.2
- [x] **T6.3**（2026-10-14）`LambdaSkeletonRenderer` + `LambdaCall.nested`（Consumer 嵌套组扩展）：全量方法表、条件重载、从句、未收录 unknown（DD-8）→ 详设 §10.3
- [x] **T6.4**（2026-10-14）`QueryFactsBuilder`（15 项事实 + UNRESOLVED DD-10）+ `StatementParser` 总装 → 详设 §10.5/§10.6
- [x] P5/P6 引擎接线（`ExtractionOutput` 复用索引；`--verbose` 解析摘要与事实）
- 验收：6 个测试类全绿（累计 164）；**真实项目验证**：57 条全部解析成功（55 骨架 + 2 `${}` 短路候选）
  - 真实数据修复①：实体式 `update(entity, wrapper)` → SET 占位列保证可解析 + unknown
  - 真实数据修复②：含 `${}` 语句解析失败不再丢失 M8 判定 → 保留为空骨架"短路候选"（D8 结构性保证，§8.5）
  - 限制标注：UNION 简化为"有过滤 + uncertain"；子查询内容隔离

## M7 rule ✅ 2026-10-14（需求方已确认）

- [x] **T7.1**（2026-10-14）`Rule/RuleContext/RuleProvider` SPI + `RuleRegistry`（13 内置 + ServiceLoader + level/enabled 覆盖）+ `RuleEngine`（语句级短路 → 骨架循环首命中即停 → 归类，防御式跳过）→ 详设 §11.1~§11.3
- [x] **T7.2**（2026-10-14）H1~H3（保守折叠提示附注、无表语句不判定）→ 详设 §11.4
- [x] **T7.3**（2026-10-14）M1~M6（元数据缺失=无索引、类型缺失不判定、CROSS/逗号连接、空 OR 分支判定）→ 详设 §11.4
- [x] **T7.4**（2026-10-14）M8（短路，含空骨架短路候选覆盖）+ L1/L2/L4 → 详设 §11.4
- 验收：13 个规则测试类 + `RuleEngineTest`（短路/首命中即停/禁用/级别覆盖），54 个新用例（累计 218）
  - **真实项目判定**（union-order-app 全量，5.6s）：高危 5（全部为 H3 + 保守折叠附注——运行期条件丢弃后 WHERE 消失，D19 行为兑现）| 中危 52（2 条 M8 `${ew.customSqlSegment}` + 50 条 M1 空元数据预期噪音）| 退出码 1（CI 门禁语义可用）

## M8 report + 汇总 ✅ 2026-10-14（需求方已确认）

- [x] **T8.1**（2026-10-14）`WhitelistFilter`：精确/`namespace.*` 前缀、Lambda 语句不参与（`WhitelistFilterTest` 8 用例）→ 详设 §13.3
- [x] **T8.2**（2026-10-14）`SummaryCalculator`：口径公式 + 恒等式断言（`total+skip+ignored==提取总数`，`SummaryCalculatorTest` 4 用例）→ 详设 §13.4
- [x] **T8.3**（2026-10-14）`ConsoleReporter`：运行头/统计行/高危明细/`--verbose` 中低危/纯文本（非 TTY 无色）/空结果提示（DIFF"无差异语句"、FULL"未提取到 SQL 语句"）（`ConsoleReporterTest` 7 用例）→ 详设 §12.2
- [x] **T8.4**（2026-10-14）`JsonReporter + ReportWriter`：DTO 字段顺序（含缩进级顺序断言）、`serializeNulls`（null 键保留）、目录自动创建、同名覆盖写、写失败 `ReportException` → exit 2（`JsonReporterTest` 4 用例 + `ReportWriterTest` 3 用例）→ 详设 §12.3/§12.4
- P4 白名单与 P8 汇总入引擎、Main 的 P9 占位摘要替换为真实输出（JSON 先落盘 → 控制台摘要含报告路径）
- 验收：ac07/ac08；黄金 JSON 全量比对；28 个新用例（累计 **246 全绿**）
  - **真实项目验证**（union-order-app 全量）：控制台模板落实（运行头/统计行/高危明细含 H3 保守附注/报告路径）；`sql_risk_report.json` 63KB 落盘、字段契约与恒等式（57=5+52+0+0+0）正确；白名单演示（`com.niwodai.*`）→ 已忽略 12 条（XML/注解）、45 条 Lambda 不受影响；退出码 1

## M9 端到端与收尾 ✅ 2026-10-14（需求方已确认）

- [x] **T9.1**（2026-10-14）`AnalysisEngineIT` 跑通全部用例：12 个黄金目录（ac01/ac02/ac03/ac04/ac05/ac05b/ac06/ac07/ac08/ac11/skip-parse-failed/m1-metadata-semantics，每用例强断言 §13.4 口径恒等式）+ ac09 错误分支专项测试（非 git/非法 commit/坏配置/元数据缺失/格式错误 → exit 2）→ 详设 §18.3
- [x] **T9.2**（2026-10-14）确定性验证：提交身份/时间固定后，同一夹具两次运行与两个独立 JVM（两次 `mvn test`）报告逐字节一致；黄金 expected 含真实提交 SHA 可比对
- [x] **T9.3**（2026-10-14）离线构建 + `java -jar target/sql-risk-checker-shaded.jar` 按 README 命令自测（/tmp/m9-demo：DIFF 命中新 DELETE → H2、exit=1、报告落盘）
- [x] **T9.4**（2026-10-14）性能抽查：合成仓库 1050 文件 / 2250 语句，FULL 整跑 **3.3s**（NFR ≤30s，余量 9×）
- [x] **T9.5**（2026-10-14）收尾：偏差回写（详设 v1.2：§8.3 `Set<Integer>`、§6 ConfigLoader 签名、§9.1 SPI 形态、§9.4/§9.5/D19 补充、§12.3 sql 三态、§18.1 12→13、§18.2 夹具确定性）；README（用法/参数/退出码/规则表）与 JOURNAL 更新；全量 `mvn -o clean verify` **263/263 全绿**
- **过程中修复 2 个真实缺陷**：① 裸 `<if>WHERE …</if>` 折叠未归 `CONDITION`，H1/H2/H3 保守附注在该常见形态缺失（ac03 暴露；含 `DynNode.innerText()` 角色推断修正）；② JGit `PersonIdent(PersonIdent)` 拷贝构造器重置提交时间，夹具 SHA 跨 JVM 不稳定（黄金比对暴露；改复用固定实例）

## 预研风险清单（M0.3 提前处理，结论回写本节）

| 风险点                              | 影响             | 结论 / 处理                                                                                     |
| -------------------------------- | -------------- | ------------------------------------------------------------------------------------------ |
| SAX endElement 行号语义差异            | 语句原文切片错位，diff 映射失效 | ✅ 已验证（M0.3）：endElement 行号 = 闭合标签行，按行切片可行；DTD 离线解析 OK（无需降级方案）                                 |
| Druid 对 MySQL `UPDATE/DELETE LIMIT` | H3/M 类事实失真      | ✅ 已验证（M0.3）：`getLimit()` 在方言 AST 上可达；`SELECT *` 经 `SQLSelectItem.getExpr()` 判定                    |
| JavaParser 泛型/符号解析局限             | Lambda 实体解析失败率上升 | ✅ 显式泛型可达（M0.3）；符号求解器场景局限待 M5 实测，失败走表名占位 `?` 降级（D13）                                         |
| 大仓库全仓索引构建耗时                      | 30s 性能目标       | ✅ 已验证（M9）：1050 文件 / 2250 语句 FULL 整跑 3.3s；排除目录（target/build/out/.idea/node_modules）已实现；未触发索引限定降级                  |

***

*文档完。任务完成状态就地勾选；如需求/设计变更，先更新文档再调整本任务表。*
