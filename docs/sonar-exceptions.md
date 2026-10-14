# Sonar 设计性例外登记表

> 依据 CLAUDE.md 强制规则：「**设计性例外须集中登记豁免（附理由），不得就地静默抑制**」。
> 本表是设计性例外的唯一登记处；新增例外必须先在此登记（含理由与替代保障），再在 SonarCloud 中处理。
>
> 处理方式（二选一，按扫描平台能力）：
>
> 1. SonarCloud 项目界面将对应 issue 标记为 **Accepted**（推荐：保留可见性，可追溯）；
> 2. 扫描配置（sonar.exclusions / issue exclusions）按**精确文件+规则**排除（仅当平台不支持 Accepted 时）。
>
> 复扫后若出现本表之外的设计性命中，视为未登记例外——须修复或补登记，不得默认忽略。

## EX-1：`System.out/System.err`（Sonar `java:S106`）

| 项      | 内容                                                              |
| ------ | --------------------------------------------------------------- |
| 命中位置   | `util/Diagnostics.java`（`warn`，stderr 唯一出口）、`cli/SqlRiskCheckerMain.java:50`（进程标准流绑定） |
| 规则诉求   | 用日志框架替代标准输出                                                     |
| 豁免理由   | ① 本工具是 **CLI 程序**：stdout 承载控制台报告、stderr 承载诊断/警告/进度——这是命令行契约的一部分，不是调试残留；② 刻意不引入日志实现（slf4j-nop）：作为被 CI 脚本/流水线调用的工具，输出契约必须稳定，日志框架的默认配置会污染使用方环境；③ 设计上已**集中化**：全部诊断输出经 `Diagnostics.warn` 一个出口，生产代码其余位置零 `System.out/err`（集中登记的前提）。 |
| 替代保障   | 输出内容有测试断言（`SqlRiskCheckerMainTest` 校验 stdout/stderr 契约）；`Diagnostics` 为唯一豁免点，新增调用必须经它。                |
| 失效条件   | 若本项目被改造为可嵌入库（非 CLI），stdout/stderr 直写即不再成立——需改为注入输出流。                 |

## EX-2：顶层 `Throwable` 兜底（Sonar `java:S1181`）

| 项      | 内容                                                              |
| ------ | --------------------------------------------------------------- |
| 命中位置   | `cli/SqlRiskCheckerMain.java:88`（`catch (Throwable t)`）           |
| 规则诉求   | 只捕获 `Exception`                                                  |
| 豁免理由   | 进程**最外层**的最后防线：任何未预期错误（含 `Error` 级）都必须转成 exit 2 的受控退出与一行错误信息，**绝不裸抛堆栈外泄**（详设 §15「顶层未捕获 Throwable → stderr → exit 2」）。此捕获不放行、不吞错：非 verbose 仅打一行，verbose 打全栈。 |
| 替代保障   | `SqlRiskCheckerMainTest` 覆盖错误路径退出码；内部各层禁止捕获 `Throwable`（仅此一处）。            |
| 失效条件   | 无（除非退出码契约本身被移除）。                                                  |

## EX-3：值对象构造器参数过多（Sonar `java:S107`，阈值 7）

| 项      | 内容                                                              |
| ------ | --------------------------------------------------------------- |
| 命中位置   | `model/ExtractedStatement.java`（8/9 参构造）、`model/ReportModel.java`（8 参）、`extractor/lambda/LambdaChainModel.java`（8 参） |
| 规则诉求   | 参数拆分为参数对象                                                        |
| 豁免理由   | 四处均为**不可变值对象**的"字段全集"构造器：参数即字段、字段即语义，不存在可聚合的中间概念。拆参数对象会引入无行为的载体类型（或可变 builder 中间态），与项目硬约束「模型类不可变、公共方法不返回 null」相悖，且降低可读性；每个类均已提供**短参兼容构造**覆盖常用场景（如 `ExtractedStatement` 无 Wrapper 段 8 参版、`LambdaChainModel` 无分支组 7 参版）。 |
| 替代保障   | 字段全部 `final` + `Objects.requireNonNull` 校验；构造点集中（每个类 ≤ 若干处），改动牵一发动全身，反而不易误用。                     |
| 失效条件   | 若某类字段继续增长（≥10）或构造点分散到多模块，需重新评估参数对象/构建器方案。                                |

## 附注

- `java:S1147`（`System.exit`）：本仓库 `SqlRiskCheckerMain.main` 唯一调用点为 CLI 退出码契约（详设 §5/§15），当前扫描质量配置**未命中该规则**；若复扫出现，按 EX-1 同族登记理由处理（同一行、同一契约）。
- 已修复类问题不留豁免：`javabugs:S2259`（NPE 阻断级）经 `RuleContext.facts()` fail-fast 契约根治，属修复而非豁免。
