# 开发日志 JOURNAL

> 记录每日进展、心得与踩坑。约定：
>
> - **最新日期在最上方**，每天一节；每节包含：进展 / 心得
> - 只记"值得回看"的内容：决策理由、踩坑、验证结论、悬而未决的问题；不抄文档目录和代码清单

***

## 2026-10-15

### 进展

- **PostgreSQL 方言落地（M13，FR-9，需求方驱动，需求方已确认）**：
  - `PostgresDialect`：`DbType.postgresql`；分页识别 = limit 节点且 `rowCount` 非空（`LIMIT n [OFFSET m]`），或文本含 `FETCH FIRST|NEXT n ROWS ONLY`（Druid 1.2.23 未把 FETCH 子句落进 limit 节点——该知识收敛在方言一处）；**纯 OFFSET 保守按无 LIMIT**（跳行不减少扫描量）；UPDATE/DELETE 恒 false（PG 无行限制子句）
  - **发现并修掉一个真实缺陷**：`QueryFactsBuilder.buildSelect` 的 `block.getLimit() != null` 快捷路径**绕过了方言**——MySQL 语义下与方言判定等价，PG 下把纯 `OFFSET 5` 误判为"有限制" → H3 漏报。改为 `hasLimit` 完全归方言后，ac14 黄金用例如实命中 H3；MySQL 侧逐字节零漂移（15 个黄金用例全量回归验证）
  - 驱动按 D27 同策略入 pom（PostgreSQL JDBC 42.2.25 → 随 shaded jar，22.2MB）；`--dialect`/报错文案/默认配置注释/README/CLAUDE.md 放开 `postgresql`；Oracle 仍预留
  - 测试 +9：`PostgresDialectTest`（12 形态正反例）、`SqlDialectsTest`（parse→事实端到端 + 配置放开）、`SqlRiskCheckerMainTest` CLI 冒烟（`--dialect postgresql` 全链路）、黄金用例 `ac14-postgres-dialect`（FETCH FIRST → 无 H3；纯 OFFSET → H3 命中）；**323/323 全绿**
  - 文档回写：详设 v1.10（§10.7 方言规格/§18.3 用例清单/变更记录）、概要 v1.10（修订行/§3.5 驱动表）、README 状态行、CLAUDE.md 技术栈与 SPI 说明、TASKS M13
  - 过程插曲 1：ac14 期望报告第一版是从失败日志"抄"的——surefire 的行前缀把 JSON 里形如 `"k": null` 的行吃掉，期望文件因此缺键（prodCommit/changedVia/level），比对持续失败而**肉眼对照日志却"完全一致"**；改为临时调试测试把真实 actual 直接落盘生成 expected 后一次通过，临时类已删除
  - 过程插曲 2：`default-config.yaml` 昨晚保留了调试用的 url/username（清密码时按选项文本保留），导致 `ac09` 的"datasource.url 未配置"用例失效——**提交 6baa59b 时未复跑全量测试**是疏漏；本次已还原 url/username 为空（本地验证改走 `--db-url/--db-username/--db-password`），ac09 恢复
- **真实 PostgreSQL 连库验证完成（T13.6，需求方提供连接信息）**：目标 `localhost:5432/postgres?currentSchema=union_order`（用户 loafer），以 `union-order-app` FULL 扫描 + 临时夹具仓库两条路径执行——
  - 连接与一次加载：9 张表（日志仅 1 次）；**与 MySQL 版报告逐字节一致**（同一仓库，忽略 generatedAt）——方言切换零漂移的最强证据
  - M1 正反例对照 **PG 库内索引实查**（psql `pg_indexes`）：命中 12 条逐条属实、索引列负例零误报、库外表保守命中（D13）
  - **PG 专有分页语法端到端**（临时夹具 + 真实 PG 元数据）：`FETCH FIRST 1 ROWS ONLY` → 无 H3（LOW）；纯 `OFFSET 5` → **H3 HIGH**；`WHERE uid = ?` 未命中 M1（PG 库内 uid 索引被正确利用）
  - **对照实验（方言错配）**：同一样本改 `--dialect mysql` → `FETCH FIRST` 与纯 `OFFSET` 均显式解析失败归 **SKIP**（失败可见、不静默错判，兑现 P3）；`WHERE uid` 照常分析
  - 失败路径：不存在的角色/库/端口不通 → exit 2 且信息脱敏；报告/日志零密码泄漏；两次运行报告逐字节一致
  - 环境注记：本机 PG 为 `trust` 认证（本地免密），"错误密码"不构成失败路径——改用"不存在的主体"验证

### 心得

- **快捷路径是方言化的天敌**：hasLimit 的坑（`block.getLimit() != null ||`）只有在第二种方言落地时才暴露——MySQL 单方言下它与方言判定**恰好等价**，正是这种"等价"让它在代码里安然存活。检验抽象是否真的抽象的，只有双实现互证。
- **替身验证的是机制，不是语义**：M11 的测试替身方言先行走通了 SPI 路径，但把"FETCH FIRST 靠文本判定"这一实现细节留在了替身里；正式实现把它收敛进 `PostgresDialect` 并文档化（Druid 未建 limit 节点的事实），替身保持独立——各归其位。
- **期望报告必须由真实管线落盘生成，且不得经控制台日志二次搬运**：日志前缀会静默吃掉 `"k": null` 行，"比对失败"与"看起来一模一样"可以同时成立。昨天（ac06 只改 SHA、逐字段 diff）与今天（直接落盘）连续两次同向教训。
- **改配置也是改代码**：昨晚"只是把调试连接信息清成空密码"的一次顺手编辑，钉死了一个测试；提交前跑全量测试不是仪式，门禁对配置文件同样生效。
- **失败路径要选"真正会失败"的输入**：本机 PG 走 `trust` 认证，塞一个错误密码照样连通——"错误密码→exit 2"这个在 MySQL 上成立的用例，在 PG 上从断言退化成空操作。改用"不存在的角色/库/端口"才重新变成有效断言；**验证用例的可证伪性要随环境重新确认，不能照着上一个环境的脚本抄**。
- **对照实验证明机制真的在起作用**：同一份 PG 语法样本换 `--dialect mysql` 跑，`FETCH FIRST` 与纯 `OFFSET` 立刻显式解析失败归 SKIP——既证明方言切换确实改变了行为（不是摆设），也证明失败落在"可见"一侧（P3）而不是静默错判。加上"PG 版与 MySQL 版报告逐字节一致"，两条证据一正一反把方言机制钉死了。

***

## 2026-10-14

### 进展

- 需求评审收敛：围绕初版需求文档迭代多轮，确认关键边界——删除类 SQL 不分析；解析失败入 SKIP 且不影响退出码；保守策略固定启用、不可配置；工作区未提交内容一律不参与（DIFF/FULL 统一以提交树为数据源）；普通 QueryWrapper 与 BaseMapper 内置方法不纳入扫描范围；元数据文件缺失报错
- 概要设计完成（v1.7），决策 D1~D23 全部确认
- 详细设计完成（v1.1）：类级设计、算法伪码、13 条内置规则详设、13 个黄金用例规划（ac01~ac11 等）、DD-1~DD-12 细化决策
- 项目骨架建立：单 Maven 模块、包结构落地、pom 依赖版本锁定（Java 8 兼容）、CLAUDE.md 编写
- 文档归位：需求 / 概要 / 详细设计三件套入 `docs/`，README 作为入口
- 开发任务拆解完成（`TASKS.md`）：M0~M9 十个里程碑、依赖链、完成定义、预研风险清单（3 个 spike 安排在 M0）
- **M0 完成并经需求方确认**：离线构建验证、测试基建（`FixtureRepoBuilder`/`GoldenCaseRunner` 等 8 类）、3 个预研 spike 全部一次通过（15/15 测试绿）
- **M1 完成并经需求方确认**：model（5 枚举 + 8 值对象，零依赖、不可变）+ util（`LineRanges`/`TextUtils`/`IoUtils`/`JavaSourceIndex`），19 个新测试全绿（累计 34）
- **M2 完成并经需求方确认**：cli（`CliArgs` 手写解析 + `ExitCode` + 入口/异常兜底）+ core（`RunContext`/`AnalysisEngine` 空管线 + 并行框架），16 个新测试全绿（累计 50）；实物验收：空仓库 exit 0 / 缺参数 exit 2 / `--help` 正常；shade 产物名固定为 `sql-risk-checker-shaded.jar`
- **M3 完成并经需求方确认**：config（三层合并 + 校验矩阵 + 已移除键告警忽略）+ metadata（必需输入语义、大小写不敏感、重复取首条）+ `default-config.yaml` 13 条规则落表；P1 接线贯通（配置/元数据错误 → exit 2）；25 个新测试全绿（累计 75）
- **M4 完成并经需求方确认**：diff（只读 GitRepo + 缩写 sha 解析 + 提交树内容读取/编码探测、DiffService 行集合与锚点、CommitTreeWalker 排除目录、ProductionCommitProvider SPI）；P2 接线贯通（真实仓库 + 提交解析 + 空差异短路）；16 个新测试全绿（累计 91）
- **M5 完成并经需求方确认**：extractor 全量交付（XML 片段索引与反向传播 + 注解/常量/Provider + Lambda 六入口与实体解析 + P3 编排与引擎接线 + `--verbose` 提取清单）；7 个单测类 + 4 个场景测试，31 个新用例（累计 122）；**真实项目全量扫描**（union-order-app，508 文件）：57 条语句、SKIP 0、5.8s，真实数据暴露 `${ew.customSqlSegment}` 与运行期布尔条件的普遍性
- **M6 完成并经需求方确认**：parser 全量交付（保守求值含 choose 双路/include 展开/上限截断、Lambda 渲染含嵌套组、15 项语句事实、解析编排）+ P5/P6 引擎接线；42 个新用例（累计 164）；**真实项目解析零失败**，抓出并修复两个真实问题——实体式 `update(entity, wrapper)` 的占位渲染、`${}` 语句解析失败会丢失 M8 承诺（改为"短路候选"结构性保证）
- **M7 完成并经需求方确认**：规则引擎（语句级短路/骨架循环首命中即停/配置覆盖）+ 13 条内置规则全量实现；54 个新用例（累计 218）；**真实项目输出风险判定**：高危 5（全部 H3 + 保守折叠附注——"运行期条件丢弃→WHERE 消失"的 D19 行为在真实代码上兑现）、中危 52（含 2 条 M8 `${}`）、退出码 1；摘要语句级口径与 finding 级明细双视图验证
- **M8 完成并经需求方确认**：P4 白名单（仅 XML/注解参与、Lambda 排除）+ P8 汇总口径（恒等式断言）+ P9 报告输出（控制台模板 + JSON 文件，写失败 exit 2）；28 个新用例（累计 246 全绿）；**真实项目兑现**：43 行控制台摘要直接可读、63KB JSON 报告字段契约正确、白名单演示忽略 12 条 XML 语句而不碰 Lambda
- **M9 完成并经需求方确认**：12 个黄金用例（每用例强断言口径恒等式）+ ac09 错误分支 + 确定性/工作区隔离专项（`AnalysisEngineIT` 15 用例）；**263→267 全绿**；期间修复 2 个真实缺陷（裸 `<if>WHERE …</if>` 附注缺失、夹具 SHA 跨 JVM 不稳定）；README 用法/参数/退出码补全、详设 v1.2 回写偏差清单；性能抽查 1050 文件 3.3s（NFR ≤30s）。**M0~M9 全部里程碑交付完毕。**
- 交付后补充：入口调试便利——argv 为空时使用 `SqlRiskCheckerMain.DEBUG_DEFAULT_ARGV`（本地验证仓库 + 占位元数据 + `/tmp` 输出 + `--verbose`）并向 stderr 打印 `[调试]` 提示；空参不再落入缺参用法错误（详见 §5），新增 2 个单测
- 交付后增补（post-M9）：**HTML 阅读层报告**——JSON 机器契约不动，`--output` 默认双产出 `sql_risk_report.html`（单文件离线自包含：内联 CSS + 原生 JS 四维筛选/折叠，全量 HTML 转义，输出确定性）；真实项目一次通过（63.6KB / 57 卡片 / 零外链）；另禁用 shade 的 `dependency-reduced-pom.xml` 自动生成（无 install/deploy 消费场景）
- **注解 SQL 的 Wrapper 段特殊处理（需求方驱动）**：`${ew.customSqlSegment}` 等占位符在根名解析到方法参数（`@Param("ew")` 值优先，缺省取参数名）且类型为 MP Wrapper 家族（原始名以 `Wrapper` 结尾，泛型/包名剥离）时，不再误报 M8，链路：`WrapperParamResolver` 提取 → 语句携带 `wrapperSqlParams` → 解析阶段 `WrapperSqlSubstitutor` 代换为中性谓词（上下文判定 `1 = 1` / ` WHERE 1 = 1`，骨架标记未知片段 → 报告 `uncertain=true`）→ M8 判定排除被覆盖占位符
- **绑定失配恢复 M8**：`@Param` 改名而 SQL 未同步（真实缺陷场景）时根名不再解析 → 自动退回 `${}` 拼串判定；变更检测覆盖两类改动——SQL 注解行与参数声明行（含 `@Param`）都在方法声明范围内，照常提取分析（黄金用例 ac12 分别覆盖）
- 黄金用例 `ac12-annotation-wrapper` 落地（首个覆盖该链路的端到端用例）；**291/291 全绿**
- **真实项目复跑**（union-order-app）：2 条 M8 误报消失——`sumApplyAmountByWrapper`/`sumApprovalAmountByWrapper` 转为 LOW(L4) + `uncertain=true`；摘要 高危 5 | 中危 50（-2）| 低危 2（+2）| SKIP 0
- 设计回写：详设 v1.4（§9.5-6 识别规则、§10.6 代换规则、§11.4 M8 排除项、§18.3 ac12）；已知边界：mapper.xml 的 `${ew…}` 暂不识别（语句与接口方法无静态绑定通道，仍按原文命中 M8）
- **Lambda 控制流分支枚举（需求方驱动，同日第二项）**：wrapper 变量在 `if/else`、`switch/case` 中追加条件 → 按分支枚举骨架（对齐 XML `<choose>`）：`LambdaBranchGroup/Ref` 记录分支坐标 → 渲染期路径组合枚举（`MAX_SKELETONS=8` 截断），未选中分支记 `DroppedBranch`（sibling → `RUNTIME_CONDITION`、default → `OTHERWISE`、set 族 → `SET` 角色）；**无 else 的 if 与循环体按 `<if>` 语义保守丢弃**（`NEVER_TAKEN` 坐标）；黄金用例 ac13（switch default 路径骨架命中 H1、skeleton=1 带分支附注）；300/300 全绿
- **真实项目兑现**（union-order-app `CashOrderServiceImpl.sum` 的 switch 四分支）：旧行为把 LAST_ONE 与 LAST_PASS 两个 case 的调用平铺混合成一条运行时并不存在的错 SQL（`WHERE status=?` + `ORDER BY DESC LIMIT 1`，M1 MEDIUM）→ 现按分支拆分，报告首分支真实 SQL（L2 LOW），其余 case 记溯源
- **元数据示例生成**：解析真实 DDL（union-order-app `init.sql`，PolarDB/PostgreSQL）→ `examples/sql-risk-metadata.example.json`（9 张表：`ALTER TABLE ADD COLUMN` 追加列合并、`CONSTRAINT … PRIMARY KEY` 与 `CREATE [UNIQUE] INDEX … USING BTREE` 全部收录，含复合索引 `idx_province_city(province, city)`）；经工具实际加载验证（不在元数据的表 → M1 保守命中，符合语义）；加守卫测试（示例可加载 + 结构断言，301 用例全绿）；README 引用
  - 过程插曲：`~/Documents/WorkSpace/union/` 目录被 macOS 隐私标记（`com.apple.macl`）拦截，本会话工具进程拒读（连 `xattr -d`/`git show`/关沙箱都被系统拒绝）→ 由需求方手动拷贝到桌面后继续；后续如复用该仓库需授予终端「完全磁盘访问权限」
- **空骨架归口 SKIP（需求方驱动，同日第三项）**：解析产物为空（含 `${}` 解析失败的短路候选）且语句级短路未命中时，不再作为 `level=null` 的无风险结果输出，改归 SKIP（PARSE_FAILED，reason=「解析失败：无可用 SQL 骨架（含 ${} 且未命中短路规则）」）；短路命中语义不变（AC-11，空骨架仍先过 M8）；`RuleEngine.run` 改为返回 `Outcome{results, skipped}` 并接入引擎汇总；ac12 增第三形态（固定条件与 `${ew.sqlSegment}` 混排 → SKIP）；302/302 全绿 → 详设 v1.6
- **HTML 报告文案与统计（需求方驱动，同日第四项）**：「未命中风险规则/无命中」更名为「**通过**」（分区标题、卡片徽标、卡片占位、恒等式行），头部统计新增「通过」卡片（= 参与分析 − 高 − 中 − 低，teal 色）；JSON 报告契约保持不变；302/302 全绿 → 详设 v1.7
- **扩展设计（需求方驱动，仅文档）**：① 元数据来源改为**数据库运行时加载**（JDBC 一次快照、连接即关、失败 exit 2），文件元数据通道**彻底移除**（`JsonMetadataProvider`/`--metadata`/`metadata_file`），测试载体改 H2 内存库；② 新增 `SqlDialect` **方言 SPI**（v1 实现 MySQL；Oracle/PostgreSQL 预留——DbType 映射与分页子句识别要点写入详设 §10.7；未支持值报错不降级）；③ 连接配置三通道 + 密码优先级（CLI > 环境变量 `SQL_RISK_DB_PASSWORD` > yaml）、JDBC 驱动**直接在 pom 中引用**（MySQL Connector/J 随 shaded jar 分发，经需求方拍板取代初稿的"外置驱动目录"方案；后续方言驱动在实现时加入 pom）。三份文档同步：**需求 v1.1**（FR-8/FR-9、AC-12/AC-13）、**概要 v1.8**（D24~D27 全部确认）、**详设 v1.8**（§7 重写、新增 §10.7、§18.2 H2 夹具、附录 B）；实现未开始（按要求先改设计）
  - 实现拆解入 TASKS：**M10 数据库元数据（JDBC 接入）→ M11 方言 SPI（MySQL 基线 + 扩展机制）→ M12 测试夹具迁移与回归（H2）**，依赖链接在 M9/交付后增补之后，门禁不变
- **M10 数据库元数据（JDBC 接入）完成并经需求方确认**：① config/CLI——`DataSourceSetting`/`CliOverrides`、新参数（`--dialect/--db-url/--db-username/--db-password`）、密码三级优先级（CLI > 环境变量 > yaml）、`--metadata`/`metadata_file` 移除（后者告警忽略）；② `JdbcMetadataProvider`——JDBC 一次加载快照（连接即关、类型归一含 H2 别名 CHARACTER VARYING→VARCHAR / INTEGER→INT、索引按列序、失败 exit 2 且脱敏），删除 `JsonMetadataProvider` 与示例文件；③ 黄金夹具 14 例迁移 `metadata.json`→`schema.sql`（H2 建库→JDBC 加载），**expected 零漂移**（旧 JSON 语义与 H2 实测等价）；ac09 新错误形态（连接失败/datasource.url 未配置/方言未支持）；④ 在线校准一次（MySQL Connector J 8.0.33 + H2 2.2.224）后离线恢复；**306/306 全绿**，shaded jar 21.2MB（含驱动）
- **M11 方言 SPI（MySQL 基线 + 扩展机制）完成并经需求方确认**：`SqlDialect`（name/dbType/hasRowLimit）+ `SqlDialects` 注册表（内置 mysql + `ServiceLoader` 外部方言，同名冲突内置优先）+ `MysqlDialect`（迁移原 LIMIT 识别，SELECT 块级 / UPDATE、DELETE 反射 getLimit）；`DruidSqlParser.parse(sql, dialect)`、`QueryFactsBuilder` hasLimit 走方言、`ParseContext` 携带方言（缺省 MySQL 兼容）；配置校验改由 `SqlDialects.supportedNames()` 动态放开——**替身方言（PG 形态 FETCH FIRST）端到端验证扩展路径**（实现→注册→配置放开→解析→hasLimit 事实）；**312/312 全绿、黄金用例零漂移**（MySQL 行为零变化）
- **M12 端到端回归与收尾完成并经需求方确认（真实 MySQL 验证按需求方决定暂缓）**：**扩展项目（FR-8 数据库元数据 + FR-9 多方言预留）整体交付完毕**——① 文档回写——README（datasource yaml 示例/CLI 表/状态行）、CLAUDE.md（依赖图含 `config → parser` 边、SPI 清单加 `SqlDialect`、技术栈加驱动/H2）、概设 §3.3 与详设 v1.8 实现回写（类型别名归一、环境变量注入重载、ParseContext 方言装配）；② **CLI 端到端冒烟**：shaded jar + 外部 H2 classpath 走真实命令——`[元数据] … 加载 0 张表（一次加载，连接已关闭）`（AC-12 证据）、DIFF 命中 H2 高危、JSON+HTML 双报告落盘；③ **确定性复验**（`AnalysisEngineIT` 两轮独立 JVM 全绿）+ 离线 `mvn -o clean verify` **312/312 全绿**
- **Sonar 规范纳入项目强制规则（需求方驱动）**：需求方对整个项目做了 Sonar 规范扫描，"**生成的代码必须满足 Sonar 代码规范**"正式写入 CLAUDE.md 硬约束与 TASKS 完成定义（DoD）；扫描发现大量存量违规，待需求方提供扫描报告（规则明细/文件清单/严重级）后按三步治理：① 机械类批量修复（重复字面量 S1192、未使用导入/变量、空捕获等）；② 设计性例外集中登记豁免（CLI 契约的 `System.out/err`、`System.exit`、顶层 `Throwable` 兜底等属设计使然——附理由登记，禁止就地静默抑制）；③ 治理完成后全量复扫验证。即日起新代码先满足规范再提交
- **Sonar 存量治理完成（165 issue / 23 规则，需求方报告为输入）**：按三批推进并全量回归——
  - **BLOCKER 根治**：`javabugs:S2259`（NPE ×11，全部在规则类）根因是 `RuleContext.facts()` 可返回 null 的宽松契约——改为**语句级上下文调用即抛** `IllegalStateException`（fail fast，与"公共方法不返回 null"的项目约束对齐），11 条一次清零、规则代码零改动；`S5998`（正则栈溢出）白名单模式改所有格量词（改前用 22 组输入做新旧等价性验证）；`S2447`（Boolean 返回 null）改 `Optional`
  - **诊断输出集中化**：新增 `util/Diagnostics`（唯一 `System.err` 出口），生产代码标准流用法 22 处 → 3 处；`S106` 豁免面积从"8 个文件 22 处"收敛为"1 条规则 3 处"
  - **机械类批量 + 复杂度拆分**：未用导入 ×11、重复字面量 ×11（`dialect`/"LambdaQueryWrapper"/" AND " 等常量化）、循环→Stream ×20（S9391，filter/map/collect 形态）、空集合返回 ×3、未用参数 ×7（含 `ConfigLoader.load` 移除 `repoPath`——详设 §6 同步）、`S3776` 认知复杂度 ×23（含 `LambdaSkeletonRenderer.applyCalls` 101→分组分发、`LambdaSqlExtractor.buildModels` 36→单一职责拆分）、`S135` 循环跳转 ×10（多 `continue` 提取为返回制小方法）
  - **设计性例外登记**：新增 `docs/sonar-exceptions.md`——EX-1 `S106` 标准流（CLI 输出契约 + slf4j-nop 零日志实现的刻意选择）、EX-2 `S1181` 顶层 `Throwable` 兜底（exit 2 的最后防线，不放行不吞错）、EX-3 `S107` 值对象构造器 ×4（字段全集，均有短参兼容构造）；每条含理由/替代保障/**失效条件**
  - **测试侧**：`assertThrows` 化 ×8（try/catch+fail → 专用断言，lambda 内提前求值参数以消 S5778）、`assertEquals/assertNotNull` 替换恒真断言 ×5、超长测试方法拆分（312→314 用例；两个超长方法各拆为二）、夹具源码按 `S7158` 改 `String.isEmpty()`（`ac06` 的 expected 仅 head SHA 变化，语义字段零漂移，已核对逐字段 diff）
  - **回归**：`mvn -o clean verify` **314/314 全绿**（含 14 黄金样例）；详设 **v1.9** 回写（§14 增 `Diagnostics`、§11.1 `facts()` 契约、§6 签名）；**复扫由需求方执行**（预期剩余 = 登记例外 4 条）

- **真实 MySQL 连库验证补验完成（T12.1 销项，需求方提供连接信息）**：以 `union-order-app`（`dec908da`）FULL 模式端到端执行——
  - `[元数据] jdbc:mysql://127.0.0.1:3306/union_order 加载 9 张表（一次加载，连接已关闭）`，加载日志全程仅 1 次；库内 9 张表与 init.sql 表清单一致
  - **"元数据确实来自库"拿到铁证**：库内 `cash_order` 有 `uid` 索引而桌面 init.sql 副本没有 → `WHERE uid = ?` 语句未命中 M1（按文件快照会误报）；正向 M1 命中 12 条逐条对照库内索引首列全属实，负例 12 例零误报
  - 库外表（`quota_change_record`/`quota_order`）→ 保守命中"表不在元数据中，默认无索引"（D13）；错误密码 → exit 2 且信息脱敏（含 URL 不含密码）；报告/日志零密码泄漏
  - 报告口径恒等式 `57 = 5 高 + 12 中 + 28 低 + 12 通过`（SKIP 0）；两次运行报告（忽略 generatedAt）逐字节一致；全程 6.4s（NFR ≤30s）
  - 附带发现：需求方把连接信息放进了 `src/main/resources/default-config.yaml`（内置默认配置）——验证因此走的是 jar 内打包配置，反而证明**分发产物开箱即连**；但密码明文随源码与 shaded jar 分发，建议改走 `--db-password`/环境变量（见当日安全提醒）

### 心得

- **不确定的信息不出规则**：M7（UPDATE/DELETE 无 LIMIT 的大批量）被移除——静态扫描无法确定影响行数，硬凑阈值只会制造噪音。规则宁少勿滥。
- **规则要正交**：H4（动态折叠致 WHERE 缺失）、H5（SELECT * 无过滤）最终都被移除，由 H1~H3 天然覆盖；"`<if>` 唯一条件视为 FALSE"归位为解析阶段语义而非独立风险规则。同一风险不做两条规则，输出才干净。
- **保守策略应当是硬语义而非开关**：去掉开关后，"`<if>` 一律 FALSE"成为解析阶段不可绕过的行为，而不是一个可能被误关的配置项——工具的核心承诺不该可配置。
- **静态分析的正确性难点在"间接依赖"而非 diff 本身**：`<sql>` 片段相当于 SQL 的"公共函数"，改函数体必须重查所有引用方（含传递、跨文件）——`XmlFragmentIndex` 反向依赖闭包是本次设计里最关键的新增机制。
- **数据源统一带来可验证性**：DIFF/FULL 都只读提交树后，"同一 commit 两次运行结果逐字节一致"可以写进验收标准；测试也更容易做（固定 Clock + 黄金样例比对）。
- **澄清问题集中在开工前最划算**：工作区范围、QueryWrapper 边界这类问题带着模糊进编码，大概率是返工而不是幸运。
- **预研 spike 性价比极高**：三个 spike 全部一次通过，还顺带暴露两个真实 API 细节（Druid 的 `SQLSelectItem` 包装、JavaParser 的 `getFields()` API）——"先验证假设再写正代码"直接消除了 M6 的返工风险。
- **黄金用例的价值在"复核"而非"生成"**：expected 由真实管线生成（否则手写 JSON 必错），但生成后逐用例人工复核输出是否符合设计意图，才暴露了两个真实缺陷——裸 `<if>WHERE …</if>` 折叠后未归 `CONDITION`（H1/H2/H3 保守附注在最常见形态下缺失）与 JGit `PersonIdent(PersonIdent)` 拷贝构造器静默重置提交时间（夹具 SHA 跨 JVM 漂移）。"先生成后签字"的流程不能省掉签字。
- **确定性要穿透到最底层**：报告的逐字节一致依赖提交 SHA 稳定，而 SHA 依赖提交对象全部字段——作者、时间、时区缺一不可；固定 Clock 只解决了报告时间戳，提交身份要单独固定。跨 JVM（两次独立 mvn）验证才算数，同 JVM 内比对会漏掉这类问题。
- **对角色的语义推断要看"内容"不要看"标签"**：判断丢弃分支是否影响 WHERE，最开始用重建的带标签原文（`<if>…</if>`）做前缀匹配，永远匹配不上；改为"仅拼接子内容文本"（`innerText`）后一次通过——诊断用的文本和推断用的文本需求不同，不能复用。
- **收尾回写让文档与代码重新对齐**：M4~M6 期间累积的实现偏差（行集合类型、ConfigLoader 签名、SPI 形态）集中回写详设 v1.2 并标注变更点，之后翻文档就能得到与代码一致的事实——"文档是唯一事实来源"要靠这种定期收敛维持。
- **"框架惯用法"与"拼串风险"要分开**：`${ew.customSqlSegment}` 与 `${userInput}` 形状一样，差别在值的**来源**——静态检查的可靠性一半在识别形状，另一半在追溯来源；追不到来源时保守（M8），能追到绑定则精确排除。
- **排除项要带"失配回退"**：根名解析失败自动退回 M8，让"@Param 改名而 SQL 未同步"这类真实缺陷继续被抓住——排除逻辑的边界处理反而成了免费的附加正确性检查。
- **代换而非丢弃**：把 Wrapper 段代换为中性谓词（而不是整句跳过/报 SKIP），让语句其余部分照常进入规则管道（本例如实报出 L4），信息损失最小；`uncertain=true` 如实标注"条件来自运行期"。
- **"平铺收集"是隐藏的语义错误源**：分支内的条件调用此前被平铺进同一条链，产出的是"所有分支同时成立"的 SQL——这种 SQL 在运行期并不存在；枚举分支不是锦上添花，而是把"不存在的 SQL"修正为"可能的 SQL"。
- **"选择"与"修饰"分开对待**：XML 早已区分 `<choose>`（替代路径 → 枚举）与 `<if>`（可选修饰 → 保守丢弃）；Java 的 if/else 与 switch/case 是"选择"、无 else 的 if 与循环体是"修饰"——沿用同一判据，Java 与 XML 两套语义天然一致，解释成本为零。
- **"无法分析"与"已分析且无风险"必须在口径上分开**：空骨架此前以 `level=null` 混在 results 里——统计上表现为"已分析"，实际是"没分析成"；归口 SKIP 后 `total + skip + ignored = 提取总数` 的恒等式自动还原真实语义，报告使用者不会再把它误读为"看过且没问题"。
- **规范要靠"扫描验证 + 分类治理"落地**：静态规范（Sonar）与工具自身的静态分析异曲同工——存量债分两类处理，机械类批量修，设计性例外（如 CLI 必须直接写 stdout/stderr）集中登记并附理由；"就地 @SuppressWarnings 静默抑制"会把豁免变成不可审计的噪音，是治理中最该避免的捷径。
- **Sonar 违规里混着真 BUG，治理要按影响排序而非规则计数**：165 条里唯一的 BLOCKER（NPE ×11）不是风格问题——"公共方法可返回 null、11 个调用点直接解引用"是真实的脆弱契约；它藏在最不起眼的规则类里，按 issue 数排的话反而排不上号。以 IMPACT_RANK 排序拿到报告，恰好把这类问题顶到了最前。
- **修根因优于修现场**：11 个 NPE 若逐点加判空 = 11 处防御代码 + 契约更含糊；改 `facts()` 为 fail-fast = 1 行契约收紧 + 误用即刻暴露（引擎兜底不变）。同一洞察在正则（所有格量词）与诊断输出（集中出口）上重复出现——**先问"为什么会有这个问题"，再决定修哪里**。
- **豁免面积本身是个指标**：S106 集中化后，例外从 22 个具体位置变成 1 条规则；每条豁免登记"理由 + 替代保障 + 失效条件"三件套后，"有豁免"不再等于"可以不管"——写了失效条件的豁免，才配叫设计决策。
- **"连上了"不等于"用对了"**：连库验证最容易糊弄过去的方式是看到 9 张表的日志就收工；真正有说服力的是**反例**——库里新加了一个 init.sql 副本没有的 `uid` 索引，按快照判定会误报 M1、按库内判定不会，工具给出"不命中"，这一条把"元数据来自库"从推断变成了证据。验收要么构造反例，要么承认自己只是抽了个烟。
- **凭据通道要跟产品设计对齐**：yaml/环境变量/CLI 三级通道的设计初衷是"明文不落盘、不进仓库"，而本次为了图快把密码写进了 `default-config.yaml`（会随 jar 分发）——工具自己的文档已经写明了推荐姿势，验证过程反而演示了不推荐的姿势。能力做对了，使用习惯仍会背叛设计意图。
