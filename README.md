# SQL 上线风险静态检查工具（sql-risk-checker）

基于 **Git 差异（diff）** 的 MyBatis SQL 静态风险检查 CLI：仅提取本次变更涉及的 SQL（mapper.xml / 注解 / MyBatis-Plus Lambda 构造），按**高危 / 中危 / 低危**三级规则分析，输出控制台摘要 + JSON 报告。

> 当前状态：**设计阶段**。项目骨架已就绪，代码待实现。

## 文档

| 文档   | 版本   | 位置                                                        |
| ---- | ---- | --------------------------------------------------------- |
| 需求文档 | v1.0 | [docs/SQL上线风险静态检查工具-需求文档.md](docs/SQL上线风险静态检查工具-需求文档.md) |
| 概要设计 | v1.2 | [docs/SQL上线风险静态检查工具-概要设计.md](docs/SQL上线风险静态检查工具-概要设计.md) |

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

## 构建（离线）

```bash
mvn -o clean verify
java -jar target/sql-risk-checker-shaded.jar --repo <path> [--prod-commit <sha>] ...
```

## 开发前待确认事项

1. `--head-commit` 缺省时是否分析工作区未提交改动（概要设计 §4.3）
2. 普通 `QueryWrapper` / `BaseMapper` 内置方法是否纳入 v1 扫描范围
3. JSON 报告是否允许追加字段（`mode`/`source`/`uncertain`/`skeleton`、`changeType: FULL`）
4. XML 解析实现偏离需求建议：JDK SAX + Locator vs dom4j（概要设计 D3）
