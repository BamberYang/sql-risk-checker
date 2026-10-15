# SQL 上线风险静态检查工具 —— 概要设计

***

## 1. 文档信息

| 项    | 内容                                          |
| ---- | ------------------------------------------- |
| 文档名称 | SQL 上线风险静态检查工具概要设计                          |
| 版本   | v1.10                                       |
| 日期   | 2026-10-15                                  |
| 关联文档 | 《SQL 上线风险静态检查工具需求文档》v1.1（下称"需求文档"）          |
| 覆盖范围 | 总体架构、模块划分、接口契约、关键流程与算法、数据模型、异常处理、测试设计、需求追溯 |
| 不含范围 | 类级方法实现细节、逐行伪码、UI 文案细节（归详细设计阶段）              |

修订记录：

| 版本   | 日期         | 变更内容                                                                                                                                                                       |
| ---- | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| v1.0 | 2026-10-14 | 首版                                                                                                                                                                         |
| v1.1 | 2026-10-14 | 按评审意见更新：最终风险结果新增 **SKIP** 归类（解析失败计入 SKIP）；移除 H4/H5 规则（`SELECT *` 无过滤由 H3 覆盖）；移除 M7 规则及 `bulk_operation_threshold` 配置；明确 `${}` 短路扫描含被丢弃分支；元数据文件缺失（含默认路径）改为报错 |
| v1.2 | 2026-10-14 | 按评审意见更新：移除保守策略开关（`conservative_if_always_false`），保守策略固定启用、不可配置关闭 |
| v1.3 | 2026-10-14 | 按评审意见更新：明确增量 SQL 改动语义——行级命中即提取**完整 SQL** 分析；`<sql>` 片段（含被 `<include>` 引用，含传递、跨文件引用）变更时，所有引用语句均按变更处理并完整分析 |
| v1.4 | 2026-10-14 | 按评审意见更新：确认工作区未提交更改不参与 diff，DIFF 模式两端均为提交树（old=prod、new=head） |
| v1.5 | 2026-10-14 | 按评审意见更新：FULL 模式缺省取当前 HEAD 提交树进行全量分析，同样不包含工作区未提交内容（两个模式数据源统一为提交树） |
| v1.6 | 2026-10-14 | 按评审意见更新：确认普通 QueryWrapper / BaseMapper 内置方法**不纳入** v1 扫描范围（写入 §15 已知限制）；确认 JSON 报告追加字段（含 `changeType: FULL`）可接受 |
| v1.7 | 2026-10-14 | 按评审意见更新：确认 XML 解析采用 JDK SAX + Locator（决策 D3）；全部决策确认完毕，待确认事项清零 |
| v1.8 | 2026-10-15 | 扩展设计（FR-8/FR-9）：① 元数据来源改为**数据库运行时加载**（JDBC，一次快照），文件元数据通道彻底移除（D24）；② 新增 **`SqlDialect` 方言 SPI**，v1 实现 MySQL、Oracle/PostgreSQL 预留（D25）；③ 连接配置三通道与密码优先级（D26）；④ JDBC 驱动**直接在 pom 中引用**（随 shaded jar 分发）、测试元数据改用 H2 内存库（D27） |
| v1.10 | 2026-10-15 | FR-9 方言落地：**PostgreSQL 方言实现**（`PostgresDialect`：LIMIT / FETCH FIRST 识别，纯 OFFSET 保守按无 LIMIT）；`hasLimit` 语义完全归方言（MySQL 零漂移）；pom 内置 PostgreSQL JDBC 驱动（D27 同策略）；配置/CLI 放开 `postgresql`；黄金用例 ac14；322 用例全绿 |
| v1.9 | 2026-10-14 | 代码质量基线（需求驱动）：**Sonar 代码规范强制**——生成/修改的代码必须满足规范，设计性例外集中登记（`docs/sonar-exceptions.md`）不得就地抑制；§2.1 增补质量目标；存量治理配套的内部契约调整（`RuleContext.facts()` fail-fast、诊断输出统一 `Diagnostics` 出口、`ConfigLoader` 移除未用保留参数）见详细设计 v1.9 |

> 本文档中标注【决策】的条目为概要设计阶段新增的设计决定，标注【假设】的条目需确认后方可进入编码（汇总见 §14）。

***

## 2. 设计目标与设计原则

### 2.1 设计目标（承接需求 §3）

1. 以管道化架构实现「差异获取 → 语句提取 → 解析规范化 → 规则分析 → 报告输出」五阶段闭环。
2. 三种 SQL 承载形态（mapper.xml / 注解 / Lambda 构造）收敛到统一的解析与规则管道，规则只实现一次。
3. 保守策略（FR-5）作为贯穿全局的默认语义：动态条件一律按最不利假设求值。
4. 规则引擎可插拔：新增规则只新增一个实现 + 注册，不改动核心流程；方言可插拔：新增数据库只需实现 `SqlDialect` SPI 并注册（FR-9）。
5. 只读、零副作用：仅连接配置的数据库**读取元数据一次**（FR-8），不执行任何业务 SQL、不修改目标仓库、不访问互联网。
6. 代码质量基线：生成/修改的代码必须满足 **Sonar 代码规范**（扫描验证）；设计性例外（如 CLI 输出契约的 `System.out/err`）集中登记于 `docs/sonar-exceptions.md`（理由 + 替代保障 + 失效条件），禁止就地静默抑制。

### 2.2 设计原则

| 编号  | 原则      | 说明                                                              |
| --- | ------- | --------------------------------------------------------------- |
| P1  | 单一管道    | 所有承载形态最终都产出「SQL 骨架文本」，复用同一 Druid 解析与规则引擎（见 §8.3、§5.7）         |
| P2  | 保守优先    | 任何"无法确定"的场景向更危险的一侧判定：`<if>`=FALSE（固定启用，不可关闭）、元数据缺失=无索引、还原不完整=可能漏条件  |
| P3  | 失败可见    | 无法处理的输入进入 `skipped`（SKIP 类）清单，绝不静默丢弃                          |
| P4  | 单向依赖    | 模块依赖无环、模型层（model）零依赖（见 §3.3）                                   |
| P5  | 确定性     | 同一输入必然产生同一报告：注入时钟、结果稳定排序、并行不改变输出顺序（见 §4.4）                     |
| P6  | 规则与流程解耦 | 规则只读取「语句事实」（QueryFacts）+ 配置 + 元数据，不感知 diff / extractor 的实现细节 |

***

## 3. 总体架构

### 3.1 架构视图

```
                          ┌──────────────────────────┐
                          │           cli            │  Main / 参数解析 / 退出码
                          └────────────┬─────────────┘
                                       │ RunContext
                          ┌────────────▼─────────────┐
                          │          core            │  AnalysisEngine：P0~P9 阶段编排
                          │  （管线调度 / 并行 / 汇总）   │  并发调度 · 结果聚合
                          └──┬─────┬─────┬─────┬─────┬─┘
              ┌──────────────┘     │     │     │     └───────────────┐
              ▼                    ▼     ▼     ▼                     ▼
      ┌───────────────┐   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
      │    config     │   │   diff   │ │extractor │ │  parser  │ │  report  │
      │ 配置/白名单校验  │   │ JGit差异  │ │ 语句提取   │ │骨架+AST   │ │控制台+JSON│
      └───────┬───────┘   └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
              │                │            │            │            │
              │            ┌───▼────────────▼────────────▼────────────▼───┐
              │            │            model（核心模型，零依赖）            │
              └────────────►            SqlChange / SqlSkeleton /          │
                           │            Finding / StatementResult / Report │
                           └───────────────────────────────────────────────┘
      ┌────────────┐               ┌────────────────────────────────────┐
      │  metadata  │◄── SPI ───────│             rule（规则引擎）           │
      │ Metadata   │               │  QueryFacts → Rule → Finding 集合   │
      │ Provider   │               │  内置规则 H1~H3 / M1~M6、M8 / L1~L4 │
      └────────────┘               └────────────────────────────────────┘
```

数据流一句话概括：**diff/config 产出输入 → extractor 产出语句 → parser 产出骨架与 AST → rule 产出发现 → report 产出交付物**；core 负责把各阶段串成管线，cli 负责对外契约。

### 3.2 模块职责（对需求 §13 的细化）

| 模块（包）        | 职责                                                                  | 关键类（示例）                                                                                                                                                |
| ------------ | ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cli`        | main 入口、参数解析与校验、退出码、控制台摘要渲染的触发                                      | `SqlRiskCheckerMain`、`CliArgs`、`ExitCode`                                                                                                                |
| `core`       | 阶段编排（P0~P9）、并行调度、白名单过滤、结果汇总、退出码判定                                   | `AnalysisEngine`、`Pipeline`、`RunContext`、`AnalysisResult`、`WhitelistFilter`                                                                          |
| `config`     | yaml 配置加载、默认值合并、schema 校验、配置优先级                                     | `AppConfig`、`ConfigLoader`、`ConfigValidator`、`RuleConfig`                                                                                                |
| `metadata`   | 表元数据（表/字段/索引）**由 JDBC 从数据库加载一次**（快照供只读查询），`MetadataProvider` SPI                        | `MetadataProvider`、`JdbcMetadataProvider`、`TableMetadata`、`FieldMeta`、`IndexMeta`                                                                         |
| `diff`       | JGit 差异获取、全量扫描、变更行集合、`ProductionCommitProvider` SPI                | `GitDiffService`、`ChangeSet`、`FileChange`、`Hunk`、`ProductionCommitProvider`、`RepoWalker`                                                                  |
| `extractor`  | 语句提取三个实现 + 统一 SPI；statementId 生成；变更类型判定；XML 片段索引与派生变更传播                 | `SqlSourceExtractor`、`MapperXmlExtractor`、`XmlFragmentIndex`、`AnnotationSqlExtractor`、`LambdaSqlExtractor`、`EntityIndex`、`TableColumnResolver`、`JavaConstantResolver`、`ProviderMethodResolver` |
| `parser`     | 动态 SQL 保守求值（骨架生成 + 丢弃溯源）、Lambda 骨架渲染、占位符归一化、**按方言** Druid 解析与事实提取（`SqlDialect` SPI）       | `DynamicEvaluator`、`LambdaSkeletonRenderer`、`PlaceholderNormalizer`、`DruidSqlParser`、`SqlDialect`、`SqlDialects`、`QueryFacts`、`SqlSkeleton`、`DroppedBranch`、`ParseFailure`        |
| `rule`       | 规则 SPI、注册与配置覆盖、执行顺序与短路、内置规则                                             | `Rule`、`RuleEngine`、`RuleRegistry`、`RuleContext`、`builtin`（内置规则实现）                                                                                     |
| `report`     | 控制台摘要与 JSON 报告生成、文件写出                                                | `ConsoleReporter`、`JsonReporter`、`ReportWriter`、`ReportModel`                                                                                             |
| `model`      | 跨模块数据模型（枚举/值对象），零依赖                                                | `SqlChange`、`ChangeType`、`StatementSource`、`RiskLevel`、`Finding`、`SkippedItem`、`IgnoredItem`                                                             |
| `util`       | 文本/编码/范围工具                                                          | `TextUtils`、`LineRanges`、`IoUtils`                                                                                                                        |

> 说明：需求 §13 未单列 `metadata` 与 `core`，本设计将其显式化——`metadata` 独立是因为存在 `MetadataProvider` SPI 与**独立的元数据加载生命周期**（连接/快照/关闭，v1.8 起来源为数据库）；`core` 独立是为了让 `cli` 只负责对外契约、避免编排逻辑沉淀在入口类中。

### 3.3 模块依赖方向（硬约束）

```
cli → core → {config, diff, extractor, parser, rule, report}
config → metadata
config → parser（v1.8 实现注记：方言取值校验经 parser.SqlDialects.supportedNames()，无环）
diff → model
extractor → {model, java解析, xml解析}
parser → {model, metadata(只读), Druid}
rule → {model, parser(QueryFacts), metadata, config}
report → model
model → （无出向依赖）
```

约束：

- 依赖必须无环；`rule` 不得依赖 `diff` / `extractor`（规则只吃 `QueryFacts` + 上下文）。
- `model` 不依赖任何其他模块与第三方库。
- 该约束通过评审 + 可选的 ArchUnit 测试（dev 依赖）守护。

### 3.4 工程结构（单 Maven 模块 + 可执行 fat jar）

【决策 D1】采用**单 Maven 模块**，以包作模块边界，`maven-shade-plugin` 产出可执行 fat jar：

```
sql-risk-checker/                      # 单模块 Maven 工程
├── pom.xml
├── src/main/java/com/demo/sqlrisk/
│   ├── cli/  core/  config/  metadata/  diff/
│   ├── extractor/ {xml/, annotation/, lambda/}
│   ├── parser/ {dynamic/, druid/}
│   ├── rule/ {builtin/}
│   ├── report/  model/  util/
├── src/main/resources/
│   ├── default-config.yaml            # 缺省配置（内置兜底）
│   └── META-INF/services/             # 规则 SPI 扩展点（ServiceLoader）
└── src/test/java/...                  # 见 §12
```

选型理由：工具为单进程 CLI，无多制品发布需求；包级模块化已能满足"规则引擎与解析/提取解耦"（NFR 可维护性）。若后续需要单独发布规则 SDK，可机械拆分为多模块（候选：`sql-risk-spi`、`sql-risk-core`、`sql-risk-cli`）。

### 3.5 技术选型与版本锁定（Java 8 兼容）

| 依赖               | 版本                    | 用途                               | Java 8 兼容说明        |
| ---------------- | --------------------- | -------------------------------- | ------------------ |
| JDK              | 1.8（编译 target 1.8）    | 运行时                              | 需求已锁定              |
| JGit             | 5.13.x                | git diff / 仓库读取                  | 6.x 需 Java 11，锁定 5.x |
| Alibaba Druid    | 1.2.x                 | SQL AST 解析、SchemaStat 事实提取       | 兼容                 |
| SnakeYAML        | 1.33                  | yaml 配置解析                        | 兼容                 |
| JavaParser       | 3.25.x（core + symbol-solver） | Java 源码解析（Lambda / 注解 / 实体映射） | 兼容；升级 3.26+ 前需验证 JDK 要求 |
| Gson             | 2.10.1                | 报告 JSON 序列化（单 jar、零传递依赖）         | 兼容                 |
| mybatis-plus-core | 3.5.x                 | 链式方法 API 基准、符号解析辅助               | 需求已锁定              |
| slf4j-nop        | 1.7.36                | 静默 JGit 的 slf4j 告警               | 兼容                 |
| JUnit 5          | 5.10.x                | 单元/集成测试                          | 兼容（test scope）     |
| JDBC（java.sql）   | JDK 内置                | 元数据加载（FR-8）                      | 兼容（零新增依赖）          |
| MySQL Connector/J | 8.0.x（compile）        | JDBC 驱动**直接在 pom 中引用**，随 shaded jar 分发（D27） | 兼容 |
| PostgreSQL JDBC | 42.2.25（compile）     | 方言落地时按 D27 同策略入 pom（v1.10/FR-9） | 兼容；Oracle 驱动待其方言实现时加入 |
| H2               | 2.2.x                 | 元数据加载与黄金用例的离线测试载体（test scope）     | 兼容（test scope；仅测试使用） |

> 全量依赖离线可解析（内网 Maven 镜像/本地仓库），运行期不访问互联网；连接业务库仅用于读取元数据（FR-8）。

***

## 4. 运行时架构与主流程

### 4.1 端到端阶段管线

| 阶段  | 名称        | 输入                                | 输出                                              | 失败策略                                    |
| --- | --------- | --------------------------------- | ----------------------------------------------- | --------------------------------------- |
| P0  | 参数装配      | 命令行参数                             | `RunContext`                                    | 缺参/非法值 → stderr + exit 2                |
| P1  | 配置加载校验    | 用户 yaml、缺省配置、数据库连接（元数据来源）          | `AppConfig`、`MetadataProvider`（JDBC 一次加载的快照）   | 配置错误 / 元数据加载失败 → exit 2                  |
| P2  | 模式判定与差异获取 | repo、head/prod commit              | `ChangeSet`（差异模式）或候选文件清单（全量模式，取自 head 提交树）        | 非 git 仓库/非法 commit → exit 2             |
| P3  | 语句提取      | `ChangeSet` + extractor SPI + 全仓 XML 片段索引 | `List<ExtractedStatement>`（含片段派生变更命中的语句）+ `skipped[]` | 单文件失败隔离，不终止整体 |
| P4  | 白名单过滤     | 语句集合 + 白名单配置                      | 分析集合 + `ignored[]`                              | —                                       |
| P5  | 骨架生成      | `ExtractedStatement`（动态片段/Lambda 模型） | `SqlSkeleton[]`（含丢弃分支溯源）                        | 无法生成 → `skipped[]`                      |
| P6  | 解析与事实提取   | 骨架文本（占位符归一化后）                     | `ParsedSql`（Druid AST + `QueryFacts`）            | 解析失败 → 计入 **SKIP**（`skipped[]`），不中断、不影响退出码     |
| P7  | 规则执行      | `ParsedSql` + 配置 + 元数据             | `StatementResult`（含 `Finding[]`）                | 单规则异常捕获 → 记录警告，不中断（防御式）                 |
| P8  | 结果汇总      | 全部结果与清单                           | `ReportModel` + 退出码                             | —                                       |
| P9  | 输出        | `ReportModel`                      | 控制台摘要（stdout）+ `sql_risk_report.json`          | 写文件失败 → exit 2                          |

P3~P7 按文件粒度并行、语句粒度隔离；P0~P2、P8~P9 为串行。

### 4.2 主流程时序

```
CLI          Engine         Config/Diff     Extractor      Parser        Rule        Report
 │  args       │                 │              │             │            │            │
 ├──parse─────►│                 │              │             │            │            │
 │             ├──加载配置────────►│              │             │            │            │
 │             │◄─AppConfig───────┤              │             │            │            │
 │             ├──解析模式+差异──────────────────►│              │             │            │
 │             │◄─ChangeSet / 全量文件清单────────┤              │             │            │
 │             ├──提取（并行）───────────────────►│              │             │            │
 │             │◄─语句 + skipped──────────────────┤              │             │            │
 │             ├──白名单过滤（Lambda 语句跳过校验）→ ignored[]      │             │            │
 │             ├──骨架生成 + 解析（并行）───────────────────────►│             │            │
 │             │◄─ParsedSql / SKIP（解析失败）───────────────────┤             │            │
 │             ├──规则执行（语句级短路 → 逐骨架）─────────────────────────────►│           │
 │             │◄─StatementResult[]───────────────────────────────────────────┤           │
 │             ├──汇总 + 渲染─────────────────────────────────────────────────────────────►│
 │◄─stdout─────┤                                                                           │
 └─ exit code ─┘
```

### 4.3 两种运行模式

| 维度     | DIFF 模式（传 `--prod-commit`）      | FULL 模式（未传 `--prod-commit`，含未传 head） |
| ------ | ------------------------------ | ---------------------------------- |
| 差异获取   | `git diff <prod> <head>`（JGit） | 跳过 diff，遍历 head commit 提交树中全部候选文件      |
| 语句范围   | 仅变更命中语句（新增/修改，删除排除）            | head commit 提交树内全部语句                 |
| changeType | `ADDED` / `MODIFIED`           | `FULL`【决策 D5】                       |
| 报告字段   | 含 headCommit、prodCommit         | `prodCommit: null`、`mode: "FULL"`   |
| 用途     | 上线前增量检查                        | 首次接入摸底、全量体检                        |

补充规则：

- `--head-commit` 缺省取当前 HEAD；**diff 两端均为提交树**（old=prod commit 树，new=head commit 树），工作区未提交更改**不参与 diff**（需求原文"当前工作区 HEAD"）。【决策 D21（已确认）】
- FULL 模式数据源同样为**提交树**：缺省取当前 HEAD（可用 `--head-commit` 指定），遍历该提交树内的全部承载文件；**工作区未提交内容不参与**。【决策 D22（已确认）】
- `--head-commit` 与 `--prod-commit` 相同 → 空 diff → 0 条语句，正常退出（exit 0，提示"无差异语句"）。
- rename 文件按"删除+新增"处理（仅新增侧参与分析）。【决策 D15】

### 4.4 并发与确定性

- P3~P7 以文件为并行单元，线程池 `min(CPU核数, 8)`；JavaParser 的类型求解器不保证线程安全 → 每个工作线程持有独立的 `TypeSolver` 实例（基于共享的只读源索引 `EntityIndex` 构建）。
- 所有集合产出后按 `(file, line, statementId)` 稳定排序再进入 P8；控制台与 JSON 输出顺序确定。
- 报告 `generatedAt` 由注入的 `Clock`（Java 8）产生，测试中替换为固定时钟。

***

## 5. 模块设计

### 5.1 cli

- 职责：解析参数 → 构造 `RunContext` → 调用 `AnalysisEngine.run()` → 打印摘要 → 返回退出码。
- 参数定义与需求 FR-7 完全一致（见 §9.1），额外约定：
  - 未知参数、必填缺失、`--format` 取值非 `json`（本版仅支持 json，缺省即"控制台 + JSON 双输出"，该参数用于显式声明）→ 参数错误 exit 2。
  - 连接参数优先级：`--db-url` / `--db-username` / `--db-password` / `--dialect` 覆盖 yaml 同名项；密码三级优先级 **CLI > 环境变量 `SQL_RISK_DB_PASSWORD` > yaml**（D26）。
- 控制台摘要输出到 **stdout**；进度、警告、错误输出到 **stderr**（`--verbose` 控制详细度），保证 stdout 可被管道消费。

### 5.2 config

配置加载为"三层合并"，后者覆盖前者：

```
内置默认（resources/default-config.yaml） < 用户 yaml < 命令行参数
```

校验规则（违反 → exit 2，消息指明字段与原因）：

| 校验项                           | 规则                                                        |
| ----------------------------- | --------------------------------------------------------- |
| `rules[].ruleId`              | 必须是内置规则 ID（未知 ID → 警告并以默认值继续，不报错）                          |
| `rules[].level`               | 必须是 `HIGH/MEDIUM/LOW` 之一                                  |
| `whitelist.statements[]`      | 仅允许「完整匹配」或 `namespace.*` 前缀匹配；出现 `*` 位于中段、多个 `*` 等非法模式 → 报错 |
| `datasource.url`              | 必填（CLI `--db-url` 可提供）；须为 `jdbc:` 前缀                                  |
| `datasource.url`              | 必填（CLI `--db-url` 可提供）；须为 `jdbc:` 前缀                                  |
| `dialect`                     | 取值必须 ∈ 已支持方言列表（v1 仅 `mysql`）；未实现值 → 报错 exit 2（不静默降级，D25）           |
| `metadata_file`               | **已移除键**（v1.8）：出现时告警并忽略（元数据来源为数据库，D24）                          |

### 5.3 metadata

```java
public interface MetadataProvider {
    /** 表元数据；表不存在返回 Optional.empty()（语义 = 无任何索引，见 §8.7） */
    Optional<TableMetadata> find(String tableName);
}
```

- **唯一实现 `JdbcMetadataProvider`**（v1.8，FR-8/D24）：通过 JDBC 连接目标数据库**一次性加载**表/字段与类型/索引（`DatabaseMetaData.getTables/getColumns/getIndexInfo`），建立 `表名 → TableMetadata` 内存快照；**连接随后关闭，运行期不再连接、不重复加载**。
- 驱动加载（D27，2026-10-15 修订）：JDBC 驱动以 **Maven 依赖直接在 pom 中引用**（MySQL Connector/J，compile scope → 随 shaded jar 分发），运行时经 classpath 直接可见，无外置目录机制。
- **加载失败语义**【承接 D12（v1.8 修订）】：连接失败 / 权限不足 / 元数据查询失败 / 超时一律报错并 exit 2，不存在"缺失视为空元数据"的降级路径。
- 文件元数据通道（`JsonMetadataProvider`/`--metadata`/`metadata_file`）**已移除**（D24）；`MetadataProvider` SPI 保留，未来可扩展数据字典等来源。

### 5.4 diff

- 打开仓库：`FileRepositoryBuilder` 只读打开；路径不存在/非 git 仓库 → exit 2。
- commit 解析：`repo.resolve(sha)` + `RevWalk.parseCommit` 双校验；short sha 支持；解析失败/对象非 commit → exit 2。
- 差异计算：`Git.diff().setOldTree(prod).setNewTree(head)` + `DiffFormatter`，按 `RawText` 逐 hunk 提取 `EditList`，得到：

```java
public final class Hunk {
    int oldStart, oldCount, newStart, newCount;
    IntSet addedLinesInNew;      // 新文件中的新增行号
    IntSet deletedLinesInOld;    // 老文件中的删除行号
}
public final class FileChange {
    String path;
    DiffType type;               // ADD / MODIFY / DELETE / RENAME
    List<Hunk> hunks;
}
```

- 全量模式：`RepoWalker` 以 **head commit（缺省 HEAD）的提交树**为数据源，经 JGit `TreeWalk` 遍历收集候选文件（`*.java`、`*.xml`），文件内容从提交 blob 读取（**不读工作目录，未提交更改不参与**）；默认排除 `target/`、`build/`、`out/`、`.idea/`、`node_modules/`，排除列表可通过配置扩展。【决策 D22】
- `ProductionCommitProvider` SPI（FR-1 预留）：

```java
public interface ProductionCommitProvider {
    Optional<String> provide(RepoContext ctx);   // 本版：CliArgProductionCommitProvider 从参数取值
}
```

后续接入"调用接口获取线上运行版本 commit id"时替换实现即可，主流程不变。

### 5.5 extractor

#### 5.5.1 统一 SPI

```java
public interface SqlSourceExtractor {
    String name();                                        // "mapper-xml" / "annotation" / "lambda"
    boolean supports(CandidateFile file);                 // 依据扩展名 + 内容特征
    ExtractResult extract(CandidateFile file, FileChange change);  // 全量模式下 change 为 FULL 占位
}
```

三种实现的产物统一为 `ExtractedStatement`：

```java
public final class ExtractedStatement {
    String statementId;            // mapper.xml/注解: namespace.id；Lambda: 文件名:行号
    StatementSource source;        // MAPPER_XML / ANNOTATION / LAMBDA
    ChangeType changeType;         // ADDED / MODIFIED / FULL
    String file; int line;         // 承载文件与起始行（报告定位用）
    String rawText;                // 语句原文（XML 片段 / 注解文本 / Lambda 链源码），供 M8、L1 与报告使用
    Object carrier;                // 承载载荷：DynamicXmlFragment / AnnotationSqlText / LambdaChainModel
}
```

`skipped`（SKIP 类别，口径见 §9.4）收录两类语句，均附 `reason`：（a）识别到 SQL 承载形式但无法完成提取（如 Provider 方法无法静态求值；Lambda 实体类型无法解析出表名时按 §8.3 降级处理，不属于此类）；（b）解析失败语句（§5.6，`reason` 形如 `解析失败：…`）。非 SQL 文件不进入清单、不计 skipped。

#### 5.5.2 mapper.xml

1. 以 `supports()` 判定：扩展名 `.xml` 且根元素为 `<mapper>`（或包含 `<select|update|insert|delete>` 元素）。
2. 用 JDK SAX 流式解析（**不用 dom4j**，理由见【决策 D3】：需要精确行号做 diff 映射，SAX `Locator` 直接提供；且可同步加固 XXE）。
3. 对每个语句元素记录：`namespace`（根属性）+ `id` + 起止行号 → `statementId = namespace.id`。
4. 按行范围**截取语句元素原文**（含全部动态标签），交给 parser 处理；同时计算与 diff 变更行的交集（算法见 §8.1）判定 ADDED / MODIFIED / 未变更。
5. 同一语句一行删一行改 → MODIFIED（由交集算法天然覆盖）。
6. DOCTYPE/EntityResolver：为 MyBatis DTD 注册**返回空内容的 EntityResolver**，禁止任何外部实体与网络取 DTD（离线要求 + XXE 防护）。
7. **片段索引与派生变更（增量语义，已确认）**：提取前对仓库**全部** mapper.xml 建立 `XmlFragmentIndex`（仅 `.xml`、SAX 流式，记录 `<sql id>` 定义位置与 `<include refid>` 引用点，开销可控）：
   - refid 解析：裸 `id` → 同 namespace；`namespace.id` → 跨文件解析；
   - 建立「`<sql>` 片段 ← 引用方」反向依赖图（引用方含语句与其它片段，支持传递闭包；循环引用以 visited 集合防重入）；
   - 差异模式下，变更行落入某 `<sql>` 片段 → 反向图闭包内**所有引用语句**均按 MODIFIED 提取其完整 SQL（即使语句所在文件自身无变更），并记录派生来源 `changedVia = 片段 refid`；
   - 无法解析的 refid → 按 §5.6 解析失败处理（计入 SKIP，不静默）。

#### 5.5.3 注解类 SQL

识别注解：`@Select/@Update/@Insert/@Delete` 与 `@SelectProvider/@InsertProvider/@UpdateProvider/@DeleteProvider`。

- 字面量（含字符串数组拼接）→ 直接得到 SQL 文本；含 `<script>` 的注解文本走与 mapper.xml **相同的动态求值**管道。
- 静态常量引用（`@Select(SqlConsts.FIND_USER)`）→ `JavaConstantResolver` 在本仓源码内解析 `static final String`（支持简单拼接），失败 → skipped。
- Provider 类：`ProviderMethodResolver` 用 JavaParser 定位 `type.method` 的方法体，支持 `return "字面量"`、字面量 `+` 拼接、`StringBuilder.append(字面量)`；无法静态求值 → skipped（reason="无法静态解析 Provider 方法"）。
- statementId = `接口全限定名.方法名`；变更判定：注解所在方法声明范围与 diff 变更行有交集。
- 注解中 `#{}` / `${}` 保留原文进入 parser（归一化见 §5.6）。

#### 5.5.4 Lambda 类 SQL（MyBatis-Plus）

识别目标（`supports()` 的语法特征）：`new LambdaQueryWrapper<X>()`、`new LambdaUpdateWrapper<X>()`、`Wrappers.<X>lambdaQuery()/lambdaUpdate()`、`<mapper实例>.lambdaQuery()/lambdaUpdate()`、`<ServiceImpl子类>.lambdaQuery()/lambdaUpdate()`、`new QueryWrapper<X>().lambda()`、`Wrapper.lambda()`。

> 范围边界【决策 D23（已确认）】：普通 `QueryWrapper`（字符串列名链式，未经 `.lambda()` 转换）与 `BaseMapper` 内置方法（`selectById/deleteById/…`）**不纳入**识别与扫描范围（维持需求 FR-2 界定，见 §15.1）。

处理流程（对应 §8.3 算法）：

1. **实体类型解析**（优先级）：显式泛型 → `Wrappers.lambdaQuery(X.class)` 的 Class 参数 → 变量声明类型 → 调用接收者类型（mapper 接口 `extends BaseMapper<T>` / 服务类 `extends ServiceImpl<M,T>` 的泛型实参）→ 失败则表名标记为 `?`（unknown）。
2. **表/列映射**：`EntityIndex` 汇总全仓实体：`@TableName` 定表名、`@TableField` 定列名、`@TableId` 视同 `@TableField`；未标注时驼峰→下划线；`@TableField(exist=false)` 忽略。
3. **链式调用归约**：按源码调用链逐方法归约（方法→SQL 映射见 §8.3 表），布尔条件重载按保守策略处理，嵌套 `and/or(Consumer)` 递归构建谓词树。
4. **渲染为 SQL 骨架文本**（`?` 占位），交给 parser —— 与其余两种形态共用管道（原则 P1）。
5. statementId = `文件名(不含路径):行号`，行号取**链式表达式根调用所在行**（如 `new LambdaQueryWrapper<Order>()` 或 `orderMapper.lambdaQuery()` 的行号）；该 ID 不参与白名单校验（FR-6）。
6. 还原不完整场景（wrapper 由方法参数传入、条件在外部拼接等）→ 标记 `uncertain=true` 并按"可能缺失条件"继续分析（原则 P2），不静默丢弃。【决策 D11】
7. **语句范围（增量语义）**：链式构造表达式 ∪ 该 wrapper 变量可见的执行调用行（如 `mapper.selectList(w)`，经变量引用追踪定位）；范围内**任意一行**变更 → 提取完整链 SQL 分析（§8.1）。

### 5.6 parser

三段式：**骨架生成 → 占位符归一化 → Druid 解析与事实提取**。

1. `DynamicEvaluator`（mapper.xml / `<script>` 注解）：按保守策略对 `<if>/<choose>/<when>/<otherwise>/<where>/<trim>/<set>/<foreach>/<include>` 求值，产出 1~N 个 `SqlSkeleton`，并记录 `DroppedBranch` 溯源（折叠规则表见 §8.2）。
2. `LambdaSkeletonRenderer`：由 §5.5.4 的链式模型渲染骨架文本。
3. `PlaceholderNormalizer`：`#{...}` → `?`；`${...}` → `?`（同时置 `hasDollarPlaceholder=true` 供 M8 短路）；保留原文切片位置映射，供 Finding 生成 `snippet`。
4. `DruidSqlParser`：`SQLParserUtils.createSQLStatementParser(sql, dialect.dbType())` → `SQLStatement` AST（方言由配置决定，缺省 MySQL；D25）；同时构建：

```java
public final class QueryFacts {          // 规则唯一读取的"语句事实"（原则 P6）
    StatementKind kind;                  // SELECT / UPDATE / INSERT / DELETE
    boolean hasWhere, hasLimit;
    boolean selectAll;                   // SELECT *
    Set<String> tables;
    Map<String, Set<String>> predicateColumnsByTable;  // WHERE 谓词列
    Map<String, Set<String>> joinOnColumnsByTable;     // JOIN ON 关联列
    boolean hasOr; boolean hasJoinWithoutOn; boolean hasCommaJoin;
    boolean hasFunctionWrappedColumn; boolean hasLeadingWildcardLike;
    boolean hasImplicitCast;             // 推测项，需元数据二次确认
    List<DroppedBranch> droppedBranches; // 保守折叠溯源，仅作 Finding 提示证据
    boolean uncertain;                   // Lambda 还原不完整等
    // …… 其余规则所需事实
}
```

5. 解析失败：捕获异常，产出 `ParseFailure{statementId, file, line, reason, sql}` 并计入 **SKIP**（报告 `skipped[]`），**不中断**整体分析、**不影响退出码**。

### 5.7 rule

```java
public interface Rule {
    String id();                     // "H1" ...
    RiskLevel defaultLevel();
    int priority();                  // 执行顺序（升序）；语句级短路规则为负值最先执行
    boolean shortCircuit();          // 命中后该语句停止后续全部规则（当前仅 M8）
    List<Finding> check(RuleContext ctx);
}
public final class RuleContext {
    QueryFacts facts; SQLStatement ast; StatementMeta meta;   // 语句元信息
    MetadataProvider metadata; AppConfig config; String rawText;
}
```

执行语义（严格对齐 FR-4 / FR-5 / §16）：

1. **语句级先行**：短路规则（M8）扫描语句**原文**（含被丢弃分支），命中即定级 MEDIUM 并结束该语句，不再评估任何骨架与规则。
2. **骨架循环**：对骨架 `S1..Sn` 依次执行；对 `Si` 运行全部启用规则：
   - 短路规则（若有）先执行；
   - 其余命中全部收集（一条语句可同时命中多条多级规则）；
   - 若 `Si` 命中 ≥1 条 → 记录该骨架结果并**停止**，不再评估 `S(i+1)`（AC-4）；
3. **语句最终归类**：有命中 → 取命中规则的**最高级别**（HIGH/MEDIUM/LOW）；无法分析（解析失败等）→ **SKIP**（§9.4 口径）。
4. 配置可调整规则的 level / enabled（`RuleRegistry` 覆盖内置默认）。

内置规则实现要点（AST 判定依据）：

| 规则  | 判定依据（Druid AST / QueryFacts）                                                            | 依赖元数据 | 与保守策略的交互                     |
| --- | -------------------------------------------------------------------------------------- | ----- | ---------------------------- |
| H1  | `SQLUpdateStatement.getWhere()==null`（保守策略折叠使 WHERE 整体消失时同样命中，提示附注"条件位于动态分支内"）        | 否     | `<if>` 折叠后 WHERE 消失天然命中       |
| H2  | `SQLDeleteStatement.getWhere()==null`（同上）                                               | 否     | 同上                           |
| H3  | SELECT ∧ `!hasWhere && !hasLimit`（覆盖 `SELECT *` 且无过滤的无限制场景）                            | 否     | `<if>` 折叠后 WHERE 消失天然命中       |
| M1  | 谓词列集合（WHERE 列 ∪ JOIN ON 列）非空 ∧ 存在表无任何索引前缀列命中；表不在元数据 → 视为无索引                                | 是     | 元数据缺失 → 命中（宁可误报）             |
| M2  | WHERE 中比较表达式一侧为函数调用且实参含列引用                                                             | 否     | —                            |
| M3  | LIKE 模式为字面量且以 `%` 开头；Lambda 的 `likeLeft()` 视为确定性前导通配符亦命中                                   | 否     | 非字面量不判定                      |
| M4  | 比较两侧「列（元数据类型）」与「字面量」类型不一致（如数值列 = 字符串字面量）                                                       | 是     | 类型缺失 → 退化为不判定（需求 §15）        |
| M5  | 表数 ≥2 ∧（存在 JOIN 无 ON 条件 ∨ 逗号连接且 WHERE 无跨表关联）                                            | 否     | —                            |
| M6  | WHERE 存在 OR ∧ OR 某分支的列未命中索引                                                            | 是     | 元数据缺失 → 命中                  |
| M8  | 语句**原文**（含被丢弃分支）含 `${...}` → 短路定级 MEDIUM                                              | 否     | 语句级先行（见 §8.5）                |
| L1  | 语句原文规范化后包含配置关键字（大小写不敏感）                                                                | 否     | 关键字表来自配置                     |
| L2  | SELECT ∧ `selectAll` ∧ 存在过滤（有 WHERE / LIMIT / JOIN）                                    | 否     | 与 H3 天然互斥（H3 要求无过滤）           |
| L4  | SELECT ∧ 无 LIMIT ∧ 有 WHERE                                                             | 否     | 无 WHERE 场景已由高危覆盖，不重复报        |

> 规则 ID `L3` 在需求文档中缺号；`M7`（UPDATE/DELETE 无 LIMIT 的大批量）因静态扫描无法确定影响行数而**移除不使用**，相关配置 `bulk_operation_threshold` 一并移除【决策 D18】。两个编号均保留不复用。

规则加载与扩展：

- 内置规则在 `RuleRegistry` 静态注册；第三方/团队自定义规则通过 `ServiceLoader<RuleProvider>`（`META-INF/services`）加载，无需改动核心流程（NFR 可维护性）。
- 配置 `rules[]` 对同 `ruleId` 覆盖 level/enabled；被禁用的规则不参与执行。

### 5.8 report

- `ConsoleReporter`：输出运行头（模式、commit）、数量统计、**高危明细**（含中危可选 `--verbose`）；UTF-8；非 TTY 时禁用 ANSI 颜色。
- `JsonReporter`：`ReportModel` → JSON（Gson，字段顺序固定），写入 `<output>/sql_risk_report.json`；目录不存在则创建；同名文件覆盖；JSON 追加写 `\n` 结尾。
- 控制台样例与 JSON 结构见 §9.4。

***

## 6. 核心数据模型

```
StatementSource: MAPPER_XML | ANNOTATION | LAMBDA
ChangeType:      ADDED | MODIFIED | FULL
RiskLevel:       HIGH > MEDIUM > LOW            （规则级别，可比较，用于取 max）
StatementCategory: HIGH | MEDIUM | LOW | SKIP   （语句最终归类；SKIP = 无法分析，不计入高危）

SqlChange ──1...n──► SqlSkeleton ──1..1──► ParsedSql(QueryFacts)
    │                    │(含 DroppedBranch*)        │
    │                    └──────────────┐            │
    ▼                                   ▼            ▼
 元信息(file,line,changeType)      RuleEngine ──► Finding(ruleId,level,message,snippet)
                                                     │
AnalysisResult ◄─────────────────────────────────────┘
 ├── results:       List<StatementResult>   // 参与分析的全部语句（归类 HIGH/MEDIUM/LOW）
 ├── ignored:       List<IgnoredItem>       // 白名单命中
 └── skipped:       List<SkippedItem>       // SKIP：解析失败 + 承载形式无法识别/处理
```

关键模型字段：

| 模型                       | 关键字段                                                                                                                        |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| `StatementResult`        | statementId、source、file、line、changeType、sql（骨架文本）、findings[]、level（最高命中级别，可为空）、uncertain、changedVia（可空：因 `<sql>` 片段变更派生命中时的片段 refid） |
| `Finding`                | ruleId、level、message、snippet、skeletonIndex（命中的骨架序号，用于解释 choose 多骨架/保守折叠场景）                                          |
| `SqlSkeleton`            | text、droppedBranches[]、strategyTag（如 `choose:when#0`）、hasDollarPlaceholder、hasUnknownFragment、uncertain                    |
| `DroppedBranch`          | branchType(IF/WHEN/OTHERWISE)、reason(CONSERVATIVE_FALSE/RUNTIME_CONDITION)、role(CONDITION/SET/JOIN_ON/RAW)、fragmentText             |
| `ExtractedStatement`     | 见 §5.5.1                                                                                                                     |

***

## 7. 关键接口（SPI）契约

| 接口                        | 定义位置         | 用途                       | 本版实现                                        |
| ------------------------- | ------------ | ------------------------ | ------------------------------------------- |
| `SqlSourceExtractor`      | extractor    | SQL 承载形态提取扩展             | MapperXml / Annotation / Lambda             |
| `RuleProvider` / `Rule`   | rule         | 规则扩展（ServiceLoader）      | 内置 H1~H3 / M1~M6、M8 / L1、L2、L4             |
| `MetadataProvider`        | metadata     | 表元数据来源扩展                 | `JdbcMetadataProvider`（数据库运行时加载，D24）        |
| `SqlDialect`              | parser       | 数据库方言扩展（FR-9 预留）          | `MysqlDialect`（默认）；`OracleDialect`/`PostgresqlDialect` 预留 |
| `ProductionCommitProvider` | diff         | 生产 commit 获取扩展（FR-1 预留）  | `CliArgProductionCommitProvider`            |
| `Reporter`                | report       | 输出形式扩展（未来 HTML 等）        | `ConsoleReporter` / `JsonReporter`          |
| `ExecutionPlanAnalyzer`   | （预留空接口）      | 未来执行计划分析（需求 §4.2 范围外预留） | 无（仅接口与调用点占位）                                |

接口签名（核心三例）：

```java
public interface RuleProvider { List<Rule> rules(); }

public interface ProductionCommitProvider {
    Optional<String> provide(RepoContext ctx);
}

/** 方言 SPI（FR-9/D25）：新增数据库 = 实现本接口 + 注册 + 用例 */
public interface SqlDialect {
    String name();                            // "mysql"（配置值）
    DbType dbType();                          // Druid 方言映射
    boolean hasRowLimit(SQLStatement ast);    // 分页/限制子句识别（H3/L4 事实；MySQL=LIMIT，Oracle=ROWNUM/FETCH FIRST，PG=LIMIT/FETCH FIRST）
}

/** 供未来接入 Explain/执行计划分析：本版无实现，仅占位 */
public interface ExecutionPlanAnalyzer {
    Optional<PlanAnalysis> analyze(ParsedSql sql);
}
```

***

## 8. 关键算法与设计决策

### 8.1 差异 → 语句映射（FR-1 / FR-2）

输入：`FileChange`（含 hunks 的新增行 / 删除行集合）；语句在新文件中的范围 `[s, e]`（XML：SAX Locator 起止行；Java：JavaParser `Node.getRange()`）。

判定表（差异模式）：

| 文件类型     | 条件                                                                    | 结论       |
| -------- | --------------------------------------------------------------------- | -------- |
| ADDED    | 全部语句                                                                  | ADDED    |
| DELETED  | 全部语句                                                                  | 跳过（删除类不分析） |
| MODIFIED | `s ∈ addedLines` 且 `[s,e]` 全部由新增行/空行构成                                 | ADDED（整段新增） |
| MODIFIED | `[s,e] ∩ addedLines ≠ ∅`，或纯删除 hunk 的锚点位置 `newStart ∈ [s, e+1]`（覆盖"一行删一行改"） | MODIFIED |
| MODIFIED | 上述均不满足                                                                | 该语句未变更，不提取 |
| 无法判定     | 语句跨多个 hunk 且启发式冲突                                                     | 取保守值 MODIFIED |

**语句级完整性原则（已确认）**：只要语句范围内有**任意一行**进入变更集，就提取该语句的**完整 SQL**（含全部动态标签与展开的片段）做风险分析，绝不只分析改动行片段。三种承载形态一致适用：

- mapper.xml：语句元素的行范围（含元素内任意行）；
- 注解 / Provider：注解及其所在方法声明范围；
- Lambda 链：链式构造表达式 ∪ wrapper 执行调用行（§5.5.4 第 7 条）。

**`<sql>` 片段派生变更（已确认）**：变更行落在 `<sql id>` 片段定义内（而不在任何语句内）时，通过 `XmlFragmentIndex` 的反向依赖闭包（含传递引用与跨文件 `namespace.id` 引用）找到**所有引用该片段的语句**，每条均按 MODIFIED 提取完整 SQL 分析，报告中记录派生来源 `changedVia`。`<script>` 注解中的 `<include>` 同样经片段索引解析与传播。

全量模式：全部语句 → `FULL`。

### 8.2 保守策略求值（FR-3 / FR-5）

`DynamicEvaluator` 对动态片段的折叠规则表：

| 标签                                    | 求值规则                                                                                              | 丢弃溯源（DroppedBranch）              |
| ------------------------------------- | ------------------------------------------------------------------------------------------------- | -------------------------------- |
| `<if test>`                           | 一律 FALSE（保守策略固定启用，不可关闭）：分支不纳入                                                                    | 丢弃内容含条件语义 → role=CONDITION     |
| `<choose>`                            | 生成 2 份骨架分支：任一 `<when>` 成立（取第一个 when 的 body）/ `<otherwise>` 成立；各自独立评估，由规则引擎按"任一骨架命中即停止"处理 | 未被选中的分支记入对应骨架的溯源                 |
| `<where>`                             | 折叠：去掉首部多余 `AND/OR`；内容为空则整块省略                                                                       | —                                |
| `<set>`                               | 折叠：去掉尾部多余 `,`；内容为空则整块省略                                                                            | —                                |
| `<trim>`                              | 按 `prefix/suffix/prefixOverrides/suffixOverrides` 标准行为折叠                                          | —                                |
| `<foreach>`                           | 单元素展开（代表展开）：`open` + 一次 body 渲染（item 值→`?`）+ `close`；记录"集合长度不定"事实                                       | —                                |
| `<include refid>` + `<property>`      | 先经 `XmlFragmentIndex` 解析 `<sql>` 片段并替换属性占位符再求值（需求未列，但为 mapper.xml 实际必需能力）【决策】                          | 解析失败 → 计入 SKIP（不静默）             |
| 其他未知标签                                | 视为不透明片段，标记 `hasUnknownFragment`，不崩溃                                                                  | -                                |

丢弃溯源的用途：求值后若最终骨架无 WHERE，`DroppedBranch` 记录仅作为 Finding 的提示证据（如 H1/H2/H3 的 message 附注"WHERE 条件位于动态分支内，按保守策略视为不成立"）——保守策略属于**解析阶段语义**，不再衍生独立风险规则（决策 D19）。

### 8.3 Lambda 骨架还原算法（FR-2 / FR-3）

```
输入: LambdaChainModel { root调用行, 实体类型(可空), 调用序列[Call{方法名, 参数, 行号}] }
输出: 骨架文本 + DroppedBranch[] + uncertain 标记

① 实体解析（§5.5.4 优先级链）→ 表名 + 列映射（EntityIndex）
② 构建谓词树：默认 AND 连接；or() 切换连接符；and/or/nested(Consumer) 递归子组
③ 逐调用归约（映射表如下），值→? 占位
④ 渲染：SELECT <列|*> FROM <表> WHERE <谓词树> [GROUP BY/ORDER BY/…] [后缀 last()]
```

链式方法归约表（MyBatis-Plus 3.5.x 全量链式方法的归约规则，节选核心行）：

| 方法族                                                                       | 归约                            | 保守策略交互                                        |
| ------------------------------------------------------------------------- | ----------------------------- | --------------------------------------------- |
| `eq/ne/gt/ge/lt/le`                                                        | `col op ?`                    | 带布尔首参重载：条件为字面量 `true` → 纳入；否则丢弃并记 `RUNTIME_CONDITION` |
| `between/notBetween`                                                       | `col BETWEEN ? AND ?`          | 同上                                            |
| `like/notLike`                                                             | `col LIKE ?`                   | 同上；`likeLeft/likeRight` 记前导通配符事实 → M3           |
| `isNull/isNotNull`                                                         | `col IS [NOT] NULL`            | 与布尔首参规则一致                                     |
| `in/notIn`                                                                 | `col IN (?)`（单元素折叠，记集合长度不定）    | 同上                                            |
| `inSql/notInSql/exists/notExists`                                           | 拼入字面量子查询；非字面量 → unknown 片段     | unknown 片段不影响 `WHERE 存在性` 判定；置 uncertain      |
| `groupBy/having/orderByAsc/orderByDesc`                                    | 直接渲染                          | —                                             |
| `select(SFunction...)`                                                     | 渲染显式列列表（替代 `*`）               | —                                             |
| `set/setSql`                                                               | UPDATE 的 SET 项                 | —                                             |
| `apply(片段, args)`                                                         | 字面量片段按 `{n}`→`?` 拼入；非字面量 → unknown | unknown → uncertain                          |
| `last(片段)`                                                                | 追加后缀（如 `limit 10`，影响 `hasLimit`） | 非字面量 → LIMIT 事实记"不确定"，对 H3 按无 LIMIT 保守处理    |
| `and()/or()`（无参）                                                           | 切换连接符                         | —                                             |
| `and(Consumer)/or(Consumer)/nested(Consumer)`                               | 递归构建子谓词组                      | 组内同样应用布尔条件规则                                  |

`uncertain` 的处理：不跳过分析；若因此导致 WHERE 缺失，由 H1/H2/H3 自然命中，提示附注"条件可能在其他位置构造，需人工复核"。【决策 D11】

### 8.4 statementId 与白名单（FR-6）

| 承载               | ID 形式                              | 变更类型来源           | 参与白名单 |
| ---------------- | ---------------------------------- | ---------------- | ----- |
| mapper.xml       | `namespace.id`                     | XML 元素行范围 ∩ diff | 是     |
| 注解 / Provider     | `接口全限定名.方法名`                       | 方法/注解行范围 ∩ diff  | 是     |
| Lambda           | `文件名(不含路径):行号`（如 `OrderServiceImpl.java:86`） | 链式表达式行范围 ∩ diff | **否** |

白名单匹配（唯一两种模式）：

```java
boolean hit = id.equals(pattern)                     // 完整匹配
           || (pattern.endsWith(".*")
               && id.startsWith(pattern.substring(0, pattern.length() - 1)));  // 前缀匹配
```

命中 → 进入 `ignored[]`（含 reason="whitelist"），不参与 P5 之后的任何阶段；Lambda 语句 ID 含 `:`，天然无法构成合法白名单模式，且实现上显式跳过校验。

### 8.5 `${}` 短路（M8，AC-11）

- 扫描范围 = 语句**原文**（`ExtractedStatement.rawText`，含被丢弃的动态分支）。理由：`${}` 是文本级拼接风险，出现在任何分支内都应暴露（保守原则 P2）。【决策 D8（已确认）】
- 命中 → 该语句直接定级 MEDIUM，输出唯一 Finding（M8），不再生成其他规则的 Finding；其余语句不受影响。

### 8.6 短路与规则执行顺序

| 场景                     | 消解                                                    |
| ---------------------- | ----------------------------------------------------- |
| M8 命中（语句原文含 `${}`）      | 语句级**短路**：直接定级 MEDIUM，不再输出该语句的其他规则命中                  |
| 多骨架（`<choose>`）任一命中      | 取**第一个命中的骨架**的 Finding 集合作为该语句结果，不再评估其余骨架（AC-4）       |
| 一条语句命中多级多条             | 全部输出（FR-4 明确允许多条），语句最终级别取最高命中级别                       |
| 无 WHERE 语句             | M1/M2/M3/M6 因"无谓词列"自然不适用（不判定），由 H1/H2/H3 覆盖            |
| `SELECT *` 无过滤         | 由 H3 覆盖；L2 的命中条件要求"已有过滤"，与 H3 天然互斥                     |

规则建议执行顺序（优先级）：`M8(语句级) → H 组 → M 组 → L 组`，同组内按规则 ID 升序，保证输出稳定。

### 8.7 索引判定语义（M1/M2/M4/M6，§15）

| 场景                     | 判定                                                     |
| ---------------------- | ------------------------------------------------------ |
| 表在元数据中，谓词列/关联列命中某索引**前缀列** | 视为命中索引，M1 不报                                          |
| 表在元数据中但无任何索引/未命中       | M1 报"条件列未命中索引"                                         |
| 表**不在**元数据中            | 默认该表无任何索引 → M1 报（宁可误报，避免漏报）                            |
| 元数据加载失败（连接/权限/驱动）          | 直接报错退出（exit 2），不进入分析（决策 D12 v1.8 修订 / D24）            |
| M4 字段类型缺失              | 退化为不判定（需求 §15 明确规定）                                    |
| 无谓词列的语句（如无 WHERE）      | M1/M6 不适用（不判定），由 H 组覆盖                                  |

***

## 9. 对外契约

### 9.1 CLI（FR-7）

参数与需求一致：

| 参数                    | 必填 | 说明                              |
| --------------------- | -- | ------------------------------- |
| `--repo <path>`       | 是  | 目标 Git 仓库绝对路径                   |
| `--head-commit <sha>` | 否  | 缺省取当前 HEAD；DIFF/FULL 均以提交树为数据源，工作区未提交更改不参与（D21/D22） |
| `--prod-commit <sha>` | 否  | 不传 → 全量分析                       |
| `--config <path>`     | 否  | 缺省用内置默认配置                       |
| `--output <path>`     | 否  | 报告输出目录，缺省当前目录                   |
| `--format json`       | 否  | 显式声明结构化输出（本版默认即控制台+JSON 双输出）    |
| `--dialect <name>`    | 否  | SQL 方言，缺省 `mysql`；未实现值 → exit 2（D25） |
| `--db-url <url>`      | 条件 | 数据库 JDBC URL（元数据来源）；未提供时读 yaml `datasource.url` |
| `--db-username <u>`   | 条件 | 数据库账号；未提供时读 yaml                  |
| `--db-password <pwd>` | 否  | 密码优先级：CLI > 环境变量 `SQL_RISK_DB_PASSWORD` > yaml（D26） |
| `--verbose`           | 否  | 输出详细过程日志与中低危明细（本设计新增，stderr 输出） |

退出码：

| 码 | 含义                                                       |
| - | -------------------------------------------------------- |
| 0 | 无高危（含"无语句可分析"的正常结束）                                      |
| 1 | 存在高危（仅由 HIGH 级别命中决定；SKIP/解析失败不触发）【决策 D6（已确认）】                |
| 2 | 参数/环境错误：仓库不存在、非 git 仓库、非法 commit、配置错误、方言未支持、数据库元数据加载失败（驱动/连接/权限/超时）、报告写出失败 |

### 9.2 配置文件（yaml，结构与需求 §9 一致）

见需求 §9。本设计补充：

- `datasource` 段承载数据库连接（url/username/password/connection_timeout_seconds）；密码三级优先级见 D26；JDBC 驱动由 pom 依赖提供（D27），配置中无驱动路径项。
- `dialect` 取值必须为已支持方言（v1 仅 `mysql`）；未实现值 → 报错 exit 2（D25）。
- 未知 `ruleId` → 警告不报错；非法白名单模式 → 报错 exit 2。
- 已移除键（出现时告警并忽略）：`bulk_operation_threshold`（D18）、`conservative_if_always_false`（D10）、`metadata_file`（v1.8/D24）。
- 缺省配置文件随 jar 内置，无 `--config` 时直接使用。

### 9.3 数据库元数据（JDBC 运行时加载，见 §5.3 与需求 §9.1）

- 加载内容：表清单（`getTables`）、字段与类型（`getColumns`，类型供 M4）、索引与列序（`getIndexInfo`）。
- 加载语义：**一次连接、一次加载、连接即关**；运行期内不重复连接；同一进程两次分析（如未来批处理）复用同一快照。
- 表名/列名匹配统一**大小写不敏感**（数据库差异大，静态检查场景按不敏感更安全）。
- 加载失败（连接失败/权限不足/查询失败/超时）→ 报错 exit 2（§10）。

### 9.4 报告

控制台摘要（stdout）：

```
[SQL 风险检查] mode=DIFF  head=abc1234  prod=def4567
仓库: /path/to/repo   扫描文件: 12   提取语句: 15
高危 2 | 中危 5 | 低危 5 | SKIP 1 | 已忽略 1
------------------------- 高危明细 -------------------------
[H2] com.demo.mapper.OrderMapper.updateBatch
     src/main/resources/mapper/OrderMapper.xml:42  (MODIFIED)
     UPDATE t_order SET status=?          ← UPDATE 语句缺少 WHERE，将全表更新，高危
报告已生成: ./sql_risk_report.json
退出码: 1
```

JSON 报告：在需求 §10.2 结构基础上，**新增字段均为追加式（additive），不破坏既有契约字段**【决策 D5】：

- 顶层新增 `"mode": "DIFF" | "FULL"`；
- `results[]` 新增 `"source": "MAPPER_XML|ANNOTATION|LAMBDA"`、`"uncertain": true|false`、可选 `"changedVia"`（因 `<sql>` 片段变更派生命中时给出片段 refid）；
- `findings[]` 新增 `"skeleton": 0`（命中的骨架序号，解释 choose 多骨架/保守折叠场景）；
- 需求中的 `parseFailed` 独立字段**取消**：解析失败并入 `skipped[]`（`reason` 形如 `解析失败：…`），summary 中对应合并为 `skip`（**SKIP 类别计数**）【决策 D6（已确认）】。

**语句最终归类与汇总口径定义**（消除需求样例歧义）【决策 D6（已确认）、D16】：

语句最终归类共四类：**HIGH / MEDIUM / LOW**（`results[]` 内，按最高命中级别归类）与 **SKIP**（`skipped[]` 内，无法分析：解析失败、承载形式无法识别/处理）；SKIP 不计入高危、不影响退出码。

| 字段           | 口径                                                        |
| ------------ | --------------------------------------------------------- |
| `total`      | 参与风险分析的语句数 = `results.length`                             |
| `high/medium/low` | 按语句**最高命中级别**归类计数，互斥；三者之和 = 有命中的语句数（≤ total）            |
| `skip`       | **SKIP 类语句数**（解析失败 + 承载形式无法处理），清单见 `skipped[]`，不影响退出码      |
| `ignored`    | 白名单命中数（不在 results 中）                                       |
| 提取总数         | `total + ignored + skip`                                  |

***

## 10. 异常处理与错误码

| 场景                       | 检测点      | 行为                                          | 退出码 |
| ------------------------ | -------- | ------------------------------------------- | --- |
| 仓库路径不存在 / 非目录            | P0/P2    | stderr：明确路径与原因                              | 2   |
| 非 git 仓库                 | P2       | stderr：提示需 git 仓库                          | 2   |
| 非法 commit id / commit 不存在 | P2       | stderr：包含输入值与 `RevWalk` 校验结果               | 2   |
| 配置文件缺失/语法错误/校验失败         | P1       | stderr：字段级原因                                 | 2   |
| 方言未支持（如 `dialect: oracle`） | P1       | stderr：列出已支持方言；不静默降级（D25）                    | 2   |
| 数据库连接失败/权限不足/元数据查询失败/超时   | P1       | stderr：连接目标（脱敏，不含密码）与原因；元数据为必需输入              | 2   |
| 语句解析失败                   | P6       | 计入 SKIP（`skipped[]` + reason），不中断，不影响退出码      | 0/1 |
| 语句无法提取（承载形式/Provider）    | P3/P5    | 入 `skipped[]` + reason                       | 不受影响 |
| 单文件读取失败（编码/IO）           | P3       | 入 `skipped[]` + reason，继续                    | 不受影响 |
| 规则执行抛异常（防御式）             | P7       | 捕获，stderr 警告（含 ruleId/statementId），继续后续语句   | 不受影响 |
| 报告目录/文件写入失败              | P9       | stderr + exit 2                             | 2   |
| 任何未捕获异常                  | 顶层       | 兜底捕获：stderr 堆栈（--verbose）与摘要提示，绝不裸抛          | 2   |

***

## 11. 非功能设计（对应 NFR）

| 类别   | 设计措施                                                                                                    |
| ---- | ------------------------------------------------------------------------------------------------------- |
| 性能   | 候选文件先行过滤（扩展名/内容特征）→ 只解析相关文件；文件级并行（`min(CPU,8)`）；`EntityIndex` 全仓只建一次并复用；JavaParser `CompilationUnit` 按文件缓存；Druid 单条解析为毫秒级。目标：1k 文件 diff ≤30s |
| 兼容性  | Java 8 编译与运行（§3.5 版本锁定表）；不访问互联网、离线可构建可运行（DTD 由本地 EntityResolver 兜底）；运行期仅连接所配置数据库读取元数据一次（FR-8）                                       |
| 可维护性 | 模块单向依赖（§3.3）；规则 SPI + 配置覆盖；三形态归一到单一管道（原则 P1）                                                            |
| 可测试性 | 规则/求值器/提取器全部可单测（§12）；时钟注入保证报告可比对                                                                        |
| 安全   | 只读打开仓库、绝不执行业务 SQL；数据库连接仅用于读取元数据（加载后即关闭）；连接日志脱敏（不打印密码）；XXE 加固（禁用外部实体）；不在日志中打印敏感业务数据（仅打印 SQL 片段）                                           |
| 日志   | 不使用业务日志框架：进度/警告走 stderr（slf4j-nop 静默 JGit 输出）；stdout 仅报告，保证管道友好                                               |
| 确定性  | 稳定排序 + 注入时钟 + 并行收集后统一排序（§4.4），同一输入逐字节同一报告                                                            |

***

## 12. 测试设计（对应 AC-1~AC-11）

### 12.1 单元测试

| 对象                                                            | 用例要点                                            |
| ------------------------------------------------------------- | ----------------------------------------------- |
| 每条内置规则（H1~H3、M1~M6、M8、L1/L2/L4）                            | 每规则 ≥1 正例 + ≥1 反例 + 边界例（如 M3 非字面量不报、M4 类型缺失不报） |
| `DynamicEvaluator`                                            | if/choose/foreach/where/set/trim/include 折叠；丢弃溯源 role 正确 |
| `LambdaSkeletonRenderer` + `EntityIndex`                      | 各识别入口、实体解析优先级链、注解/默认驼峰映射、嵌套条件组、布尔重载保守处理         |
| `WhitelistFilter`                                             | 完整/前缀/非法模式；Lambda 语句不参与                         |
| `PlaceholderNormalizer` / `DruidSqlParser` / `QueryFacts`     | 占位符归一化、事实提取正确性                                  |
| `ConfigLoader`/`ConfigValidator`                              | 校验矩阵；`metadata_file` 等已移除键告警；方言取值校验                |
| `JdbcMetadataProvider`（H2 内存库）                              | 表/字段类型/索引加载正确性；连接失败/超时错误语义；一次加载语义              |
| `SqlDialect`/`SqlDialects`                                    | 方言注册与取值；未实现方言报错；MySQL 分页事实识别（扩展机制以测试替身验证）          |

### 12.2 集成（黄金样例）测试

- 夹具：测试内用 JGit **程序化构造临时 git 仓库**（两次提交分别模拟 prod 与 head），case 目录结构：
  `cases/<caseId>/{repo/, changes/, schema.sql, config.yaml, expected/report.json}`；其中 `schema.sql` 为 H2 兼容 DDL，测试启动时执行到**内存库**，由 `JdbcMetadataProvider` 加载元数据（v1.8：替代原 `metadata.json`）。
- 比对：运行完整管线，报告 JSON 去除 `generatedAt` 后与 expected 全量比对（固定时钟注入）。
- 覆盖矩阵：3 种承载形态 × 风险等级 × 差异/全量模式 × 白名单 × 保守策略（AC-4：`<if>` 唯一条件折叠后 WHERE 消失 → 直接命中 H1/H2/H3；`<choose>` 双骨架任一命中即停）；含 SKIP 归类用例（解析失败不计入高危、不影响退出码）。
- 增量语义用例（v1.3 已确认）：`<sql>` 片段内改动 → 全部（含跨文件、传递引用）语句提取完整 SQL 分析；Lambda 链上任意一行（含 wrapper 执行调用行）改动 → 完整链提取。
- AC 映射：AC-1→diff 用例；AC-2→全量用例（数据源为提交树，断言工作区未提交改动不纳入）；AC-3/4/5→规则与动态用例；AC-6→Lambda 用例；AC-7→白名单用例；AC-8→报告快照；AC-9→错误码用例（非 git 仓库/非法 commit/坏配置/**数据库连接失败**各 1，v1.8 调整）；AC-10→CI 构建；AC-11→`${}` 短路用例；AC-12→元数据一次加载与失败语义用例（H2）；AC-13→方言 SPI 扩展机制与未支持值报错用例。

### 12.3 构建与 CI 前置

- `mvn -o clean verify`（离线）必须通过；构建产物 `sql-risk-checker-shaded.jar` 可直接 `java -jar` 运行自测样例。

***

## 13. 需求追溯矩阵

| 需求                         | 设计章节                        |
| -------------------------- | --------------------------- |
| FR-1 差异获取 / 全量              | §4.3、§5.4、§8.1              |
| FR-2 SQL 变更提取              | §5.5、§8.3、§8.4              |
| FR-3 解析与规范化                 | §5.6、§8.2                   |
| FR-4 风险分析（三级规则）             | §5.7、§13.2（规则表）、§8.6        |
| FR-5 保守策略                  | §8.2、§5.7（保守折叠与 M8 短路）     |
| FR-6 白名单                   | §8.4、§5.2                   |
| FR-7 执行入口与参数                | §5.1、§9.1                   |
| §8 规则集                     | §5.7 内置规则表                  |
| §9 配置 / §9.1 元数据           | §5.2、§5.3、§9.2、§9.3        |
| FR-8 数据库元数据加载             | §5.3、§9.3、§10、§13.1（D24/D26/D27） |
| FR-9 多数据库与多方言              | §5.6、§7（SqlDialect）、§9.1、§14（D25） |
| AC-12 / AC-13                 | §12.1、§12.2 |
| §10 输入输出 / §10.2 JSON      | §9.4、§6                     |
| NFR（性能/兼容/维护/测试/错误/依赖）      | §11、§3.5、§10               |
| §14 AC-1~AC-11             | §12.2、§12.1                  |
| §15 已知限制                   | §8.7、§14、§15                |
| §16 决策记录                   | §14（全部落入设计决策）               |

***

## 14. 设计决策与假设（相对需求文档的补充/澄清）

| 编号  | 类型   | 内容                                                                                            | 影响章节        |
| --- | ---- | --------------------------------------------------------------------------------------------- | ----------- |
| D1  | 决策   | 单 Maven 模块 + 包级模块化 + shade fat jar；多模块拆分留作后续演进                                                   | §3.4        |
| D2  | 决策   | 三种承载形态统一收敛为 SQL 骨架文本，复用同一 Druid 解析与规则引擎                                                        | §5.5、§5.6   |
| D3  | 决策（已确认，偏离需求建议） | XML 解析采用 JDK SAX + Locator（需求建议 dom4j/DocumentBuilder）；理由：精确行号（diff 映射必需）、零依赖、XXE 加固方便                | §5.5.2      |
| D4  | 决策   | JSON 序列化用 Gson（单 jar 零传递依赖）；日志用 stderr + slf4j-nop                                             | §3.5、§5.8   |
| D5  | 决策（已确认） | `changeType` 扩展 `FULL`（全量模式）；报告新增 `mode`/`source`/`uncertain`/`changedVia`/`skeleton` 等**追加式**字段 | §4.3、§9.4   |
| D6  | 决策（已确认） | 最终风险结果新增 **SKIP** 类别：解析失败计入 SKIP，取消 `parseFailed` 独立字段与"高危待人工复核"处理；SKIP 不计入高危、不影响退出码         | §5.6、§9.4、§10 |
| D7  | 决策（已确认） | 取消规则互斥矩阵（H4/H5 已移除）；保留：M8 语句级短路 + 多骨架首个命中即停                                                        | §8.6        |
| D8  | 决策（已确认） | M8 的 `${}` 扫描范围为语句原文，**包含被丢弃分支**                                                            | §8.5        |
| D10 | 决策（已确认） | **移除保守策略开关**（原 `conservative_if_always_false`）：保守策略固定启用、不可关闭；`<if>` 一律视为 FALSE                          | §5.2、§8.2、§9.2 |
| D11 | 决策   | Lambda 还原不完整（wrapper 外部传入/条件在别处构造）→ 不跳过，按"可能缺失条件"分析并标记 `uncertain`                                | §5.5.4、§8.3 |
| D12 | 决策（已确认；v1.8 由 D24 修订） | 表元数据为必需输入：~~文件缺失（无论显式指定或默认路径）或格式错误~~ → exit 2；v1.8 起语义为**元数据加载失败（驱动/连接/权限/超时）→ exit 2** | §5.3        |
| D13 | 决策   | Lambda 实体无法解析出表名 → 表名占位 `?`，非元数据类规则照常判定；元数据类规则按"未知表=无索引"保守命中                                     | §5.5.4、§8.7 |
| D14 | 决策   | 并行执行 + 稳定排序 + 注入 Clock，保证报告确定性与可测性                                                              | §4.4        |
| D15 | 决策   | rename 按"删除+新增"处理（新增侧照常分析）                                                                     | §4.3        |
| D16 | 澄清   | 汇总口径：`total`=参与分析语句数，`high+medium+low ≤ total`，提取总数 = `total + ignored + skip`                     | §9.4        |
| D17 | 澄清   | 规则编号 `L3`（需求缺号）与 `M7`（v1.1 移除）保留不复用                                                           | §5.7        |
| D18 | 决策（已确认） | 移除 M7 规则及 `bulk_operation_threshold` 配置：静态扫描无法确定影响行数，不执行该规则                                       | §5.7、§9.2   |
| D19 | 决策（已确认） | 移除 H4/H5 规则：`SELECT *` 无过滤场景由 H3 直接覆盖；"唯一条件在 `<if>` 内且视为 FALSE"属**解析阶段**保守策略语义（折叠后 WHERE 消失 → 由 H1/H2/H3 判定），不设独立风险规则 | §5.7、§8.2   |
| D20 | 决策（已确认） | **增量改动语义**：行级命中 → 提取**完整语句**分析（XML 语句 / `<sql>` 片段 / 注解 / Lambda 链一致）；`<sql>` 片段变更向所有（含传递、跨文件）引用语句传播，均按 MODIFIED 并记录 `changedVia` | §5.5.2、§8.1 |
| D21 | 决策（已确认） | **工作区未提交更改不参与 diff**：DIFF 模式两端均为提交树（old=prod commit，new=head commit）；不提供工作区 diff 模式          | §4.3        |
| D22 | 决策（已确认） | **FULL 模式数据源为提交树**：缺省取当前 HEAD（可由 `--head-commit` 指定），遍历该提交树的全部承载文件，不读取工作区未提交内容        | §4.3、§5.4   |
| D23 | 决策（已确认） | 普通 `QueryWrapper`（字符串列名）与 `BaseMapper` 内置方法**不纳入** v1 扫描范围（维持需求 FR-2 界定），写入 §15 已知限制 | §5.5.4、§15.1 |
| D24 | 决策（已确认，2026-10-15） | **元数据来源改为数据库运行时加载**（JDBC `DatabaseMetaData`，一次快照、连接即关）；文件元数据通道（`JsonMetadataProvider`/`--metadata`/`metadata_file`）**彻底移除**；加载失败 → exit 2 | §5.3、§9.3、§10 |
| D25 | 决策（已确认，2026-10-15） | **方言 SPI `SqlDialect`**（方言名 + Druid DbType + 分页识别）；v1 实现 MySQL，Oracle/PostgreSQL 预留；配置未实现方言值 → **报错 exit 2**（不静默降级）；`dialect` 为配置/CLI 键 | §5.6、§7、§9.1 |
| D26 | 决策（已确认，2026-10-15） | 连接配置三通道：yaml `datasource` 段 + CLI 覆盖 + 环境变量；**密码优先级 CLI > `SQL_RISK_DB_PASSWORD` > yaml** | §5.1、§9.2 |
| D27 | 决策（已确认，2026-10-15） | JDBC 驱动**直接在 pom 中引用**（MySQL Connector/J，compile scope → 随 shaded jar 分发），无外置驱动目录机制；后续方言驱动在实现时加入 pom；测试元数据载体用 **H2 内存库**（test scope） | §3.5、§5.3、§12 |

**需求文档中未明确、本设计已补齐的能力**：`<include refid>` 解析与片段派生变更（§8.2、§8.1）、`<script>` 注解与 mapper.xml 同管道（§5.5.3）、静态常量与 Provider 方法的静态求值（§5.5.3）、XXE 加固与离线 DTD 兜底（§5.5.2）、元数据一次加载的连接生命周期管理（§5.3）。

**待确认事项**：无——D24~D27 方向已于 2026-10-15 确认（D27 按"驱动直接在 pom 中引用"定稿），细则以本文为准。

***

## 15. 已知限制与演进路径

### 15.1 已知限制（补充需求 §15）

1. **不在扫描范围（v1 已确认的范围边界，决策 D23）**：普通 `QueryWrapper`（字符串列名链式，包括 `new QueryWrapper<>()` 空条件全表扫描写法）与 `BaseMapper` 内置方法（`selectById/deleteById/…`）及 MP 逻辑删除自动追加条件，均不提取、不分析、报告中不体现（需求 FR-2 范围界定）；建议后续迭代评估是否纳入。
2. Lambda 类的运行期条件（如 `eq(flag, col, v)` 中 flag 为变量）按保守策略丢弃，可能抬高误报率（设计预期内）。
3. 方法外部传入的 wrapper、跨文件的条件拼装无法完整还原（已降级为 `uncertain`，见 D11）。
4. 差异行映射为启发式（§8.1），极端多 hunk 场景可能把未变更语句判为 MODIFIED（保守方向，可接受）。
5. 元数据缺失即"无索引"的语义会显著放大 M1 报警量（需求既定策略，非缺陷）。
6. 元数据加载要求运行期能连通数据库且具备系统表读取权限（FR-8 既定策略）；连接过程新增运行期外部依赖（数据库可达性）。
7. v1 仅实现 MySQL 方言；Oracle/PostgreSQL 的方言实现（含分页子句识别）未提供，配置未实现值将直接报错（FR-9 预留，非缺陷）。

### 15.2 演进路径（预留接口已就位）

| 演进项                          | 落点                                             |
| ---------------------------- | ---------------------------------------------- |
| 线上运行版本 commit 自动获取           | 实现 `ProductionCommitProvider`，主流程零改动            |
| 表结构/索引元数据自动获取（连库或数据字典）       | ✅ 已纳入 v1.8（D24，JDBC 一次加载）；数据字典等其它来源经 `MetadataProvider` SPI 扩展 |
| Oracle / PostgreSQL 方言支持     | 实现 `SqlDialect`（含分页识别）+ 注册 + 用例，核心与规则零改动（D25）     |
| 执行计划分析（EXPLAIN）              | `ExecutionPlanAnalyzer` 占位接口 + P6 后插入新阶段        |
| CI/CD 门禁与评审平台集成              | 退出码（0/1/2）已可编程判断；报告 JSON 为稳定契约                 |
| 团队自定义规则                      | `ServiceLoader<RuleProvider>` 扩展点，不动核心流程       |

***

*文档完。v1.8 按扩展要求修订：元数据来源改为数据库运行时加载、文件通道移除（D24），新增 `SqlDialect` 方言 SPI 及 MySQL 基线/多方言预留（D25），连接配置三通道与密码优先级（D26），驱动**直接在 pom 中引用**（随 shaded jar）与 H2 测试载体（D27 已确认）。全部决策已确认；详细设计同步更新（v1.8，含 M10/M11 实现回写）。*
