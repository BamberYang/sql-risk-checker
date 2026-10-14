# 开发任务拆解（TASKS）

> 依据：详细设计 v1.1、概要设计 v1.7。规模估算 S/M/L 仅用于排序参考（S≈半天内，M≈1~2 天，L≈3 天+）。
> **完成定义（DoD）**：`mvn -o clean verify` 通过；任务对应单测齐备（规则类须含 正例/反例/边界 三类）；不破坏既有黄金用例；**代码满足 Sonar 代码规范（需求方扫描验证，新代码零新增违规；设计性例外须登记豁免并附理由）**；勾选本文件并标注日期。
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
| M10 | 数据库元数据（JDBC 接入，FR-8）           | M  | M9/交付后增补           | 元数据加载测试（H2）全绿；文件元数据通道零残留 |
| M11 | 方言 SPI（MySQL 基线 + 扩展机制，FR-9）    | S  | M10                 | SqlDialectsTest；解析行为零变化 |
| M12 | 测试夹具迁移与回归（H2）                    | M  | M11                 | 黄金用例全绿；ac09 新错误形态 |

依赖链：`M0 → M1 → {M2, M3, M4}`；`M4 → M5 → M6 → M7 → M8 → M9 → 交付后增补 → M10 → M11 → M12`。M2 与 M3/M4 可并行；M5、M6、M7 各自内部子任务按序推进；**M10~M12 为扩展需求（FR-8/FR-9，详设 v1.8）实现串行链**，门禁沿用：每里程碑完成测试并经需求方确认后进入下一阶段。

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

## M10 数据库元数据（JDBC 接入，FR-8）✅ 2026-10-15（需求方已确认；真实 MySQL 连库验证延至 M12）

- [x] **T10.1**（2026-10-15）config/CLI 改造：`DataSourceSetting` + `AppConfig`（去 `metadataFile`，加 `datasource`/`dialect`）；`CliArgs` 增 `--dialect/--db-url/--db-username/--db-password`、移除 `--metadata`；`ConfigLoader` CLI 覆盖 + 密码三级优先级（CLI > `SQL_RISK_DB_PASSWORD` > yaml）+ `metadata_file` 已移除键告警 + 校验（url 必填、`jdbc:` 前缀）→ 详设 §5/§6
- [x] **T10.2**（2026-10-15）元数据加载：pom 引入 MySQL Connector/J（compile）；`JdbcMetadataProvider`（一次连接加载：`getTables/getColumns/getIndexInfo`、类型归一化、索引列序、连接即关、失败 → `MetadataException` exit 2）→ 详设 §7；删除 `JsonMetadataProvider` 与 `examples/sql-risk-metadata.example.json`
- [x] **T10.3**（2026-10-15）接线与入口：P1 换 JDBC 加载、`DEBUG_DEFAULT_ARGV` 改连接参数、错误信息脱敏（不含密码）→ 详设 §13.2/§5
- [x] **T10.4**（2026-10-15）黄金夹具迁移与错误形态更新（M，**原 T12.1/T12.2 并入**：M10 删除 `JsonMetadataProvider` 后，用例 `metadata.json` 依赖必须同步迁移）：14 用例 `metadata.json` → `schema.sql`（H2 内存库建 schema → `JdbcMetadataProvider` 加载）；`GoldenCase`/`AnalysisEngineIT` 管线更新；expected 应零漂移（逐条人工复核）；ac09 的"元数据缺失/格式错误"改为"连接失败（非法 url）/datasource.url 缺失/方言未支持"
- 验收（2026-10-15 实测）：`JdbcMetadataProviderTest` 6 用例（H2：类型归一 VARCHAR/INT 别名、索引列序、快照独立于连接关闭、失败 exit 2）；Config/CliArgs/Engine/Main 测试全部按新语义更新；**14 个黄金用例 expected 零漂移**（H2 元数据与旧 JSON 语义等价验证）；**306/306 全绿**；离线 `mvn -o clean verify` 通过（在线校准一次：MySQL Connector/J 8.0.33 + H2 2.2.224 后恢复离线）；shaded jar 21.2MB（含驱动）
  - 说明：`--metadata`/`metadata_file`/`JsonMetadataProvider`/`examples/sql-risk-metadata.example.json` 全部移除（`metadata_file` 出现时告警忽略）；ac09 错误形态改为"连接失败（未知驱动）/datasource.url 未配置/方言未支持"
  - 注：真实 MySQL 连库验证需需求方提供连接信息（安排在 M12 回归时统一执行；已于 2026-10-14 补验完成，见 T12.1）

## M11 方言 SPI（MySQL 基线 + 扩展机制，FR-9）✅ 2026-10-15（需求方已确认）

- [x] **T11.1**（2026-10-15）方言抽象：`SqlDialect`/`SqlDialects`/`MysqlDialect`（`hasRowLimit` 现行为迁入）；`DruidSqlParser.parse(sql, dialect)`、`QueryFactsBuilder` 的 `hasLimit` 走方言 → 详设 §10.4/§10.5/§10.7
- [x] **T11.2**（2026-10-15）配置放开与扩展机制验证：`dialect` ∈ `supportedNames()`；未支持值（`oracle`/`postgresql`）→ 明确报错 exit 2（不降级）；以测试替身方言验证"实现 SPI → 注册 → 可用"的扩展路径 → 详设 §6/§10.7
- 验收（2026-10-15 实测）：`SqlDialectsTest` 6 用例（内置+ServiceLoader 注册、字典序确定性、大小写不敏感取值、未支持报错、MySQL 分页正反例、**替身方言端到端**——PG 形态 `FETCH FIRST` 解析→hasLimit 事实→配置校验放开）；**312/312 全绿、黄金用例 expected 零漂移**（MySQL 行为零变化）
  - 扩展路径（契约）：实现 `SqlDialect` → `META-INF/services` 注册 → 配置 `dialect: <name>` 即可用；未实现值报错不降级
  - 回写清单（M12）：概设 §3.3 依赖图新增 `config → parser`（方言取值校验经 `SqlDialects.supportedNames()`）

## M12 端到端回归与收尾 ✅ 2026-10-15（需求方已确认；T12.1 真实 MySQL 验证于 2026-10-14 补验完成）

- [x] **T12.1**（2026-10-14 补验完成）真实 MySQL 连库验证——需求方提供连接信息（`default-config.yaml`）后以 `union-order-app` 真实仓库（`dec908da`，FULL 模式）端到端执行：
  - **连接与一次加载**：`[元数据] jdbc:mysql://127.0.0.1:3306/union_order 加载 9 张表（一次加载，连接已关闭）`，加载日志全流程仅出现 1 次（AC-12 证据）；库内 9 张表与 `init.sql` 表清单一致
  - **元数据来自库内实时结构（铁证）**：库内 `cash_order.uid` 索引为文件快照所无 → `WHERE uid = ?` 语句**未命中 M1**（若用文件快照会误报）；反方向：M1 命中 12 条逐条对照库内索引首列全部属实，索引列负例 12 例（cash_no/uid/credit_id/code/order_no）零误报
  - **保守语义**：`quota_change_record`/`quota_order` 不在库快照中 → 命中"表不在元数据中，默认无索引"（D13）
  - **失败路径**：错误密码（CLI 优先于 yaml）→ exit 2，错误信息含 URL 不含密码（脱敏）；**密码零泄漏**：报告/日志均不含密码串
  - **报告与确定性**：JSON 49.6KB + HTML 57.9KB 双产出；口径恒等式 `57 = 5 高 + 12 中 + 28 低 + 12 通过`（SKIP 0）；两次运行报告（忽略 generatedAt）逐字节一致；FULL 扫描 + 连库 6.4s（NFR ≤30s）
- [x] **T12.2**（2026-10-15）文档回写：README（CLI 参数、yaml datasource 示例、示例文件删除）、CLAUDE.md（模块约束/元数据来源）、JOURNAL、详设偏差回写（如有）
- [x] **T12.3**（2026-10-15）全量回归：`mvn -o clean verify` 全绿；确定性复验（两轮独立 JVM）
- 验收（2026-10-15 实测）：**312/312 全绿**（离线 `mvn -o clean verify`）；**确定性复验**（`AnalysisEngineIT` 两轮独立 JVM 全绿）；**CLI 端到端冒烟**（shaded jar + 外部 H2 classpath 走真实命令：`[元数据] … 加载 0 张表（一次加载，连接已关闭）`（AC-12）+ DIFF 命中 H2 高危 + JSON/HTML 双报告）；文档回写完成——README（datasource 示例/CLI 表/状态）、CLAUDE.md（依赖图/SPI/技术栈）、概设 §3.3（`config → parser` 边）、详设 v1.8 实现回写（类型别名归一/环境变量重载/ParseContext 方言装配）

***

## 交付后增补（post-M9，需求方驱动）

- [x] 2026-10-14 入口调试便利：argv 为空 → `DEBUG_DEFAULT_ARGV`（IDE 直跑 main 可调试），stderr 打印 `[调试]` 提示 → 详设 §5
- [x] 2026-10-14 构建清理：禁用 shade 自动生成 `dependency-reduced-pom.xml`（本项目仅供 `java -jar` 直跑，无 install/deploy 消费场景）→ pom 注释
- [x] 2026-10-14 HTML 阅读层报告：`--output` 默认双产出 `sql_risk_report.json`（机器契约）+ `sql_risk_report.html`（单文件离线、筛选/折叠、全量转义、确定性）→ 详设 §12.5；274 用例全绿
- [x] 2026-10-15 注解 SQL Wrapper 段特殊处理：`${ew.customSqlSegment}`（根名解析到 `@Param` Wrapper 参数）不再误报 M8——代换解析 + `uncertain` 标记；绑定失配恢复 M8；黄金用例 ac12 → 详设 v1.4（§9.5-6/§10.6/§11.4）；291 用例全绿；真实项目 M8 误报 2 → 0
- [x] 2026-10-15 Lambda 控制流分支枚举：if/else 与 switch/case 中对 wrapper 追加条件 → 每分支独立骨架（对齐 `<choose>`，`DroppedBranch` 溯源，组合超 8 截断）；无 else 的 if / 循环体按 `<if>` 保守丢弃；黄金用例 ac13 → 详设 v1.5（§9.7/§10.3）；300 用例全绿；真实项目 switch 四分支语句从"混合错 SQL（M1 MEDIUM）"修正为真实首分支 SQL（L2 LOW）
- [x] 2026-10-15 元数据示例：按真实 DDL（union-order-app `init.sql`，PolarDB/PostgreSQL）生成 `examples/sql-risk-metadata.example.json`（9 张表，含 ALTER 追加列合并、唯一/复合索引），经工具实际加载验证并加守卫测试（301 用例全绿）；README 引用
- [x] 2026-10-15 空骨架归口 SKIP：解析产物为空（含 `${}` 解析失败的短路候选）且语句级短路未命中 → 从 `level=null` 结果改归 SKIP（PARSE_FAILED，"无可用 SQL 骨架"）；短路命中语义不变（AC-11）；`RuleEngine.run` 返回 `Outcome{results, skipped}`；ac12 增第三形态；302 用例全绿 → 详设 v1.6（§11.2-④）
- [x] 2026-10-15 HTML 报告文案与统计：分区/徽标「未命中风险规则·无命中」更名为「**通过**」，头部统计新增「通过」卡片（= total − 高 − 中 − 低）与恒等式行口径同步；JSON 契约不变；302 用例全绿 → 详设 v1.7（§12.5）
- [x] 2026-10-15 扩展实现（FR-8/FR-9）**交付完毕**：设计定稿（需求 v1.1、概要 v1.8、详设 v1.8 含实现回写）；**M10 数据库元数据 / M11 方言 SPI / M12 回归与收尾 均已确认**（真实 MySQL 验证为唯一暂缓项）；312 用例全绿
- [x] 2026-10-14 **Sonar 规范治理（需求方驱动）**：以 SonarCloud 扫描报告（165 issue / 23 规则，IMPACT_RANK）为输入，按三批治理并全量回归：
      - **BLOCKER 修复**：`javabugs:S2259`（NPE×11）根因根治——`RuleContext.facts()` 对语句级上下文改为 fail-fast（抛 `IllegalStateException`，引擎 `safeCheck` 兜底不变）；`S5998`（正则栈溢出）白名单模式改所有格量词（22 组用例等价性验证）；`S2447`（Boolean 返回 null）改 `Optional`。
      - **机械类批量修复**：S1128 未用导入 ×11、S1192 重复字面量 ×11、S9391 循环→Stream ×20、S1168 空集合返回 ×3、S1172 未用参数 ×7（含 `ConfigLoader.load` 移除 `repoPath` 保留参数，**详设 §6 同步**）、S8491/S7158/S1130/S5961/S5785/S8714/S1066/S127/S1905 等；**诊断输出集中化**：新增 `util/Diagnostics`，生产代码 `System.err` 从 22 处收敛至 1 处（S106 豁免点从 22 → 3）。
      - **认知复杂度拆分**（S3776 ×23，含 `LambdaSkeletonRenderer.applyCalls` 101 → 分组方法、`LambdaSqlExtractor.buildModels` 36 → 单一职责拆分）与 S135 循环跳转收敛 ×10。
      - **设计性例外集中登记**：`docs/sonar-exceptions.md`（EX-1 S106 标准流 / EX-2 S1181 顶层 Throwable 兜底 / EX-3 S107 值对象构造器 ×4），含理由、替代保障与失效条件；SonarCloud 侧按 Accepted 标记。
      - **回归**：`mvn -o clean verify` **314 用例全绿**（含 14 黄金样例，ac06 夹具按 S7158 调整后 expected 仅 head SHA 变化、语义零漂移）；详设 v1.9 回写；**复扫由需求方执行验证**（预期剩余 = 登记例外）

## 预研风险清单（M0.3 提前处理，结论回写本节）

| 风险点                              | 影响             | 结论 / 处理                                                                                     |
| -------------------------------- | -------------- | ------------------------------------------------------------------------------------------ |
| SAX endElement 行号语义差异            | 语句原文切片错位，diff 映射失效 | ✅ 已验证（M0.3）：endElement 行号 = 闭合标签行，按行切片可行；DTD 离线解析 OK（无需降级方案）                                 |
| Druid 对 MySQL `UPDATE/DELETE LIMIT` | H3/M 类事实失真      | ✅ 已验证（M0.3）：`getLimit()` 在方言 AST 上可达；`SELECT *` 经 `SQLSelectItem.getExpr()` 判定                    |
| JavaParser 泛型/符号解析局限             | Lambda 实体解析失败率上升 | ✅ 显式泛型可达（M0.3）；符号求解器场景局限待 M5 实测，失败走表名占位 `?` 降级（D13）                                         |
| 大仓库全仓索引构建耗时                      | 30s 性能目标       | ✅ 已验证（M9）：1050 文件 / 2250 语句 FULL 整跑 3.3s；排除目录（target/build/out/.idea/node_modules）已实现；未触发索引限定降级                  |

***

*文档完。任务完成状态就地勾选；如需求/设计变更，先更新文档再调整本任务表。*
