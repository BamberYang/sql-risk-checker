# SQL 上线风险静态检查工具（sql-risk-checker）

基于 **Git 差异（diff）** 的 MyBatis SQL 静态风险检查 CLI：仅提取本次变更涉及的 SQL（mapper.xml / 注解 / MyBatis-Plus Lambda 构造），按**高危 / 中危 / 低危**三级规则分析，输出控制台摘要 + JSON 报告。

> 当前状态：**M0~M9 全部里程碑完成并经需求方确认**——267 用例全绿（含 12 个黄金样例 + 确定性/FULL 工作区隔离/ac09 错误码），真实项目（union-order-app）全量验证通过，1050 文件性能抽查 3.3s（NFR ≤30s）。

## 文档

| 文档   | 版本   | 位置                                                        |
| ---- | ---- | --------------------------------------------------------- |
| 需求文档 | v1.0 | [docs/SQL上线风险静态检查工具-需求文档.md](docs/SQL上线风险静态检查工具-需求文档.md) |
| 概要设计 | v1.7 | [docs/SQL上线风险静态检查工具-概要设计.md](docs/SQL上线风险静态检查工具-概要设计.md) |
| 详细设计 | v1.2 | [docs/SQL上线风险静态检查工具-详细设计.md](docs/SQL上线风险静态检查工具-详细设计.md) |

## 技术栈（版本锁定见概要设计 §3.5）

Java 8 · Maven · JGit 5.x · Alibaba Druid SQL 1.2.x · SnakeYAML 1.33 · JavaParser 3.25.x · Gson 2.10.1 · MyBatis-Plus 3.5.x · JUnit 5

## 目录结构（概要设计 §3.4）

```
src/main/java/com/demo/sqlrisk/
├── cli/          # main 入口、参数解析、退出码
├── core/         # 阶段编排（P0~P9）、并行调度、白名单过滤、汇总
├── config/       # yaml 配置加载与校验
├── metadata/     # 表元数据（MetadataProvider SPI）
├── diff/         # JGit 差异获取 / 全量扫描（ProductionCommitProvider SPI）
├── extractor/    # 语句提取：xml/（mapper.xml）、annotation/（注解）、lambda/（MyBatis-Plus）
├── parser/       # 骨架生成（dynamic/）+ Druid 解析（druid/）
├── rule/         # 规则引擎 + builtin/ 内置规则
├── report/       # 控制台摘要 + JSON 报告
├── model/        # 核心模型（零依赖）
└── util/         # 工具类
```

## 构建与测试（离线）

```bash
mvn -o clean verify                        # 构建 + 全量测试（含黄金样例 AnalysisEngineIT）
mvn -o test -Dtest=H1RuleTest              # 单个测试类
mvn -o clean package                       # 产出 target/sql-risk-checker-shaded.jar
```

## 使用

```bash
# DIFF 模式：扫描 prod → head 之间的变更 SQL
java -jar target/sql-risk-checker-shaded.jar \
    --repo /path/to/repo --prod-commit <sha> --metadata /path/to/sql-risk-metadata.json

# FULL 模式：全量扫描 head 提交树（省略 --prod-commit）
java -jar target/sql-risk-checker-shaded.jar --repo /path/to/repo --metadata <path>
```

| 参数 | 说明 |
| ---- | ---- |
| `--repo <path>` | 仓库路径（必需；只读，数据源恒为提交树，不含工作区未提交内容） |
| `--prod-commit <sha>` | 生产 commit；提供则 DIFF 模式，省略则 FULL 模式 |
| `--metadata <path>` | 表元数据 JSON；省略时取仓库根 `sql-risk-metadata.json`（缺失/格式错误 → exit 2） |
| `--config <path>` | 配置文件（覆盖内置默认；`rules`/`whitelist.statements`/`low_risk_keywords`） |
| `--output <dir>` | 报告目录，默认 `.`；报告文件名固定 `sql_risk_report.json` |
| `--verbose` | stderr 输出提取/白名单/解析/规则阶段进度日志 |
| `--help` | 用法 |

**退出码**：`0` 无高危（SKIP 不影响）；`1` 存在高危；`2` 参数/环境/IO 错误。

**调试**：IDE 直接运行 `main`（argv 为空）时使用 `SqlRiskCheckerMain.DEBUG_DEFAULT_ARGV` 内置默认参数（本地验证仓库 + 占位元数据 + `/tmp` 输出 + `--verbose`），并向 stderr 打印 `[调试]` 提示；正式运行请显式传参。

**输出**：stdout 控制台摘要（高危明细默认展开，`--verbose` 附中/低危明细）+ `sql_risk_report.json`（字段契约见详细设计 §12.3/附录 A）。

## 风险规则（13 条，级别可配置、启停可配置）

| 组 | 规则 |
| -- | ---- |
| H（高危） | H1 UPDATE 无 WHERE；H2 DELETE 无 WHERE；H3 SELECT 无 WHERE 且无 LIMIT |
| M（中危） | M1 条件列未命中索引；M2 索引列函数包裹；M3 前导通配符 LIKE；M4 隐式类型转换；M5 笛卡尔积；M6 OR 非索引；M8 `${}` 拼串（语句级短路） |
| L（低危） | L1 配置关键字；L2 SELECT * 且有过滤；L4 无 LIMIT 且有 WHERE |

保守策略固定启用：`<if>` 恒视为 FALSE（折叠后条件丢失由 H/M 组规则暴露，命中 H1~H3 时附注提示）；Lambda 运行期布尔条件丢弃。要点：`${}` 出现在被丢弃分支内也短路定级 M8；`<sql>` 片段变更传播到全部（含跨文件、传递）引用语句并记 `changedVia`。

## 开发任务与日志

- 开发任务拆解与进度：[TASKS.md](TASKS.md)（M0~M9 里程碑）
- 每日进展与心得：[JOURNAL.md](JOURNAL.md)
