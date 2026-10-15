# SQL 上线风险静态检查工具（sql-risk-checker）

基于 **Git 差异（diff）** 的 MyBatis SQL 静态风险检查 CLI：**只提取本次变更涉及的 SQL**（mapper.xml / 注解 SQL / MyBatis-Plus Lambda 构造），结合**数据库真实表结构（索引/字段）**做静态分析，按 **高危 / 中危 / 低危** 三级规则输出控制台摘要 + JSON + HTML 报告。

> 当前状态：**M0~M9 全部里程碑 + 扩展 M10（数据库元数据，JDBC）/ M11（方言 SPI）/ M13（PostgreSQL 方言）均经确认；真实 MySQL 与 PostgreSQL 连库验证完成**——323 用例全绿（含 15 个黄金样例）。SQL 解析支持方言扩展（内置 MySQL/PostgreSQL，Oracle 预留）；表元数据为**数据库运行时一次加载**的只读快照。

***

## 1. 它解决什么问题

上线前的人肉 SQL Review 有三个绕不开的痛点，本工具针对性地把它们变成一条可自动化的检查线：

| 痛点                                                                            | 本工具的做法 |
|-------------------------------------------------------------------------------| --- |
| **人工 Review 靠不住**：`UPDATE`/`DELETE` 忘写 `WHERE`、`LIKE '%x%'` 走不了索引——这类问题肉眼容易漏  | 13 条内置规则按三级风险定级；高危 `UPDATE/DELETE 无 WHERE`、全表扫描等**命中即 exit 1**，可直接卡住流水线 |
| **全量扫描噪音大**：全仓几万条 SQL，绝大多数与本次上线无关，报出来也没人看                                     | 只分析 **prod → head 之间变更的语句**；行级命中即提取**完整语句**；`<sql>` 片段变更自动传播到全部引用语句（含跨文件、传递） |
| **动态 SQL 看不清**：`<if>`/`<choose>`、Wrapper 的 `if/else` 拼条件，静态看语句"有WHERE"，实际运行没有 | 保守策略固定启用：无法确定的动态条件一律**按最不利假设**求值并附注说明来源；`<choose>`/`switch` 按分支枚举骨架，任一骨架命中即报告 |

适用场景：后端团队上线前自检、CI 流水线卡点、DBA/架构师抽查。**不修改目标仓库、不执行任何业务 SQL、不访问互联网**——对数据库只有一次只读元数据查询。

## 2. 工作原理

一次运行的管线（P0~P9）：

```
参数装配 → 配置/元数据(JDBC 一次加载) → Git diff(提交树)
   → 语句提取(xml/注解/Lambda) → 白名单过滤 → 骨架生成(保守求值)
   → Druid 解析(按方言) → 规则引擎(语句级短路→骨架循环) → 汇总 → 报告
```

三条关键设计约束（决定了你会看到的输出形态）：

- **数据源恒为提交树**：DIFF 两端（prod/head）与 FULL 遍历都只读**已提交内容**，工作区未提交的改动永不参与——检查的是"将要上线的那个版本"；
- **三种承载形态收敛为同一条管道**：无论 SQL 写在 mapper.xml、注解还是 Lambda 链上，都先还原为统一的「SQL 骨架文本」再做解析与规则判定，规则只实现一次；
- **保守策略（无法关闭）**：`<if>` 恒按不成立折叠（条件因此"消失"时，H1~H3 附注提示"WHERE 条件位于动态分支内"）；Lambda 运行期布尔条件直接丢弃；`${}` 拼接（语句级短路规则 M8）出现在被丢弃分支里也照常命中。

## 3. 快速开始

**前置条件**：

1. JDK 8+（产物为 Java 8 字节码）；
2. 一个能连上的**业务库**（元数据来源，必需）——建议使用**只读账号**；工具只执行 `getTables/getColumns/getIndexInfo` 元数据查询，不读业务数据；
3. 待检查的 Git 仓库（本地路径即可，只读）。

### 3.1 方式一：命令行运行（打包产物）

```bash
# ① 构建（首次需联网拉依赖；之后全程可离线）
mvn clean package            # 首次
mvn -o clean package         # 之后离线构建
# 产物：target/sql-risk-checker-shaded.jar（已内嵌 MySQL/PostgreSQL JDBC 驱动）

# ② 全量扫描（最简形态：不传 --prod-commit 即 FULL 模式）
java -jar target/sql-risk-checker-shaded.jar \
    --repo /path/to/your-repo \
    --db-url 'jdbc:mysql://127.0.0.1:3306/your_db' \
    --db-username reader \
    --db-password '******' \
    --output ./report

# ③ 看结果
#   - 控制台：摘要 + 高危明细
#   - ./report/sql_risk_report.json  （机器契约，CI 消费）
#   - ./report/sql_risk_report.html  （阅读层，浏览器直接打开）
```

### 3.2 方式二：IDE 直接运行 `main` 方法（开发/调试）

不打包，直接在 IDE 中运行入口类 `com.demo.sqlrisk.cli.SqlRiskCheckerMain#main`（IntelliJ 打开工程后 Run/Debug 即可）：

- **空参运行（调试默认参数）**：不配置 Program arguments 直接运行——入口检测到 `argv` 为空时，启用
  `SqlRiskCheckerMain.DEBUG_DEFAULT_ARGV` 内置默认参数（本地验证仓库 + 本地库连接 + `/tmp` 输出 + `--verbose`），
  并向 stderr 打印 `[调试] argv 为空，使用内置默认参数：…` 提示（不静默落入错误配置）；
- **默认值指向开发者本机路径**：换机器/换目标仓库时，改 `DEBUG_DEFAULT_ARGV` 里的 `--repo/--db-url/--db-username` 即可；
  密码不写入代码——用环境变量 `SQL_RISK_DB_PASSWORD`（IDE 运行配置的 *Environment variables*）或临时加 `--db-password`；
- **带参运行**：在 IDE 运行配置的 *Program arguments* 中按 §4.3 参数表填写，与命令行完全等价；
- **正式运行（CI/脚本）请始终显式传参**，不要依赖调试默认值。

## 4. 使用详解

### 4.1 两种模式

| 模式 | 触发方式 | 分析范围 | 典型用法 |
| --- | --- | --- | --- |
| **DIFF** | 传 `--prod-commit <sha>` | prod 提交 → head 提交之间的**变更语句**（含 `<sql>` 片段传播） | 上线卡点：本次要发的 commit 对生产 commit 的差异 |
| **FULL** | 省略 `--prod-commit` | head 提交树的**全部语句**（changeType 恒为 `FULL`） | 首次接入摸底、存量治理 |

`--head-commit` 可指定 head（缺省取仓库 HEAD）；DIFF 模式下若 prod == head 则视为空差异（0 条语句，正常运行结束）。

### 4.2 配置文件（可选，覆盖内置默认）

```yaml
# sql-risk.yaml —— 三层合并：内置默认 < 本文件 < CLI 参数
datasource:                      # 元数据来源（必需）
  url: "jdbc:mysql://127.0.0.1:3306/your_db"
  username: "reader"             # 建议只读账号
  password: ""                   # 建议留空，用环境变量 SQL_RISK_DB_PASSWORD 提供
  connection_timeout_seconds: 10

dialect: mysql                   # mysql | postgresql（oracle 为预留值，配置将报错）

rules:                           # 按 ruleId 覆盖 级别/启停（优先级与短路标记不可配置）
  - ruleId: M2
    level: HIGH
  - ruleId: M5
    enabled: false

low_risk_keywords:               # L1 关键字表（整体替换；大小写不敏感）
  - "SELECT *"
  - "OR 1=1"

whitelist:                       # 白名单：完整匹配 或 namespace.* 前缀匹配（仅 xml/注解语句参与）
  statements:
    - "com.demo.mapper.ReportMapper.selectBig"
    - "com.demo.legacy.*"
```

**数据库连接三通道与密码优先级**：

```
--db-url/--db-username/--db-password（CLI） > 环境变量 SQL_RISK_DB_PASSWORD（密码） > yaml datasource
```

> 元数据为**必需输入**：启动时经 JDBC **一次加载**（表 / 字段类型 / 索引）为内存快照，加载后连接立即关闭，运行期不再连库；连接失败/权限不足/超时 → exit 2，无降级路径。**不在快照中的表按"无索引"保守判定**（M1 提示"表不在元数据中"）。

### 4.3 参数表

| 参数 | 说明 |
| --- | --- |
| `--repo <path>` | 目标 Git 仓库路径（**必填**；只读，数据源恒为提交树） |
| `--head-commit <sha>` | 当前分支最新 commit；缺省取 HEAD（仅分析已提交内容） |
| `--prod-commit <sha>` | 生产 commit；**提供则 DIFF 模式，省略则 FULL 模式** |
| `--config <path>` | 配置文件（yaml）；缺省用内置默认配置 |
| `--output <dir>` | 报告输出目录，缺省当前目录；文件名固定 `sql_risk_report.json` / `sql_risk_report.html` |
| `--format json` | 显式声明结构化输出（缺省即"控制台 + JSON + HTML"三产出，该参数为兼容保留） |
| `--dialect <name>` | SQL 方言 `mysql`\|`postgresql`，缺省 `mysql`（须与被检查代码的目标库一致） |
| `--db-url <url>` | 数据库 JDBC URL（元数据来源；未传读 yaml） |
| `--db-username <u>` | 数据库账号（未传读 yaml） |
| `--db-password <pwd>` | 数据库密码（优先级：CLI > 环境变量 `SQL_RISK_DB_PASSWORD` > yaml） |
| `--verbose` | 向 stderr 输出提取/白名单/解析/规则各阶段进度（stdout 报告契约不受影响） |
| `--help` | 用法与退出码说明 |

### 4.4 退出码与 CI 集成

| 退出码 | 含义 |
| --- | --- |
| `0` | 无高危（含"通过"与 SKIP——SKIP 表示"无法分析"，不影响退出码但会列在报告里） |
| `1` | 存在高危（`summary.high > 0`） |
| `2` | 参数 / 配置 / 数据库连接 / 仓库 / 报告写出等环境错误 |

CI 卡点示例（通用 shell，按平台放进对应 step 即可）：

```bash
java -jar sql-risk-checker-shaded.jar \
    --repo "$CI_PROJECT_DIR" --prod-commit "$LAST_DEPLOY_SHA" \
    --config ci/sql-risk.yaml --output build/sql-risk
case $? in
  0) echo "✅ 无高危，放行" ;;
  1) echo "⛔ 存在高危 SQL 变更，阻断上线（详见 build/sql-risk 报告）"; exit 1 ;;
  *) echo "⚠️ 工具/环境错误（exit 2），请检查连接与参数"; exit 2 ;;
esac
```

（若按 `--verbose` 做审计归档，注意其日志走 stderr，报告仍以 JSON 为唯一契约。）

## 5. 报告与解读

每次运行产出 **3 份**输出：

| 产物 | 定位 | 内容 |
| --- | --- | --- |
| 控制台摘要 | 人读（快） | 模式/commit 缩写、**高危 5 \| 中危 12 \| 低危 28 \| SKIP 0 \| 已忽略 0**、高危明细（含片段与命中说明） |
| `sql_risk_report.json` | **机器契约**（CI/脚本消费，黄金样例基准） | `summary` + `results[]`（每语句：`statementId/file/line/changeType/sql/findings/level/uncertain/changedVia`）+ `ignored[]` + `skipped[]` |
| `sql_risk_report.html` | 阅读层（交付评审） | 单文件离线、零外链；头部统计卡片（高危/中危/低危/**通过**/SKIP/已忽略），高危默认展开，其余折叠，支持按级别/规则/文件/关键字筛选 |

**口径（务必按此理解报告）**：

- `参与分析 total = 高危 + 中危 + 低危 + 通过`，其中"**通过**"= 参与分析但无任何命中（≠ 白名单忽略）；
- `提取总数 = total + SKIP + 已忽略`——SKIP 是"**无法分析**"（如含 `${}` 无法还原、解析失败、XML 结构错误），与"分析过且没问题"严格区分；
- 语句级 `uncertain=true`：还原不完整（如 Wrapper 由方法参数传入、`${}` 占位符代换），**结论可能偏少**，建议人工复核；
- `changedVia`：该语句因 `<sql>`/`<include>` 片段变更而派生命中时的来源片段；
- 命中级别取该语句所有 finding 的最高级（配置覆盖后的生效级别）。

## 6. 风险规则（13 条）

| 组 | 规则（默认级别可配置、启停可配置；优先级/短路标记固定） |
| -- | ---- |
| **H（高危）** | H1 `UPDATE` 无 `WHERE`；H2 `DELETE` 无 `WHERE`；H3 `SELECT` 无 `WHERE` 且无 `LIMIT` |
| **M（中危）** | M1 条件列未命中索引；M2 索引列被函数包裹；M3 前导通配符 `LIKE '%x'`；M4 隐式类型转换；M5 笛卡尔积（逗号 JOIN 且无跨表关联）；M6 `OR` 两侧存在非索引列；M8 `${}` 拼串（**语句级短路**，含被丢弃分支，注解中 `${ew.customSqlSegment}` 等 Wrapper 段除外） |
| **L（低危）** | L1 配置关键字（`low_risk_keywords`）；L2 `SELECT *` 且已有过滤；L4 有 `WHERE` 但无 `LIMIT`（未限制返回行数） |

补充语义：

- **M8 是语句级短路规则**：扫语句原文，命中即定级并结束该语句——含 `${}` 的"空骨架短路候选"也由此覆盖；若含 `${}` 但未命中 M8 且骨架无法生成 → 归 **SKIP**（不输出为"无风险"）；
- **分支枚举**：Lambda wrapper 在 `if/else`、`switch/case` 中追加条件时按分支生成独立骨架（对齐 XML `<choose>`，任一骨架命中即报告）；无 `else` 的 `if` / 循环体按 `<if>` 保守丢弃；
- 命中 H1~H3 但 WHERE 因保守折叠而"消失"时，消息附注提示"WHERE 条件位于动态分支内，按保守策略视为不成立"。

## 7. 已知边界与限制

- **不解析的范围**：普通 `QueryWrapper`（字符串列名链式）与 `BaseMapper` 内置方法不纳入扫描（D23）——它们不构成可静态还原的 SQL 骨架；
- **链式未收录方法**：MyBatis-Plus 方法表中未收录的链式调用 → 忽略该条件并标记 `unknown`（**不猜测语义**，体现为 `uncertain`）；
- **方言须匹配目标库**：用错方言会导致方言特有语法解析失败（例如 PG 的 `FETCH FIRST`/纯 `OFFSET` 用 `mysql` 解析）——此时语句归 **SKIP**（失败可见，不会静默错判）；Oracle 为预留值（明确报错，不降级为 MySQL）；
- **元数据必须可连**：没有数据库连接就无法运行（这是设计决策：拿不到真实索引，"M1/M2/M6" 的正确性无从谈起）；
- **保守策略意味着会"多报"**：`<if>` 一律按不成立、运行期条件丢弃、参数型 Wrapper 标记 `uncertain`——宁可误报提示人工复核，不静默放行。

## 8. 文档 / 技术栈 / 目录结构

| 文档 | 版本 | 位置 |
| ---- | ---- | ---- |
| 需求文档 | v1.1 | [docs/SQL上线风险静态检查工具-需求文档.md](docs/SQL上线风险静态检查工具-需求文档.md) |
| 概要设计 | v1.10 | [docs/SQL上线风险静态检查工具-概要设计.md](docs/SQL上线风险静态检查工具-概要设计.md) |
| 详细设计 | v1.10 | [docs/SQL上线风险静态检查工具-详细设计.md](docs/SQL上线风险静态检查工具-详细设计.md) |

**技术栈**（版本锁定见概要设计 §3.5）：Java 8 · Maven · JGit 5.13.x · Alibaba Druid SQL 1.2.x · SnakeYAML 1.33 · JavaParser 3.25.x · Gson 2.10.1 · MyBatis-Plus 3.5.7（core） · JUnit 5.10 · MySQL Connector/J 8.0.33 + PostgreSQL JDBC 42.2.25（随 shaded jar） · H2 2.2.224（仅测试）。

**目录结构**（概要设计 §3.4）：

```
src/main/java/com/demo/sqlrisk/
├── cli/          # 入口、参数解析（CliArgs）、退出码
├── core/         # 阶段编排 P0~P9（AnalysisEngine）、白名单过滤、汇总
├── config/       # yaml 三层合并与校验（ConfigLoader/CliOverrides）
├── metadata/     # 表元数据（MetadataProvider SPI；JdbcMetadataProvider 一次快照）
├── diff/         # JGit 差异获取 / 全量扫描（ProductionCommitProvider SPI）
├── extractor/    # 语句提取：xml/（mapper.xml+片段传播）、annotation/（注解+常量+Provider）、lambda/（MyBatis-Plus）
├── parser/       # 骨架生成（dynamic/DynamicEvaluator）、Lambda 渲染、Druid 解析、方言 SPI（mysql/postgresql）
├── rule/         # 规则引擎 + builtin/（13 条内置规则）
├── report/       # 控制台摘要（ConsoleReporter）+ JSON（ReportWriter）+ HTML（HtmlReporter）
├── model/        # 核心模型与值对象（零依赖）
└── util/         # 工具类（含 Diagnostics：诊断输出唯一出口）
src/test/
├── java/…        # 单测 + 场景测试 + 黄金样例端到端（AnalysisEngineIT）
└── resources/cases/  # 15 个黄金用例（repo/ + changes/ → schema.sql → expected/report.json 全量比对）
```

**构建与测试**：

```bash
mvn -o clean verify          # 离线构建 + 全部测试（323 用例，含黄金样例）
mvn -o test -Dtest=H1RuleTest            # 单个测试类
mvn -o test -Dtest='H1RuleTest#method'   # 单个测试方法
```

## 9. 开发任务与日志

- 开发任务拆解与进度：[TASKS.md](TASKS.md)（M0~M13 里程碑）
- 每日进展与心得：[JOURNAL.md](JOURNAL.md)
- 代码规范强约束：[CLAUDE.md](CLAUDE.md)（Sonar 规范强制；设计性例外集中登记于 [docs/sonar-exceptions.md](docs/sonar-exceptions.md)）
