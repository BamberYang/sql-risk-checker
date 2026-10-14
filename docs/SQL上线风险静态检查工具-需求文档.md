# SQL 上线风险静态检查工具 —— 需求文档

***

## 1. 文档信息



| 项    | 内容                 |
| ---- | ------------------ |
| 文档名称 | SQL 上线风险静态检查工具需求文档 |
| 版本   | v1.0               |



***

## 2. 背景与问题陈述

**现状问题：**



* 开发提交的代码中存在高风险 SQL（典型如全表扫描、`UPDATE/DELETE` 无 `WHERE`）。

* 上述问题 SQL 在**代码评审阶段未被发现**，上线后引发生产事故。

* 测试环境数据量与生产**不在一个量级**，问题 SQL 在测试阶段无法暴露。

**要解决的问题：**

在每次发布前，对本次变更涉及的 SQL 进行风险检查，提前发现风险内容。



***

## 3. 目标

设计并实现一款 **Java 编写的代码质量检查工具（CLI 工具）**，具备：



1. 基于 **Git 差异（diff）** 分析，仅检查本次变更涉及的 SQL。

2. 提取差异中的 **新增、修改** SQL（不含删除）。

3. 对 SQL 进行风险分析，按 **高危 / 中危 / 低危** 三级分类并给出提示。

4. 采用**保守策略**，动态 SQL 中所有 `<if>` 条件一律视为 `FALSE`，最大化暴露 "漏条件" 类风险。

5. 支持 **mapper.xml** 与 **MyBatis-Plus** 两种 SQL 承载形式。

6. 支持通过 **statement id** 配置忽略白名单。



***

## 4. 范围

### 4.1 范围内（In Scope）



* 基于 Git 差异的 SQL 变更提取。

* 未输入 Commit Id 时，默认全量分析

* 对提取到的 SQL 进行解析与静态风险分析。

* 排除白名单语句

* 三级风险分级。

* 控制台 + 结构化（JSON）报告输出。

### 4.2 范围外（Out of Scope / 非目标）



| 非目标项                      | 说明            | 处理方式               |
| ------------------------- | ------------- | ------------------ |
| 生产 commit id 自动获取         | 可先不实现         | 本版由**外部输入**传入，接口预留 |
| SQL 执行计划分析                | 暂时不连数据库       | 本版本不实现，预留实现路径      |
| 删除类 SQL 分析                | 不需要分析         | 不做                 |
| CI/CD/ Maven / 代码评审平台自动接入 | 本版仅 `main` 触发 | 预留扩展点，不做集成         |
| 非 SQL 类风险检查（代码规范、安全漏洞等）   | 工具定位为 SQL 专项  | 不做                 |
| 动态 SQL 运行时求值（真实参数注入）      | 静态场景无法确定真实参数  | 采用保守策略替代           |



***

## 5. 术语表



| 术语           | 定义                                                                                                         |
| ------------ | ---------------------------------------------------------------------------------------------------------- |
| 差异代码（diff）   | 当前分支最新 commit 相对生产 commit 的代码差异；**未输入生产 commit 时，改为对仓库全量分析**                                               |
| 生产 commit    | 线上运行版本对应的 commit id，本版由外部输入提供                                                                              |
| statement id | MyBatis 中一条 SQL 语句的唯一标识；mapper.xml/ 注解为 `namespace.id`（全限定名 + 方法名），Lambda 类 SQL 为「文件名（不含路径）+ 行号」，且不参与白名单校验 |
| Lambda 类 SQL | MyBatis-Plus 基于 Lambda 表达式构造的查询 / 更新（如 `LambdaQueryWrapper`），通过 `SFunction` 解析列名并还原为 SQL 骨架                |
| 全表扫描         | 查询无有效过滤条件，逐行扫描整张表                                                                                          |
| 命中索引         | 查询条件能够利用到对应列的索引                                                                                            |
| 笛卡尔积         | 多表 JOIN 且无关联条件，产生 M×N 行结果                                                                                  |
| 保守策略         | 静态分析时对无法确定的动态分支采用最不利假设（`<if>` 视为 FALSE）                                                                    |



***

## 6. 总体流程



```
① 输入装配                ② 差异获取         ③ SQL 提取          ④ 解析与规范化
[当前分支commit]  ──┐
[生产commit(可选)] ──┼─►  已输入 prod-commit ? → git diff
[配置文件]       ──┘         未输入           → 全量扫描仓库
                                      │
                                      ▼
                      提取新增/修改的 statement(SQL)  →  解析为 AST
                                      │
                                      ▼
⑤ 风险规则分析      ⑥ 白名单过滤      ⑦ 结果汇总
高危/中危/低危  ──►  按 statement id    ──►  控制台摘要 + JSON 报告
规则引擎          忽略已配置条目
```



***

## 7. 功能需求（FR）

### FR-1 差异获取



* **输入**：`当前分支最新 commit`、`生产 commit`（均为**可选**）。


  * **未输入 commit（含未输入生产 commit）时，默认全量分析**：跳过 diff，直接对仓库全量扫描 mapper.xml、MyBatis-Plus 注解及 Lambda 构造中的 SQL，而非仅分析差异代码。

  * 输入了生产 commit 时（差异模式），生产 commit 由**外部输入**（命令行参数）提供；预留 `ProductionCommitProvider` 接口，后续接入 "调用接口获取线上运行版本 commit id" 能力时替换实现，不影响主流程。

* **处理**：


  * 差异模式：执行 `git diff <productionCommit> <headCommit> --stat / -- <path>`，限定在目标代码仓库目录内。

  * 全量模式：跳过 git diff，直接扫描仓库内全部 SQL 承载文件。

* **输出**：变更文件清单及对应 diff 内容（差异模式）/ 全量 SQL 承载文件清单（全量模式）。

* **边界**：仓库目录不存在 / 非法 commit id / 非 Git 仓库，须给出明确错误并终止（见 §11 错误处理）。

### FR-2 SQL 变更提取



* **范围**：仅提取 **新增、修改** SQL；**排除删除**（差异模式下为 diff 中被删除的行；全量模式下不涉及删除判断）。

* **承载形式支持**：

1. **mapper.xml**：解析 `namespace` + `<select/update/insert/delete>` 的 id，读取 SQL 文本，包括其中动态标签 `<if>/<choose>/<when>/<otherwise>/<where>/<trim>/<foreach>/<set>`。

2. **MyBatis-Plus / 注解式**（含两类）：

* **注解类 SQL**：识别接口方法上的 `@Select/@Update/@Insert/@Delete/@SelectProvider/@InsertProvider` 等注解中的 SQL 文本。

* **Lambda 类 SQL**：识别基于 Lambda 的查询 / 更新构造（`LambdaQueryWrapper`、`LambdaUpdateWrapper`、`lambdaQuery()/lambdaUpdate()`、`Wrapper.lambda()` 等），通过 `SFunction`（`Entity::getXxx`）解析出列名，并结合泛型表名与链式条件方法还原为可分析的 SQL 骨架，再参与风险分析；**支持 MyBatis-Plus 全部链式方法及复杂嵌套**（`and/or/apply/exists/嵌套条件组` 等）。

* **Lambda 实体→表 / 列映射**：解析实体类上的 `@TableName`/`@TableField` 注解确定真实表名与列名；未标注时按默认「驼峰 → 下划线」转换。

- **输出**：`List<SqlChange>`，元素含 `statementId`、变更类型（新增 / 修改）、原始 SQL、承载文件与行号。


  * **Lambda 类 SQL 的 statementId 生成规则**：以「文件名（不含路径）+ 代码行数」生成，如 `OrderServiceImpl.java:86`；该标识**不参与白名单校验**（见 FR-6）。

- **边界**：


  * 同一 statement 同时改动（一行删一行改），归类为 "修改"。

  * 无法识别的 SQL 承载形式（非上述两种）记录为 "跳过项" 并在报告中列出（报告 `skipped` 字段，见 §10.2），不静默丢弃。

  * 模板变量 / 占位符（`#{}`、`${}`）保留原文参与静态分析；SQL 含 `${}` 时**直接归属中危**（见 §8 M8），不再继续判断其他规则。

### FR-3 SQL 解析与规范化



* 使用 SQL 解析器将每条 SQL 解析为 **AST**，支持 `SELECT / UPDATE / INSERT / DELETE`。

* **动态标签规范化**：对 mapper.xml 动态 SQL，先按 "动态标签求值" 得到静态骨架再解析：


  * **保守策略（FR-5）**：`<if>` 条件一律视为 `FALSE`（不纳入）；`<choose>` 分别求值判断：`<when>` 条件成立 与 `otherwise` 成立 各生成一份 SQL 骨架、各判断一次，**任一骨架命中即停止**（见 FR-5）。

  * `<foreach>`、`<where>/<trim>/<set>` 按标准行为折叠处理。

* **Lambda 类 SQL 骨架还原**：对 MyBatis-Plus Lambda 构造（见 FR-2），按「`@TableName`/`@TableField` 注解 + 默认驼峰转下划线」完成表 / 列映射后还原为 SQL 骨架，同样进入 AST 解析；其中受运行时参数控制的链式条件按 \*\* 保守策略（FR-5）\*\* 视为不成立，避免漏条件类风险漏报。

* 解析失败（语法无法识别）时：不崩溃，标记为 "解析失败项"，归入**高危待人工复核**并输出原因（如 "含无法解析的动态片段"）。

### FR-4 风险分析（三级规则）



* 对每条 SQL 依次应用规则，输出**规则 ID + 风险级别 + 命中原因 + 相关片段**；通常可同时命中多级多条规则，但**短路规则**（如 \${}，见 §8 M8）命中后不再继续判断其余规则。

* 具体默认规则集见 **§8**。

### FR-5 保守策略



* **语义**：静态分析时，对动态 SQL 中无法在编译期确定的 `IF` 条件，一律按 **FALSE** 处理。


  * `<if test="...">`：条件视为 FALSE，分支内容**不纳入** SQL 骨架。

  * `<choose>`：对 `<when>` 条件成立 与 `<otherwise>` 成立 **各生成一份 SQL 骨架、各判断一次**；**任一骨架命中任一风险规则即判定该语句命中，不再继续判断其余骨架**。

  * Lambda 链式条件：受运行时参数控制的链式条件按保守策略不纳入，暴露漏条件风险（见 FR-3）。

* **目的**：避免 " 条件为真时漏掉 `WHERE`/ 漏加关联条件 "的风险被低估，最大程度暴露" 可能产生全表扫描 / 无过滤更新删除 " 的隐患。

* **落地方式**：各分支分别折叠进入 AST 判定；由分支缺失 `WHERE`、JOIN 无条件等最坏情形触发对应规则。

### FR-6 白名单（statement id 忽略）



* 支持在配置文件中配置一个或多个 `statementId`，命中白名单的语句**不参与风险分析**（但可保留在 "已忽略" 清单输出）。

* 匹配模式：仅支持**完整匹配**与 `namespace.*` 前缀匹配 \*\* 两种，**不再支持其他匹配模式**（见 §9）。

* **作用范围**：白名单仅针对 mapper.xml 与注解类 SQL 的 `statementId`；**Lambda 类 SQL 不参与白名单校验**（其 statementId 为「文件名：行号」，见 FR-2）。

### FR-7 执行入口与命令行参数



* 通过 `main` 方法触发，参数定义：



| 参数                    | 必填 | 说明                                              |
| --------------------- | -- | ----------------------------------------------- |
| `--repo <path>`       | 是  | 目标 Git 仓库绝对路径                                   |
| `--head-commit <sha>` | 否  | 当前分支最新 commit，缺省取当前工作区 HEAD                     |
| `--prod-commit <sha>` | 否  | 生产 commit id（本版外部输入）；**不传时对仓库进行全量分析**           |
| `--config <path>`     | 否  | 规则 / 白名单配置文件路径，缺省用默认配置                          |
| `--output <path>`     | 否  | 报告输出目录，缺省输出到当前目录                                |
| `--format json`       | 否  | 指定结构化输出（默认控制台 + JSON 双输出）                       |
| `--metadata <path>`   | 否  | 表元数据 JSON 文件路径；缺省读取仓库根 `sql-risk-metadata.json` |



* 退出码约定：`0` 无高危；`1` 存在高危风险；`2` 参数 / 环境错误（便于程序化判断）。



***

## 8. 风险分级与默认规则集

> 规则 = 级别 + 匹配条件 + 提示。级别可调、开关可配（见 §9）。
>
> **短路规则**
>
> （如 ${}，M8）命中后直接定级，不再继续判断该语句的其他规则。

### 8.1 高危（HIGH）—— 必然或大概率导致生产事故



| 规则 ID | 命中条件                                                                  | 提示示例                         |
| ----- | --------------------------------------------------------------------- | ---------------------------- |
| `H1`  | `UPDATE` 无 `WHERE` 子句                                                 | "UPDATE 语句缺少 WHERE，将全表更新，高危" |
| `H2`  | `DELETE` 无 `WHERE` 子句                                                 | "DELETE 语句缺少 WHERE，将全表删除，高危" |
| `H3`  | `SELECT` 全表扫描：无 `WHERE` 且无 `LIMIT`                                    | "SELECT 无过滤条件，存在全表扫描风险"      |
| `H4`  | `UPDATE/DELETE/SELECT` 的 `WHERE` 在保守策略下整体缺失（如唯一条件在 `<if>` 内且视为 FALSE） | "WHERE 条件位于动态分支内，可能整体缺失，高危"  |
| `H5`  | `SELECT *` 且无任何过滤 / 限制条件                                              | "SELECT \* 且无过滤，全表扫描"        |

### 8.2 中危（MEDIUM）—— 索引 / 性能隐患，可配置



| 规则 ID | 命中条件                                                | 提示示例                               |
| ----- | --------------------------------------------------- | ---------------------------------- |
| `M1`  | 无法命中索引：过滤列未被识别为索引列；**元数据缺失时默认该表无任何索引**              | "条件列未命中索引"（索引信息由表元数据 JSON 提供，见 §9） |
| `M2`  | 索引列被函数包裹（`WHERE FUNC(col)=...`）                     | "索引列被函数包裹，无法走索引"                   |
| `M3`  | `LIKE '%xxx'`（前导通配符）                                | "前导通配符 LIKE 无法走索引"                 |
| `M4`  | 隐式类型转换（数字列 vs 字符串，依据表元数据字段类型判定）                     | "存在隐式类型转换，可能无法命中索引"                |
| `M5`  | 多表 JOIN 无关联条件（笛卡尔积）                                 | "多表连接缺少关联条件，可能产生笛卡尔积"              |
| `M6`  | `OR` 连接非索引条件                                        | "OR 连接可能导致全表扫描"                    |
| `M7`  | `UPDATE/DELETE` 无 `LIMIT` 的大批量（可配置阈值）；**不含 INSERT** | "无 LIMIT 的批量更新 / 删除，生产量大时风险"       |
| `M8`  | SQL 含 `${}` 拼串（**短路规则**：命中即归属中危，不再继续判断其他规则）         | "存在 \${} 拼串，归属中危，不再继续判断"           |

### 8.3 低危（LOW）—— 不推荐写法 / 关键字，可配置



| 规则 ID | 命中条件                   | 提示示例           |
| ----- | ---------------------- | -------------- |
| `L1`  | 使用不推荐关键字 / 写法（可配置关键字表） | "使用了不推荐写法：xxx" |
| `L2`  | `SELECT *`（存在但已有过滤）    | "建议显式列出字段"     |
| `L4`  | 未加 `LIMIT` 的列表查询       | "建议限制返回行数"     |



***

## 9. 配置设计

采用 `properties` 或 `yaml`（【假设】默认 `yaml`，可扩展）承载规则、白名单等配置；**表元数据除外**—— 使用独立的 **JSON 文件**（`table-metadata.json`）描述，不放入 yaml（见 9.1）。yaml 中仅通过 `metadata_file` 指定该 JSON 文件路径。



```
# rules: 内置规则的级别调整与开关（同 ruleId 覆盖）
rules:
  - ruleId: H1
    level: HIGH
    enabled: true
  - ruleId: M1
    level: MEDIUM
    enabled: true

# 表元数据：由独立的 JSON 文件提供（见 9.1），此处仅指定文件路径
metadata_file: ./table-metadata.json

# 低危不推荐写法关键字表
low_risk_keywords:
  - "SELECT *"
  - "OR 1=1"

# 白名单：命中则跳过分析；仅支持完整匹配 与 namespace.* 前缀匹配
whitelist:
  statements:
    - "com.demo.mapper.OrderMapper.selectById"   # 精确
    - "com.demo.mapper.*"                          # 前缀

# 保守策略开关（默认 true）
conservative_if_always_false: true

# 批量阈值（M7 参考，仅针对 UPDATE/DELETE，不含 INSERT）
bulk_operation_threshold: 1000
```

### 9.1 表元数据（独立 JSON 文件）

表元数据使用独立的 JSON 文件描述（**不在 yaml 中配置**），由 `--metadata <path>` 或 yaml 的 `metadata_file` 指定路径。每条表记录包含：



| 字段        | 说明                                                        |
| --------- | --------------------------------------------------------- |
| `table`   | 表名                                                        |
| `fields`  | 该表的**全部字段列表**，每项含 `name`（字段名）与 `type`（字段类型，供 M4 隐式类型转换判定） |
| `indexes` | 索引信息，每项含 `name`（索引名）与 `columns`（索引字段）                     |

示例 `table-metadata.json`：



```
{
  "version": "1.0",
  "tables": [
    {
      "table": "t_order",
      "fields": [
        { "name": "id", "type": "BIGINT" },
        { "name": "order_no", "type": "VARCHAR(64)" },
        { "name": "user_id", "type": "BIGINT" },
        { "name": "status", "type": "INT" }
      ],
      "indexes": [
        { "name": "PRIMARY", "columns": ["id"] },
        { "name": "idx_order_no", "columns": ["order_no"] },
        { "name": "idx_user_id", "columns": ["user_id"] }
      ]
    }
  ]
}
```

> 规则：表未出现在元数据文件中时，默认该表无任何索引（见 §8 M1、§15）。后续可通过 
>
> `MetadataProvider`
>
>  接口从外部数据源获取（见 §16）。



***

## 10. 输入 / 输出接口定义

### 10.1 输入



* Git 仓库路径、head commit、prod commit（可选）、配置文件、表元数据 JSON（可选）、输出目录（见 FR-7）。

### 10.2 输出



* **控制台摘要**：汇总各级风险数量 + 高危列表。

* **JSON 报告**（推荐落地到文件 `sql_risk_report.json`；报告内 `statementId`：mapper.xml/ 注解为 `namespace.id`，Lambda 类 SQL 为「文件名：行号」）：



```
{
  "generatedAt": "2026-10-13T22:00:00+08:00",
  "headCommit": "abc123",
  "prodCommit": "def456",
  "summary": { "total": 12, "high": 2, "medium": 5, "low": 5, "ignored": 1, "skipped": 1, "parseFailed": 0 },
  "results": [
    {
      "statementId": "com.demo.mapper.OrderMapper.updateBatch",
      "file": "src/main/resources/mapper/OrderMapper.xml",
      "line": 42,
      "changeType": "MODIFIED",
      "sql": "UPDATE t_order SET status=?",
      "findings": [
        { "ruleId": "H2", "level": "HIGH", "message": "DELETE/UPDATE 缺少 WHERE", "snippet": "UPDATE t_order SET status=?" }
      ]
    }
  ],
  "ignored": [ { "statementId": "com.demo.mapper.OrderMapper.selectById", "reason": "whitelist" } ],
  "skipped": [ { "statementId": "com.demo.mapper.SomeMapper.unknownForm", "reason": "无法识别的 SQL 承载形式" } ],
  "parseFailed": [ { "statementId": "xxx", "reason": "解析失败：...", "sql": "..." } ]
}
```



***

## 11. 非功能需求（NFR）



| 类别   | 要求                                                            |
| ---- | ------------------------------------------------------------- |
| 性能   | 单次分析（1k 文件以内 diff）应在 30s 内完成；解析失败不阻塞整体                        |
| 兼容性  | 运行于 Java 8；无网络、不连库，离线可运行                                      |
| 可维护性 | 规则引擎与解析 / 提取解耦；新增规则不改核心流程                                     |
| 可测试性 | 每个规则、每种承载形式、保守策略均有可单测覆盖的样例                                    |
| 错误处理 | 非法 commit / 非 git 仓库 / 配置错误 / 解析失败均给出明确退出码与提示（见 FR-7、FR-2 边界） |
| 依赖   | 最小化第三方依赖；SQL 解析器选型见 §12                                       |



***

## 12. 技术选型建议



| 项            | 建议                                          | 说明                               |
| ------------ | ------------------------------------------- | -------------------------------- |
| 语言 / 运行时     | **Java 8**                                  | 契合 "main 方法触发"                   |
| 构建           | Maven                                       | 主流通用                             |
| Git 差异       | **JGit**                                    | 需要 `git diff` 能力                 |
| SQL 解析       | **Alibaba Druid SQL 解析器**（`SQLParserUtils`） | 需支持 MySQL 语法 + 动态 SQL 预处理        |
| XML 解析       | `dom4j` 或 JDK 自带 `DocumentBuilder`          | 解析 mapper.xml                    |
| 配置           | `yaml`（SnakeYAML）                           | 便于规则扩展                           |
| MyBatis-Plus | **3.5.x**                                   | Lambda 链式方法与 `SFunction` 机制的版本基准 |
| Java 源码解析 | **JavaParser**（备选：Eclipse JDT）             | 解析 Lambda 构造与 `@TableName`/`@TableField` 注解，供 Lambda extractor 使用 |

> 已确认：目标数据库固定为
> **MySQL**
> ；SQL 解析器采用
> **Druid SQL**
> ；Git 差异采用
> **JGit**
> 。



***

## 13. 模块划分（初步）



```
sql-risk-checker
├── cli               # main 入口、参数解析、退出码
├── diff              # 差异获取（git diff），ProductionCommitProvider 接口
├── extractor         # SQL 变更提取（mapper.xml / 注解 / Lambda 构造），SqlChange 模型
├── parser            # 动态 SQL 规范化 + AST 解析
├── rule              # 规则引擎：内置规则加载、配置级别调整/开关、规则执行
├── report            # 控制台摘要 + JSON 报告生成
└── config            # 配置加载与校验
```



***

## 14. 验收标准（AC）

> 开发完成后，以下标准须全部通过方可视为交付：



| 编号    | 验收项             | 通过条件                                                                         |
| ----- | --------------- | ---------------------------------------------------------------------------- |
| AC-1  | 差异分析            | 给定 head/prod commit，仅输出差异涉及的新增 / 修改 SQL，删除 SQL 不出现                           |
| AC-2  | 全量分析            | 未输入 prod-commit 时，对仓库全量 mapper.xml/ 注解 SQL 提取并分析                             |
| AC-3  | 高危识别            | 对含 `UPDATE/DELETE` 无 `WHERE` 的样例 SQL 必报高危（H1/H2）                             |
| AC-4  | 保守策略            | `<if>` 唯一条件按 FALSE 报高危（H4）；`<choose>` 各判断一次，任一骨架命中即判定命中                      |
| AC-5  | mapper.xml 支持   | 动态标签（if/choose/foreach/where/trim/set）样例可正确提取并分析                             |
| AC-6  | MyBatis-Plus 支持 | 注解类 SQL 与 Lambda 类 SQL（全部链式方法、复杂嵌套）样例均可正确提取并分析                               |
| AC-7  | 白名单             | mapper.xml/ 注解 的 statement id 命中白名单被忽略，且出现在 ignored 清单；Lambda 类 SQL 不参与白名单校验 |
| AC-8  | 报告              | 控制台摘要 + JSON 结构与 §10.2 一致，字段齐全                                               |
| AC-9  | 边界容错            | 非 git 仓库 / 非法 commit / 配置错误 / 解析失败均按约定退出码返回，不抛未捕获异常                          |
| AC-10 | 测试              | 核心规则、提取、解析均有单测覆盖，构建通过                                                        |
| AC-11 | `${}` 短路        | SQL 含 `${}` 时直接归属中危（M8），且不再输出其他规则命中                                          |



***

## 15. 已知限制



* 生产 commit 依赖外部输入，无法保证与线上完全一致（本版设计使然）。

* 静态分析无法覆盖运行时动态 SQL 的真实参数注入场景，采用保守策略规避漏报。

* 索引命中类规则（M1/M2/M4）依赖表元数据 JSON 的准确性（表名、全部字段列表与类型、索引信息）；**表未出现在元数据中时默认该表无任何索引**，M1 按未命中索引判定（宁可误报，避免漏报）；M4 在字段类型缺失时退化为不判定。

* 对无法解析的动态 SQL 片段标记为 "待人工复核"，可能存在少量误报 / 漏报。



***

## 16. 已确认决策记录

> 以下决策已由需求方确认，开发按此执行：



| 决策项                         | 结论                                                              |
| --------------------------- | --------------------------------------------------------------- |
| 目标数据库                       | 固定 **MySQL**                                                    |
| SQL 解析器                     | **Alibaba Druid SQL**（`SQLParserUtils`）                         |
| Git 差异实现                    | **JGit** 内嵌                                                     |
| 生产 commit 获取                | 本版由外部输入；接口契约已由 FR-1 `ProductionCommitProvider` 预留，后续迭代实现线上获取    |
| 索引 / 表结构信息来源                | 独立的表元数据 JSON 文件（含表名、全部字段列表与类型、索引信息），不在 yaml 中配置；预留接口，后续从外部数据源获取 |
| 报告消费方                       | 目前仅输出结构化数据（JSON）+ 控制台摘要，暂不接 CI 门禁                               |
| 白名单匹配                       | 仅支持完整匹配 与 `namespace.*` 前缀匹配，不支持其他模式                            |
| Java 版本                     | **Java 8**                                                      |
| 交付文档载体                      | Markdown（本文件）                                                   |
| 自动集成（CI/CD、评审平台）            | 暂不考虑，维持范围外                                                      |
| Lambda 类 SQL 的 statement id | 用「文件名（不含路径）+ 代码行数」生成（如 `OrderServiceImpl.java:86`），**不参与白名单校验** |
| choose/when/otherwise 保守语义  | `<when>` 条件成立 与 `<otherwise>` 成立 各生成一份 SQL 骨架、各判断一次             |
| 元数据缺失时索引判定                  | 默认该表**无任何索引**，M1 按未命中索引判定                                       |
| Lambda 链式方法支持范围             | 支持 MyBatis-Plus **全部链式方法及复杂嵌套**（and/or/apply/exists 等）          |
| 大批量 insert                  | 暂不考虑，M7 仅针对 UPDATE/DELETE                                       |
| 表元数据形式                      | 独立的 JSON 文件（`table-metadata.json`，含表名、全部字段与类型、索引信息），不在 yaml 中配置 |
| Lambda 实体→表 / 列映射           | 解析 `@TableName`/`@TableField` 注解；未标注时按默认「驼峰 → 下划线」转换            |
| MyBatis-Plus 版本             | 锁定 **3.5.x**                                                    |
| `${}` 处理                    | SQL 含 `${}` 直接归属**中危**（M8），命中后不再继续判断其他规则                        |
| choose 多骨架判定                | 各生成一份骨架各判断一次；**任一骨架命中任一风险规则即判定该语句命中，不再继续判断其余骨架**                |

> 遗留假设：配置文件格式默认采用
> `yaml`
> （如偏好
> `properties`
> 可在编码前调整，不影响架构）。



***

*文档完。§16 已记录确认的决策项；其余默认取值见文中【假设】标注。*