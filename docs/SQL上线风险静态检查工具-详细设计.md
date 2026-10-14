# SQL 上线风险静态检查工具 —— 详细设计

***

## 1. 文档信息

| 项    | 内容                                             |
| ---- | ---------------------------------------------- |
| 文档名称 | SQL 上线风险静态检查工具详细设计                            |
| 版本   | v1.9                                           |
| 日期   | 2026-10-14                                     |
| 关联文档 | 需求文档 v1.1（FR-8/FR-9 扩展）；概要设计 v1.8（决策 D1~D27）     |
| 本文定位 | 编码直接依据：类结构、方法签名、算法伪码、常量阈值、测试类清单；不包含最终源码本身      |
| 阅读顺序 | §2 设计约定 → §3 类总览 → §4~§14 分包详设 → §15 异常 → §18 测试 → §19 决策 → §20 追溯 |

> 标注【DD】的条目为详细设计阶段新增的细化决定（汇总见 §19）；与概要设计冲突时以本文为准并回写概要设计。

***

## 2. 设计约定

| 项     | 约定                                                                              |
| ----- | ------------------------------------------------------------------------------- |
| 语言/版本 | Java 8；不使用 var、record、switch 表达式、`List.of` 等新语法                                 |
| 不可变   | 模型/值对象一律 `final` 字段 + 构造器赋值；转发集合用 `Collections.unmodifiableList` 包装      |
| 空值    | 公共方法**不返回 null**（集合返回空集合，可选值用 `Optional<T>`）；可空字段以 Javadoc `@Nullable` 标注 |
| 日志    | 不引入日志框架：进度/警告经 `System.err`（`--verbose` 提升详细度）；stdout 仅报告                    |
| 字符集   | 全部 UTF-8 读写；XML 原文按文件声明编码解码（缺省 UTF-8）                                            |
| 时钟    | 所有取当前时间的点经构造函数注入 `java.time.Clock`（测试用 `Clock.fixed`）                          |
| 并行    | 见 §16；共享对象必须在构建后不可变                                                              |
| 异常    | 受控失败统一抛 `SqlRiskException` 子类（携带 exit code），顶层兜底捕获（§15）                             |

***

## 3. 包与类总览

```
com.demo.sqlrisk
├── cli        SqlRiskCheckerMain · CliArgs · ExitCode
├── core       AnalysisEngine · RunContext · WhitelistFilter · SummaryCalculator
├── config     AppConfig · RuleSetting · DataSourceSetting · ConfigLoader · ConfigValidator · ConfigException
├── metadata   MetadataProvider · JdbcMetadataProvider · TableMetadata · FieldMeta · IndexMeta · MetadataException
├── diff       GitRepo · RepoContentReader · DiffService · CommitTreeWalker · ChangeSet · FileChange · Hunk
│              ProductionCommitProvider · CliArgProductionCommitProvider · DiffException
├── extractor  SqlSourceExtractor · CandidateFile · ExtractContext · ExtractResult
│              MapperXmlExtractor · MapperXmlSaxHandler · XmlFragmentIndex · FragmentPropagator
│              AnnotationSqlExtractor · JavaConstantResolver · ProviderMethodResolver
│              LambdaSqlExtractor · LambdaChainModel · LambdaCall · EntityIndex · EntityMeta · ColumnMapping
├── parser     PlaceholderNormalizer · NormalizedSql · DynamicEvaluator · LambdaSkeletonRenderer
│              SqlSkeleton · DroppedBranch · DruidSqlParser · QueryFacts · QueryFactsBuilder
│              SqlDialect · SqlDialects · MysqlDialect · ParsedStatement · ParsedSql · SqlParseException
├── rule       Rule · RuleContext · RuleEngine · RuleRegistry · RuleProvider · builtin/(H1~H3、M1~M6、M8、L1/L2/L4 共 13 个)
├── report     ReportModel · Summary · ConsoleReporter · JsonReporter · ReportWriter · ReportException
├── model      Mode · ChangeType · StatementSource · RiskLevel · DiffType
│              ExtractedStatement · StatementMeta · Finding · StatementResult · SkippedItem · IgnoredItem
└── util       TextUtils · LineRanges · IoUtils
```

类间主线（数据流）：`CliArgs → RunContext → AnalysisEngine` 编排：`GitRepo/DiffService → ChangeSet`，`XmlFragmentIndex/EntityIndex → Extractor* → ExtractedStatement`，`DynamicEvaluator/LambdaSkeletonRenderer → SqlSkeleton → DruidSqlParser/QueryFactsBuilder → ParsedStatement`，`RuleEngine → StatementResult`，`SummaryCalculator → ReportModel → Reporter`。

***

## 4. model 包

### 4.1 枚举

```java
public enum Mode { DIFF, FULL }
public enum ChangeType { ADDED, MODIFIED, FULL }
public enum StatementSource { MAPPER_XML, ANNOTATION, LAMBDA }
public enum DiffType { ADD, MODIFY, DELETE, RENAME }
public enum RiskLevel {
    HIGH(3), MEDIUM(2), LOW(1);          // max(a,b) 取高；比较用 weight
    public int weight();
    public static Optional<RiskLevel> max(Collection<RiskLevel> levels);
}
```

> 【DD-1】概要设计 §6 的 `StatementCategory` 为逻辑归类，不再设独立枚举：由 `StatementResult.level`（可为 null）与 `skipped[]` 清单共同表达。

### 4.2 核心值对象

```java
public final class ExtractedStatement {
    String statementId;            // XML/注解: namespace.id；Lambda: 文件名:行号
    StatementSource source;
    ChangeType changeType;
    String file;                   // 仓库相对路径
    int line;                      // 语句起始行（1 基）
    String rawText;                // 语句原文（XML 片段 / 注解文本 / Lambda 链源码）
    Object carrier;                // DynamicXmlText / AnnotationSqlText / LambdaChainModel
    @Nullable String changedVia;   // 片段派生命中：来源 <sql> 片段 refid（全限定）
}
```

| 类型                  | 关键字段 / 方法                                                                                                   |
| ------------------- | ----------------------------------------------------------------------------------------------------------- |
| `StatementMeta`     | statementId、source、file、line、changeType、changedVia；由 `ExtractedStatement` 投影                                       |
| `Finding`           | ruleId、level、message、snippet、skeletonIndex（首个骨架为 0）                                                             |
| `StatementResult`   | meta + `sql`（报告展示：命中则命中骨架文本，未命中则骨架 #0）+ findings[] + `@Nullable level`（最高命中级别）+ `uncertain`（骨架/DroppedBranch 任一带 uncertain） |
| `SkippedItem`       | statementId(@Nullable)、file、line、reason、`Kind kind`；`Kind { UNSUPPORTED_CARRIER, PARSE_FAILED, READ_ERROR }`   |
| `IgnoredItem`       | statementId、file、line、reason（当前恒为 "whitelist"）                                                                |
| `Summary`           | total、high、medium、low、skip、ignored；计算见 §13.4                                                                |
| `ReportModel`       | generatedAt（ISO-8601 含时区）、mode、headCommit、prodCommit(@Nullable)、summary、results[]、ignored[]、skipped[]        |

***

## 5. cli 包

```java
public final class ExitCode {
    public static final int OK = 0;            // 无高危
    public static final int HIGH_RISK = 1;     // 存在高危（summary.high > 0）
    public static final int USAGE_ENV_ERROR = 2;
}

public final class CliArgs {                    // 手写解析，不引入 CLI 框架【DD-2】
    String repoPath;                            // 必填
    @Nullable String headCommit;
    @Nullable String prodCommit;
    @Nullable String configPath;
    String outputDir = ".";                     // 缺省当前目录
    @Nullable String dialect;                   // v1.8：缺省 mysql
    @Nullable String dbUrl;                     // v1.8：覆盖 yaml datasource.url
    @Nullable String dbUsername;                // v1.8
    @Nullable String dbPassword;                // v1.8：优先级 CLI > 环境变量 > yaml
    boolean verbose;
    // parse(String[]) 抛 CliUsageException（exit 2）；--help 打印后 exit 0
    // 已移除参数：--metadata（v1.8/D24，出现 → 参数错误 exit 2）
}
```

`SqlRiskCheckerMain.main` 伪码：

> 调试便利（实现补充）：`argv` 为空 → 使用入口内置 `DEBUG_DEFAULT_ARGV`（本地验证仓库 + 本地开发库连接参数 + `/tmp` 输出 + `--verbose`），并向 stderr 打印 `[调试]` 提示，便于 IDE 直接运行 `main`；非空参数原样解析，正式运行不受影响。该常量随 v1.8 连接参数（`--db-url`/`--db-username`/`--dialect` 等）调整，不再包含元数据文件路径。

```
int main(argv):
  try:
    CliArgs args = CliArgs.parse(argv)                     // 失败 → 打印 usage → 2
    AppConfig config = ConfigLoader.load(args)             // ConfigException → 2
    RunContext ctx = RunContext.build(args, config, Clock.systemDefaultZone())
    ReportModel report = new AnalysisEngine(clock).execute(ctx)
    JsonReporter.write(report, args.outputDir)             // ReportException → 2
    ConsoleReporter.print(report, System.out, args.verbose)
    return report.summary.high > 0 ? ExitCode.HIGH_RISK : ExitCode.OK
  catch SqlRiskException e:  System.err.println("[错误] " + e.getMessage()); return e.exitCode()
  catch Throwable t:         System.err.println("[未知错误] " + t); if verbose stack; return 2
```

`--help` 文本见附录 B；参数校验矩阵：

| 校验                                  | 结果     |
| ----------------------------------- | ------ |
| `--repo` 缺失 / 重复参数 / 未知参数 / `--format` 非 `json` / **`--metadata`（已移除参数）** | exit 2 |
| `--head-commit` 与 `--prod-commit` 均缺省 | 合法（FULL 模式，取 HEAD 提交树） |
| `--head-commit` == `--prod-commit`    | 合法：空差异，0 条语句，exit 0（console 提示"无差异语句"） |
| `--dialect` 取值不在已支持列表（v1 仅 `mysql`） | exit 2（不静默降级，D25） |

***

## 6. config 包

```java
public final class AppConfig {                  // 三层合并结果，不可变
    Map<String, RuleSetting> ruleSettings;      // ruleId → 覆盖项
    DataSourceSetting datasource;               // v1.8：数据库连接（元数据来源）
    String dialect;                             // v1.8：方言名（已校验为已支持值）
    List<String> lowRiskKeywords;
    List<String> whitelistStatements;           // 原始模式串（已校验）
}
public final class RuleSetting { String ruleId; RiskLevel level; boolean enabled; }

public final class DataSourceSetting {
    String url;                                 // 必填（CLI --db-url 可提供）
    @Nullable String username;
    @Nullable String password;                  // 已按 CLI > 环境变量 > yaml 解析后的最终值
    int connectionTimeoutSeconds = 10;          // §17
}
```

`ConfigLoader.load(CliOverrides overrides)` 流程（v1.8 起以不可变 `CliOverrides{configPath, dialect, dbUrl, dbUsername, dbPassword}` 携带 CLI 值，避免 config 包反向依赖 cli，§3.3；另有 `load(overrides, Map<String,String> env)` 重载供测试注入环境变量——M10 实现回写；v1.9 移除未使用的 `repoPath` 保留参数）：

1. 读内置 `resources/default-config.yaml` → 用户 `--config` 文件覆盖 → CLI 覆盖（dialect / db-url / db-username / db-password）。
2. yaml 解析（SnakeYAML `safeLoad`，禁用任意对象构造）；未知顶层键 → stderr 警告忽略；已移除键 `bulk_operation_threshold`、`conservative_if_always_false`、**`metadata_file`（v1.8/D24）** → 告警并忽略。
3. 密码解析优先级（D26）：`--db-password` > 环境变量 `SQL_RISK_DB_PASSWORD` > yaml `datasource.password`；日志一律脱敏。
4. `ConfigValidator` 校验（错误 → `ConfigException`，含字段级消息）：
   - `rules[].ruleId` 必须存在于内置规则表，未知 → 警告忽略；
   - `rules[].level ∈ {HIGH,MEDIUM,LOW}`；`enabled` 布尔；
   - 白名单模式必须满足 `完整匹配` 或 `namespace.*`（正则：`^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*(\.\*)?$`，且 `*` 仅可出现在末尾段），非法 → 报错；
   - `low_risk_keywords` 元素非空；
   - **`dialect` 必须 ∈ `SqlDialects.supportedNames()`（v1 仅 `mysql`），否则报错（D25）**；
   - **`datasource.url` 缺失（且 CLI 未提供）→ 报错；须以 `jdbc:` 开头**。

***

## 7. metadata 包

```java
public interface MetadataProvider {
    Optional<TableMetadata> find(String tableName);     // 大小写不敏感
}
public final class TableMetadata { String table;
    Map<String, FieldMeta> fieldsByLowerName;           // 列名小写 → 字段
    List<IndexMeta> indexes; }
public final class FieldMeta { String name; String type; }     // type 如 "BIGINT"/"VARCHAR(64)"
public final class IndexMeta { String name; List<String> columns; }
```

**v1.8（FR-8/D24）：唯一实现 `JdbcMetadataProvider`——JDBC 一次加载数据库元数据为内存快照。**

```java
public final class JdbcMetadataProvider implements MetadataProvider {
    /** 连接一次 → 读取 → 关闭连接；失败 → MetadataException（exit 2） */
    public static JdbcMetadataProvider load(DataSourceSetting cfg) { ... }
    /** v1.8 起仅测试可见：直接以内存快照构造（H2 加载后复用/替身测试） */
    static JdbcMetadataProvider ofSnapshot(Collection<TableMetadata> tables) { ... }
}
```

`JdbcMetadataProvider.load` 伪码：

```
① 驱动加载：无需额外机制——驱动为 pom 依赖（MySQL Connector/J，compile scope，随 shaded jar 分发），
   经 classpath 直接可见（D27，2026-10-15 修订）
   └─ 驱动类加载失败（兜底，正常不应发生）→ MetadataException
② 连接：DriverManager.getConnection(url, username, password)；LoginTimeout = connectionTimeoutSeconds
   └─ 连接失败/超时/权限不足 → MetadataException（消息含脱敏连接串与原因，不含密码）
③ 元数据读取（DatabaseMetaData，只读）：
   · getTables(catalog=schema 由 url 决定, null, {"TABLE"}, null)   → 表清单（系统表按驱动返回过滤）
   · 每表 getColumns(...)                                            → name + TYPE_NAME + 尺寸/精度
       - type 归一化：TYPE_NAME 基础 + 尺寸（如 varchar(64)、numeric(19,2)、timestamp(6)）；无尺寸类型保留原名
       - 跨库别名归一（M10 实测回写）：CHARACTER VARYING/CHAR VARYING → VARCHAR、CHARACTER → CHAR、INTEGER → INT
   · 每表 getIndexInfo(table, unique=false, approximate=false)       → INDEX_NAME + ORDINAL_POSITION 排序
       - 同名索引多行聚合为 [列序]；PRIMARY/主键索引保留驱动返回名（MySQL=PRIMARY）
④ 组装为不可变快照（表名/列名小写索引，语义与旧 File 实现一致）；重复表名取首条并警告
⑤ finally：Connection.close()（ClassLoader 保留至进程结束）；此后 provider 只读内存，不再触库
```

- 一次加载语义：`load` 在 P1 调用**一次**；同一进程不做第二次连接/加载（NFR）。
- 失败语义（承接 D12 v1.8 修订）：连接失败 / 权限不足 / `getTables|getColumns|getIndexInfo` 抛错 / 超时 → `MetadataException`（exit 2），无降级路径。
- `find` 未命中 → `Optional.empty()`（语义 = 该表无任何索引，M1/M6 按未命中判定）。
- 已移除（v1.8/D24）：`JsonMetadataProvider`、`--metadata`、yaml `metadata_file`（后者出现时告警忽略）；`examples/sql-risk-metadata.example.json` 不再随库交付。

***

## 8. diff 包

### 8.1 GitRepo 与提交解析

```java
public final class GitRepo implements Closeable {
    public static GitRepo open(String repoPath);                 // 非 git 仓库/路径非法 → DiffException
    public RevCommit resolveCommit(String refOrSha);             // resolve + RevWalk.parseCommit 双校验
    public RevCommit head();                                     // 缺省 head-commit 时用
    public RepoContentReader contentReader(RevCommit commit);    // 读取该提交树内文件内容
}
```

- `open`：`FileRepositoryBuilder().setGitDir(...)` 只读打开；绝不写 refs/工作区。
- `resolveCommit`：`repo.resolve(sha)` 为 null 或 `parseCommit` 抛 `MissingObjectException/IncorrectObjectTypeException` → `DiffException("非法 commit：<输入>")`。
- 全程不访问网络（JGit 本地对象库）。

### 8.2 RepoContentReader

```java
public final class RepoContentReader {
    public Optional<byte[]> read(String repoRelativePath);   // 经 TreeWalk 定位 blob，ObjectLoader 读取
    public List<String> listFiles(Predicate<String> filter);// FULL 模式：遍历提交树
}
```

- 编码解码：XML 用文件头 `<?xml encoding=` 探测（缺省 UTF-8），Java 源码固定 UTF-8。
- **数据源恒为提交树**（决策 D21/D22）：DIFF/FULL 两模式都读 head commit 的内容，工作区文件永不读取。

### 8.3 DiffService 与变更模型

```java
public final class DiffService {
    public ChangeSet diff(RevCommit prod, RevCommit head);   // JGit DiffCommand + DiffFormatter
}
public final class ChangeSet { Mode mode; String headCommit; @Nullable String prodCommit; List<FileChange> files; }
public final class FileChange { String path; DiffType type; List<Hunk> hunks; }
public final class Hunk {
    int oldStart, oldCount, newStart, newCount;
    Set<Integer> addedLines;      // 新文件行号（基于 RawText/EditList beginB 计算；M9 回写：JDK 升序 Set，原 IntSortedSet 系笔误）
    Set<Integer> deletedLines;    // 旧文件行号
    int anchorLineInNew;          // 纯删除 hunk 在新文件中的落点（newStart），用于语句级映射
}
```

- 路径过滤：仅保留 `*.java`、`*.xml`（其余 entry 直接丢弃）；`DELETE` 文件整体跳过（删除类不分析）；`RENAME` 按 DELETE+ADD 拆两条。
- `CommitTreeWalker.collectCandidates(head tree)`（FULL 模式）：`TreeWalk` 递归提交树，按扩展名过滤，排除目录：`target/`、`build/`、`out/`、`.idea/`、`node_modules/`（前缀匹配，可配置扩展）。

### 8.4 ProductionCommitProvider

```java
public interface ProductionCommitProvider { Optional<String> provide(RepoContext ctx); }
public final class CliArgProductionCommitProvider implements ProductionCommitProvider { ... }   // v1：取 --prod-commit
```

***

## 9. extractor 包

### 9.1 统一 SPI 与调度

```java
public interface SqlSourceExtractor {
    String name();
    boolean supports(CandidateFile file);
    ExtractResult extract(CandidateFile file, ExtractContext ctx);
}
// M9 回写：实际实现形态——注解/Lambda 两提取器实现本 SPI（supports 按扩展名分派）；
// mapper.xml 为「选择驱动」独立入口：MapperXmlExtractor.extract(List<StatementSelection>, contents)，
// 由 FragmentPropagator 先产出选择集（语句级命中依赖 diff 行范围，非逐文件扫描形态）。
public final class CandidateFile { String path; String content; DiffType diffType; @Nullable FileChange change; }
public final class ExtractContext {
    XmlFragmentIndex fragmentIndex; EntityIndex entityIndex;
    RepoContentReader contentReader; AppConfig config;
}
public final class ExtractResult { List<ExtractedStatement> statements; List<SkippedItem> skipped; }
```

调度规则（core 内实现）：

- `.xml` → `MapperXmlExtractor`；`.java` → `AnnotationSqlExtractor` 与 `LambdaSqlExtractor` **并行执行**（二者产出的 statementId 空间不同，天然不重叠；仍以 statementId 去重，重复时保留首个并警告）。
- 提取集合 = 直接变更文件中的变更语句 ∪ 片段派生变更命中的语句（§9.3 传播）。派生语句所在文件即使自身无 diff 也要读取内容（来自提交树）。
- 非 SQL 承载文件不进入清单、不计 skipped。

### 9.2 MapperXmlExtractor 与 SAX 处理

`MapperXmlSaxHandler` 单趟流式解析（JDK SAX + `Locator`，XXE 加固：禁外部实体，MyBatis DTD 由返回空内容的 `EntityResolver` 兜底）：

1. 根元素 `<mapper>`：记录 `namespace`；非 mapper XML → 返回空结果（不算 skipped）。
2. 语句元素（`select/update/insert/delete`）压栈记录：`id`、起始行（startElement 的 Locator 行号）、结束行（endElement 的 Locator 行号，JDK Xerces 语义为闭合标签位置【DD-3，单测覆盖】）。
3. 语句原文切片：按起止行从整文件文本截取（含首尾行），作为 `rawText`；`carrier` = 该片段文本。
4. `<sql id>` 元素与 `<include refid>` 由 §9.3 的索引统一处理（与语句同趟扫描记录）。
5. `statementId = namespace + "." + id`；语法完整但无法归属（如嵌套异常）由 SAXException 捕获 → `SkippedItem(READ_ERROR/PARSE_FAILED)`。
6. 变更判定：见 §9.4；非变更语句不产出条目（FULL 模式全部产出，changeType=FULL）。

### 9.3 XmlFragmentIndex（片段索引 + 反向闭包）

```java
public final class XmlFragmentIndex {
    public static XmlFragmentIndex build(List<CandidateFile> allXmlFiles);   // 全仓 mapper.xml，一趟 SAX/文件
    // 定义：key = namespace + "#" + sqlId，如 "com.demo.mapper.BaseMapper#baseColumns"
    public Optional<FragmentDef> define(String key);                          // file/startLine/endLine/namesp
    public List<String> statementKeysReferencing(String key);                 // 引用该片段的所有语句（含传递）
    public List<IncludeSite> includeSites(String key);                        // 引用点明细（用于闭包与 changedVia）
    public Set<String> closureOf(Set<String> changedFragmentKeys);            // 反向依赖闭包（BFS + visited）
}
```

结构：

| 结构            | 内容                                                                     |
| ------------- | ---------------------------------------------------------------------- |
| `fragments`   | key → `FragmentDef{namespace, sqlId, file, startLine, endLine}`         |
| `references`  | key → `List<IncludeSite{file, line, refidRaw, ownerKind, ownerKey}>`；`ownerKind ∈ {STATEMENT, FRAGMENT}` |
| `statementOf` | 语句 key（namespace.id）→ `StatementSite{file, startLine, endLine}`（供派生命中后按行范围提取原文） |

refid 规范化：含 `.` → 直接用（跨文件 `namespace.id`）；不含 → 本文件 namespace + "." + refid。无法解析 → 引用点标记 `unresolved`，提取该语句时走 §9.5 的 include 展开失败路径（该语句 parseFailed/SKIP）。

反向闭包算法（伪码）：

```
closureOf(changedFragments):
    result = {}; queue = changedFragments; visited = {}
    while queue 非空:
        f = queue.poll(); if !visited.add(f): continue
        for site in references[f]:
            if site.ownerKind == STATEMENT: result.add(site.ownerKey)   // 命中语句
            else: queue.add(site.ownerKey)                              // 上级片段，继续向上传播
    return result     // 传递 + 跨文件天然支持（索引是全仓的）
```

### 9.4 FragmentPropagator（差异 → 生效语句集合）

```
compute(ChangeSet cs, XmlFragmentIndex idx, EntityIndex eidx):
    directStatements = {}      // (statementKey → FileChange) 由行范围 ∩ 变更行得到
    propagated = {}            // statementKey → fragmentKey（changedVia）
    for fc in cs.files where fc.type != DELETE:
        changedLines = fc 的 added ∪ deleted 行集合
        if fc.path 是 .xml:
            命中片段 = { frag | frag.file==fc.path ∧ [startLine,endLine] ∩ changedLines ≠ ∅ }
            // 注意：片段定义行自身的改动才算片段变更；语句内 include 行改动由语句级覆盖
            for stmt in directStatements: 不额外处理
            for fragKey in 命中片段:
                for stmtKey in idx.closureOf({fragKey}):
                    若 stmtKey 已在 directStatements → 跳过（直接变更优先）
                    else propagated.put(stmtKey, fragKey)
        if fc.path 是 .java:
            变更命中方法/注解/链式范围 → directStatements（§9.5/§9.6 内部实现）
    产出 = direct(changeType=ADDED/MODIFIED) ∪ propagated(changeType=MODIFIED, changedVia)
```

**changeType 判定细则（M9 回写，黄金用例暴露语义）**：语句行区间**全部**为 head 新文件行 → `ADDED`（整行替换因旧行删除+新行插入同属"新文件行"，也归 `ADDED`）；与新增行部分交叠，或纯删除 hunk 落点（`anchorLineInNew`）落在语句区间内 → `MODIFIED`。`changedVia` 格式为规范化 refid：`namespace#片段id`（如 `com.demo.mapper.Common#orderCols`），传递引用记**根因种子片段**（§8.1/D20）。

### 9.5 注解类 SQL（AnnotationSqlExtractor）

1. JavaParser 解析（`JavaParserTypeSolver` 指向仓库 src 根 + `ReflectionTypeSolver`），遍历类型声明的**方法**，命中注解：`@Select/@Update/@Insert/@Delete/@SelectProvider/@InsertProvider/@UpdateProvider/@DeleteProvider`（简名匹配，忽略 import 来源）。
2. 注解 → SQL 文本（按优先级）：
   - 字面量（单值或数组以 `\n` 拼接）；
   - 静态常量引用 → `JavaConstantResolver.resolve(FQN 字段)`：支持 `static final String` 字面量与简单 `+` 拼接；失败 → skipped(UNSUPPORTED_CARRIER, "无法解析常量：…")；
   - `@*Provider` → `ProviderMethodResolver`：定位 `type.method`，支持 `return "字面量"`、字面量 `+`、`StringBuilder/StringBuffer.append(字面量)`；其余语法 → skipped("无法静态解析 Provider 方法")。
3. 以 `<script>` 开头 → carrrier 标记为脚本，交 §10.2 动态求值（与 mapper.xml 同管道）；否则纯文本，`#{}`/`${}` 保留到 §10.1。
4. 语句范围 = 方法声明范围（注解 + 签名 + 方法体，JavaParser `MethodDeclaration.getRange()`）；范围内任意行变更 → 整条提取。
   —— 该范围天然覆盖「SQL 注解行」与「参数声明行（含 `@Param`）」两类改动：改 SQL 或改 ew 参数绑定都会触发重新提取与分析。
5. `statementId = 接口全限定名 + "." + 方法名`。
6. **Wrapper SQL 段识别（v1.4 增补，FR-2 细化）**：纯文本注解 SQL 中，`${name…}` 占位符的**根名**（首个 `.` 前）解析到方法参数（`@Param("name")` 的值，或缺省取参数自身名），且该参数类型原始名以 `Wrapper` 结尾（MyBatis-Plus Wrapper 家族：`Wrapper/QueryWrapper/LambdaQueryWrapper/UpdateWrapper/AbstractWrapper/EntityWrapper…`，泛型自动剥离）→ 记入语句 `wrapperSqlParams`（有序去重），透传至解析与规则阶段。
   - 语义：该段 WHERE 由**框架经 Wrapper 参数**在运行期生成，属框架惯用法而非字面量拼串 → **M8 不命中**（§11.4）；解析时将占位符代换为中性谓词（见 §10.6）以正常解析，骨架标记"未知片段"（报告 `uncertain=true`，提示条件来自运行期）。
   - 绑定失配（如 `@Param` 改名而 SQL 未同步）→ 根名不再解析 → 恢复为 `${}` 拼串判定（可发现真实缺陷）。
   - 已知边界：mapper.xml 中的 `${ew…}` 暂不识别（语句与接口方法无静态绑定通道），仍按原文命中 M8。

### 9.6 JavaConstantResolver / ProviderMethodResolver

- 两 resolver 共用 `JavaSourceIndex`（全仓 .java 的 `CompilationUnit` 缓存，按 `(package, 类简名)` 建索引；构建一次、只读）。
- 支持语法子集（超出即失败，不猜测）：字符串字面量、`+` 连接、`StringBuilder.append` 链式字面量、`static final String` 常量引用（可递归一层）。

### 9.7 LambdaSqlExtractor 与链式模型

```java
public final class LambdaChainModel {
    String file; int rootLine;                       // 链式表达式根调用行（statementId 用）
    int rangeEndLine;                                // 链范围终点（含执行调用行）
    @Nullable String entityTypeFqn;                  // 实体解析结果（可空 → 表名占位 ?）
    List<LambdaCall> calls;                          // 源码顺序调用序列
    List<LambdaBranchGroup> branchGroups;            // 控制流分支组（M15 增补），无分支为空
}
public final class LambdaCall { String method; List<LambdaArg> args; boolean runtimeConditional;
                                List<LambdaCall> nested; List<LambdaBranchRef> branchPath; }
public final class LambdaBranchRef { int groupId; int pathIndex; }   // pathIndex=-1：恒不成立分支
public final class LambdaBranchGroup { enum Kind { IF, SWITCH }; int groupId; Kind kind; List<String> pathLabels; }
```

**控制流分支识别（M15 增补，需求方驱动）**：wrapper 变量在方法体内的后续调用按祖先控制流语句记录分支坐标（外层→内层）：

| Java 形态                     | 组路径（pathLabels）                                | 语义（对齐 XML）                          |
| --------------------------- | ---------------------------------------------- | ----------------------------------- |
| `if (…) {…} else {…}`       | `[if-then, if-else]`（then/else 内调用 pathIndex 0/1） | `<choose>`：渲染期枚举两分支（每分支一骨架）          |
| `if (…) {…}`（无 else）        | `[if-skip]`，体内调用 pathIndex=-1                 | `<if>`：保守视为不成立（渲染期丢弃 + 溯源记录）        |
| `for/foreach/while/do` 循环体  | `[loop-skip]`，体内调用 pathIndex=-1               | 同上（循环可能不执行）                         |
| `switch/case/default`       | 每 case/default 条目一条路径（空条目落穿并入后继；无 default 追加 `switch-none`） | `<choose>`：每组各分支枚举（default ↔ otherwise） |

- 变更检测不受影响：分支体内行、分支条件行均在 `[rootLine, rangeEndLine]` 内 → 照常整句提取（ac13 验证）。
- 嵌套（if 内 if）→ 坐标叠加，渲染期按组合枚举（上限见 §10.3）。

识别入口（AST 模式匹配）：

| 入口                                                                  | 实体解析来源                                |
| ------------------------------------------------------------------- | ------------------------------------- |
| `new LambdaQueryWrapper<X>()` / `new LambdaUpdateWrapper<X>()`       | 显式泛型                                  |
| `Wrappers.<X>lambdaQuery()/lambdaUpdate()`                           | 显式泛型                                  |
| `Wrappers.lambdaQuery(X.class)`                                      | Class 参数                              |
| `<mapper>.lambdaQuery()/lambdaUpdate()`                              | mapper 变量类型 → `extends BaseMapper<T>` |
| `<ServiceImpl 子类>.lambdaQuery()/lambdaUpdate()`                      | 类泛型 `ServiceImpl<M,T>` 第二实参            |
| `new QueryWrapper<X>().lambda()` / `Wrapper.lambda()`                 | 显式泛型 / 变量声明类型                         |

实体→表/列映射由 `EntityIndex` 提供（§9.8）；解析失败 → `entityTypeFqn=null`（表名占位 `?`，元数据类规则按"未知表=无索引"保守命中【D13】）。

**语句范围（增量语义，D20）**：`[rootLine, rangeEndLine]`；`rangeEndLine` 的确定：若链表达式的值赋给变量 `w`，则扫描同方法内后续对 `w` 的**执行调用**（`mapper.selectList(w)` 等），取最后一个执行调用行；无变量接收则范围终点 = 链表达式结束行。范围内任意一行变更 → 整条链提取。

还原不完整场景（wrapper 作方法参数传入、条件在外部拼接）→ `uncertain=true`，按"可能缺失条件"继续（D11）；完全无法构造链模型 → skipped(UNSUPPORTED_CARRIER)。

### 9.8 EntityIndex

```java
public final class EntityIndex {
    public static EntityIndex build(List<CandidateFile> allJavaFiles, JavaSourceIndex srcIdx);
    public Optional<EntityMeta> entity(String fqn);              // @TableName/@TableField 映射
    public Optional<String> entityOfMapper(String mapperFqn);    // BaseMapper<T>/ServiceImpl<M,T> 反解
}
public final class EntityMeta { String table; Map<String/*property*/, String/*column*/> columns; }
```

- `@TableName("t_order")` → 表名；缺省 → 类名驼峰→下划线；`@TableField("user_id")` → 列名；缺省 → 属性名驼峰→下划线；`@TableId` 视同 `@TableField`；`@TableField(exist=false)` 忽略。
- 不读取业务应用的 MP 全局配置（如 table-prefix），列为已知限制。

***

## 10. parser 包

### 10.1 PlaceholderNormalizer

```java
public final class PlaceholderNormalizer {
    public NormalizedSql normalize(String sqlText);
}
public final class NormalizedSql { String text; boolean hasDollarPlaceholder; }
```

- `#\{[^}]*\}` → `?`；`\$\{[^}]*\}` → `?` 且置 `hasDollarPlaceholder`。
- 仅在骨架文本上执行；M8 判定用**语句原文**（含被丢弃分支，见 §11.4 M8），与本文互不替代。
- Druid 解析前必须完成归一化（`#{...}` 非合法 MySQL 语法）。

### 10.2 DynamicEvaluator（动态 SQL 保守求值）

```java
public final class DynamicEvaluator {
    public Iterator<SqlSkeleton> evaluate(String xmlFragmentText, DynamicEvalContext ctx);
}
public final class DynamicEvalContext {
    XmlFragmentIndex fragmentIndex; String ownerNamespace; AppConfig config;   // 保守策略无开关，恒 TRUE
}
```

实现两段式：

1. **小 XML 树构建**：对片段文本做 SAX 解析（无 DTD），构建轻量节点树 `DynNode {name, attrs, children(文本|节点)}`。
2. **求值**：深度优先渲染，产出骨架；`<choose>` 处形成分支点。

折叠规则（与概要设计 §8.2 逐条对应）：

| 构造                        | 求值行为                                                                     | DroppedBranch 记录                                        |
| ------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------- |
| 文本节点                      | 原样输出（XML 实体已解码，如 `&lt;`→`<`）                                             | —                                                       |
| `<if test>`               | **恒不纳入**（保守策略固定启用）                                                       | `{IF, CONSERVATIVE_FALSE, role}`，role 按上下文推断            |
| `<choose>`                | 生成 2 路：`第一个 <when>` 的 body / `<otherwise>` 的 body；无 otherwise 时第二路为空片段        | 未选中分支记 `{WHEN/OTHERWISE, CONSERVATIVE_FALSE, role}`       |
| `<where>`                 | 先求值内部；结果非空则输出 `WHERE ` + 去除首部多余 `AND/OR`；为空则整块省略                         | —                                                       |
| `<set>`                   | 结果非空则输出 `SET ` + 去除尾部多余 `,`；为空则省略                                         | —                                                       |
| `<trim>`                  | 按 `prefix/suffix/prefixOverrides/suffixOverrides` 标准行为折叠                 | —                                                       |
| `<foreach>`               | **单元素展开**：`open` + 一次 body 渲染（`#{item}`→`?`）+ `close`                       | —                                                       |
| `<include refid>`         | 经 `fragmentIndex` 解析片段定义 → 原文切片 → 递归求值；`<property>` 值替换被包含文本中的 `${name}`   | 展开失败/未解析 refid → 整体求值失败（该语句 → parseFailed/SKIP）        |
| `<script>`（注解来源）          | 先剥离外层 `<script>` 标签再按上表求值                                                 | —                                                       |
| 其它未知标签                    | 原样透传文本内容并置 `hasUnknownFragment=true`（不崩溃）                                 | —                                                       |

**role 推断**（供 H1/H2/H3 提示附注，不做独立规则，决策 D19）：丢弃片段位于 `<where>`/`property` 为条件前缀的 `<trim>` 内 → `CONDITION`；位于 `<set>` 内 → `SET`；片段文本含 `JOIN`/`ON` → `JOIN_ON`；**丢弃片段文本以 `WHERE` 开头（裸 `<if test="…">WHERE …</if>` 形态，折叠即 WHERE 消失）→ `CONDITION`**；否则 `RAW`。<!-- M9 回写：补充裸 if-WHERE 形态，修复 H1/H2/H3 保守附注在该常见形态下缺失的问题（ac03 黄金用例暴露） -->

**骨架枚举与上限**（惰性，配合规则引擎"任一骨架命中即停"）：

```
evaluate(...): 返回 Iterator<SqlSkeleton>（深度优先惰性展开 choose 组合）
- strategyTag：无 choose 时 "base"；有则路径拼接，如 "choose:when#0/choose:otherwise"
- MAX_SKELETONS = 8【DD-4】：枚举超出上限时停止生成，已生成骨架照常判定并将骨架标记 truncated=true（报告 uncertain=true）
- choose 无 otherwise：第二路为空片段（仍生成，能暴露"WHERE 缺失"类风险）
```

### 10.3 LambdaSkeletonRenderer

```java
public final class LambdaSkeletonRenderer {
    public List<SqlSkeleton> renderAll(LambdaChainModel chain, EntityIndex entityIndex); // M15：分支枚举
    public SqlSkeleton render(LambdaChainModel chain, EntityIndex entityIndex);          // 兼容：首个骨架
}
```

渲染流程：

```
① entity 映射 → table、列解析函数 col(SFunction 引用名字)：@TableField 优先，否则属性名驼峰→下划线
② 谓词树构建（连接符状态机）：默认 AND；无参 or() 切换连接符为 OR；and()/or(Consumer)/nested(Consumer)
   递归子组（组内重置为 AND）；组渲染时加括号
③ 逐调用归约（方法表见下）；非字面量的条件/片段 → dropped 或 unknown（保守处理）
④ 输出骨架：SELECT <cols|*> FROM <table> [WHERE <树>] [GROUP BY …] [HAVING …] [ORDER BY …] [后缀]
             UPDATE <table> SET <项列表> [WHERE <树>]      DELETE FROM <table> [WHERE <树>]
⑤ strategyTag="base"；uncertain = (entity==null || 存在 unknown 片段 || 链不完整)
⑥ 控制流分支枚举（M15 增补）：按 branchGroups 枚举行路径组合（笛卡尔积，单路径组不参与），
   每组合过滤调用（未选中/恒不成立分支的调用记 DroppedBranch：未选中 sibling → RUNTIME_CONDITION，
   无 else 的 if / 循环体 → CONSERVATIVE_FALSE；分支类型 IF/WHEN(default→OTHERWISE)，
   角色 set 族 → SET 否则 CONDITION），各生成一个骨架；组合数超 MAX_SKELETONS=8 截断
   （truncated=true，DD-4）；strategyTag="branch:<所选路径标签, …>"（无分支组为 "base"）。
   多骨架由规则引擎"首命中即停"消费（AC-4）——如 ac13：switch default 路径骨架命中 H1（skeleton=1）。
```

链式方法归约表（MyBatis-Plus 3.5.x，全量覆盖；带布尔首参的方法统一走"条件处理规则"）：

| 方法族                                        | 归约产物                        | 备注                                       |
| ------------------------------------------ | --------------------------- | ---------------------------------------- |
| `eq/ne/gt/ge/lt/le`                        | `col op ?`                  | 值非字面量也参与（值是运行期参数，不影响列与结构）                 |
| `between/notBetween`                       | `col BETWEEN ? AND ?`       | —                                        |
| `like/notLike`                             | `col LIKE ?` / `LIKE` 取反     | —                                        |
| `likeLeft`                                 | `col LIKE ?` + 置 `leadingWildcard=true` 事实 | M3 对确定性前导 `%` 判定                          |
| `likeRight`                                | `col LIKE ?`                | —                                        |
| `isNull/isNotNull`                         | `col IS [NOT] NULL`         | 计入谓词列                                    |
| `in/notIn`                                 | `col IN (?)`（单元素展开）         | inSql/notInSql 见下                          |
| `inSql/notInSql`                           | `col IN (子查询原文)`，仅字面量字符串      | 非字面量 → unknown 片段 + uncertain            |
| `exists/notExists`                         | `EXISTS (原文)`，仅字面量          | 非字面量 → unknown + uncertain               |
| `groupBy/orderByAsc/orderByDesc`           | `GROUP BY … / ORDER BY …`   | —                                        |
| `having(String)`                           | `HAVING 原文`（字面量）           | 非字面量 → unknown + uncertain               |
| `select(SFunction…)`                       | 显式列列表（替代 `*`）               | 含 `distinct` 变体：置于 SELECT 后               |
| `set(col,val)/set(cond,col,val)`           | SET 项 `col=?`               | 条件处理规则                                   |
| `setSql(String)/setSql(cond,String)`       | SET 项原文                     | 非字面量 → unknown + uncertain               |
| `setIncrBy/setDecrBy`                      | `col = col + ?` / `col = col - ?` | —                                        |
| `apply(String, Object...)`                 | 原文按 `{n}`→`?` 拼入            | 非字面量 → unknown + uncertain               |
| `last(String)`                             | 追加后缀；含 `limit` 则置 hasLimit  | 非字面量 → LIMIT 事实记不确定（对 H3 按无 LIMIT）        |
| `and()/or()`（无参）                           | 连接符切换                       | 首个调用前导 or() 忽略                           |
| `and(Consumer)/or(Consumer)/nested(Consumer)` | 递归子组（括号内 AND 默认）             | 组内同样应用条件规则                               |
| `func(Consumer)`                           | 子组原样渲染（函数语义无法静态还原，按谓词组）     | —                                        |
| `comment/hints/setEntityClass/setParamAlias/其他非条件方法` | 忽略（不改变 SQL 结构）              | —                                        |
| **未收录方法名**                                 | 忽略则该条件丢失 → 由 H1/H2/H3 暴露     | 置 `hasUnknownFragment=true` + uncertain |

**条件处理规则**（所有带布尔首参的重载统一）：`condition` 为字面量 `true` → 纳入；其余表达式（变量/方法调用/字面量 false）→ **丢弃该条件**并记 `DroppedBranch{RUNTIME_CONDITION, CONDITION, 方法名+行}`（保守策略，暴露漏条件风险）。无布尔参数的 2 参形式 → 恒纳入。

### 10.4 DruidSqlParser

```java
public final class DruidSqlParser {
    public SQLStatement parse(String normalizedSql, SqlDialect dialect);   // v1.8：SQLParserUtils.createSQLStatementParser(sql, dialect.dbType())
}                                                         // 异常统一包装为 SqlParseException（携带原文与原因）
```

`SQLUtils.toMySqlString(statement)` 用于生成 snippet 与报告展示（统一格式）。

### 10.5 QueryFactsBuilder（语句事实）

```java
public final class QueryFactsBuilder {
    public QueryFacts build(SQLStatement ast, @Nullable MetadataProvider metadata, SqlSkeleton skeleton);
}
```

`QueryFacts` 字段与计算规则：

| 字段                                            | 计算                                                                                         |
| --------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `kind`                                        | `instanceof` SQLSelectStatement / SQLUpdateStatement / SQLDeleteStatement / SQLInsertStatement |
| `hasWhere`                                    | SELECT:`queryBlock.getWhere()!=null`；UPDATE/DELETE:`getWhere()!=null`                            |
| `hasLimit`                                    | SELECT:`queryBlock.getLimit()!=null` 或 `dialect.hasRowLimit(ast)`（v1.8：分页子句识别交由方言，MySQL=LIMIT）；UPDATE/DELETE 同理 |
| `selectAll`                                   | selectList 含 `SQLAllColumnExpr`（含 `t.*` 的 SQLPropertyExpr）                                     |
| `tables`                                      | FROM/JOIN 递归收集 `SQLExprTableSource`（剥 schema 前缀）                                              |
| `predicateColumnsByTable`                     | WHERE 子树中比较/IN/BETWEEN/LIKE/IS NULL 的列侧；有 alias 按 alias 归表，单表语句归该表，多表歧义归 `UNRESOLVED`（从 M1 判定中排除） |
| `joinOnColumnsByTable`                        | 各 JOIN 的 ON 条件列                                                                             |
| `hasJoinWithoutOn`                            | 存在 `SQLJoinTableSource` 且 `condition==null` 且 joinType != COMMA                              |
| `hasCommaJoin`                                | 存在 joinType == COMMA 的连接                                                                     |
| `orBranches`                                  | WHERE 树中所有 `BooleanOr` 节点的两侧操作数列表（用于 M6）                                                     |
| `hasFunctionWrappedColumn`                    | 比较表达式任一侧为 `SQLMethodInvokeExpr` 且实参含列引用                                                     |
| `hasLeadingWildcardLike`                      | LIKE 右侧为 `SQLCharExpr` 且以 `%` 开头；或骨架标记 leadingWildcard（Lambda likeLeft）                          |
| `hasImplicitCast`                             | 列（元数据类型为数值）与字符串字面量比较，或列（字符类型）与数值字面量比较；类型缺失 → false                                          |
| `droppedBranches`                             | 来自 SqlSkeleton 透传                                                                           |
| `uncertain`                                   | 骨架 uncertain 透传                                                                             |
| `hasCrossTableWhereLink`                      | WHERE 条件中是否存在引用 ≥2 张表列的关联（供 M5 逗号连接判定）                                                          |

遍历实现：自定义 `ASTVisitorAdapter` 子类逐节点访问（不依赖 `SchemaStatVisitor`，保证行为可控与可测）。

### 10.6 ParsedStatement / ParsedSql

```java
public final class ParsedStatement { ExtractedStatement source; List<ParsedSql> parsed; }
public final class ParsedSql {
    SqlSkeleton skeleton; NormalizedSql normalized; SQLStatement ast; QueryFacts facts;
}
```

解析失败处理：某骨架归一化/解析抛 `SqlParseException` → 该**语句**整体计入 skipped(`PARSE_FAILED`, reason=`"解析失败：" + 原因`)，不再参与规则；其余语句不受影响。规则引擎按需惰性请求骨架（配合 §10.2 惰性枚举），首个命中骨架即停止，后续骨架不生成、不解析。

**Wrapper SQL 段代换（v1.4，配合 §9.5 第 6 条）**：注解载体且语句 `wrapperSqlParams` 非空时，骨架生成前将根名匹配的 `${…}` 占位符代换为中性谓词，保证语句可解析——
- 代换文本按占位符前的上下文确定：其前非空白内容以 `WHERE/AND/OR/NOT/ON/(` 结尾（占位符提供"条件表达式"）→ `1 = 1`；否则（占位符自带 WHERE 的 `customSqlSegment` 惯用法）→ ` WHERE 1 = 1`；
- 代换后骨架 `hasUnknownFragment=true`（报告 `uncertain=true`："条件来自运行期 Wrapper"）；
- 代换后仍不可解析 → 走既有"含 `${}` 短路候选"路径保留（M8 因根名被覆盖而不命中 → 按 §11.2-④ 归 SKIP）；
- 解析失败兜底（§8.5 结构性保证）语义不变：**未解析**的 `${}` 仍以空骨架候选交语句级短路（M8）定级；未命中的空骨架归 SKIP（§11.2-④，v1.6）。

### 10.7 SqlDialect（方言 SPI，v1.8 / FR-9 / D25）

```java
public interface SqlDialect {
    String name();                            // 配置值："mysql"（v1 唯一实现）
    DbType dbType();                          // Druid 方言映射
    boolean hasRowLimit(SQLStatement ast);    // 分页/限制子句识别（H3/L4 的 hasLimit 事实来源）
}

public final class SqlDialects {              // 注册表（静态注册，确定性顺序）
    public static SqlDialect of(String name);         // 未知 → ConfigException（exit 2，不降级）
    public static Set<String> supportedNames();       // v1.8：{"mysql"}
}

public final class MysqlDialect implements SqlDialect {
    // dbType()=DbType.mysql；hasRowLimit()=SELECT 查 queryBlock.getLimit()；
    // UPDATE/DELETE 查 MySqlUpdateStatement/MySqlDeleteStatement.getLimit()（现行为迁入）
}
```

- **预留扩展实现要点**（文档即契约，实现方言时按此落地）：
  - `PostgresqlDialect`：`DbType.postgresql`；`hasRowLimit` = `LIMIT` 或 `FETCH FIRST n ROWS ONLY`；
  - `OracleDialect`：`DbType.oracle`；`hasRowLimit` = `FETCH FIRST n ROWS ONLY` 或 WHERE 子树含 `ROWNUM` 谓词（含嵌套子查询包裹形态）；
  - 新增方言流程 = 实现 `SqlDialect` → `SqlDialects` 注册 → 用例（含分页识别正/反例）→ 放开配置值与 `supportedNames`；核心管线与 13 条规则**零改动**。
- 方言贯穿点：`DruidSqlParser.parse(sql, dialect)`（§10.4）与 `QueryFactsBuilder` 的 `hasLimit`（§10.5）；**方言经 `ParseContext` 装配**（M11 实现回写：3 参构造缺省 MySQL 保持兼容，引擎以配置值注入）；报告/snippet 渲染仍统一走 `SQLUtils.toMySqlString`（展示层，与解析方言解耦）。
- 未实现方言值（`oracle`/`postgresql`）→ **ConfigException exit 2**（明确报错，不静默降级为 MySQL 解析）。

***

## 11. rule 包

### 11.1 接口与上下文

```java
public interface Rule {
    String id();
    RiskLevel defaultLevel();
    int priority();                 // 升序执行
    boolean shortCircuit();         // 命中后该语句停止后续全部规则
    List<Finding> check(RuleContext ctx);
}
public final class RuleContext {
    ParsedSql parsedSql; StatementMeta meta; MetadataProvider metadata; AppConfig config;
    String rawText;                 // 语句原文（含被丢弃分支）—— M8/L1 用
    QueryFacts facts();             // 骨架事实；语句级上下文（parsedSql=null）调用即抛 IllegalStateException
}
public interface RuleProvider { List<Rule> rules(); }
```

### 11.2 RuleEngine（执行流程）

```
run(List<ParsedStatement> statements, RuleEngineDeps deps) -> Outcome{results, skipped}:
  for stmt in statements:
    // ① 语句级短路（M8）：扫描语句原文，命中即定级并结束该语句
    if M8.enabled && rawText 匹配 \$\{[^}]*\}:
        result = StatementResult(level=MEDIUM, findings=[M8])
        continue
    // ② 骨架循环：惰性请求骨架，任一骨架命中即停止（决策 D7/AC-4）
    hit = null
    for pSql in parsedStatement.parsedIterable():        // 惰性
        findings = allEnabledRules.sorted().map(r -> r.check(ctx(pSql))).flatten()
        if findings 非空: hit = (pSql, findings); break
    // ③ 归类
    if hit != null: result = StatementResult(level = max(levels), sql = hit.skeleton.text, …)
    else:           result = StatementResult(level = null, sql = 首个骨架文本, …)
    // ④ 空骨架归口（v1.6）：parsed 为空（含 ${} 解析失败的短路候选）且 ① 未命中
    //    → skipped += SkippedItem(PARSE_FAILED, "解析失败：无可用 SQL 骨架（含 ${} 且未命中短路规则）")
    //    不再作为 level=null 结果输出（"无法分析" ≠ "已分析且无风险"）
```

- **空骨架归口（v1.6，需求方驱动）**：解析阶段对含 `${}` 的失败语句保留空骨架"短路候选"（D8 结构性保证）不变；**短路命中（M8/AC-11）仍按原语义输出 MEDIUM**；未命中的空骨架不再进入 results，改由 `Outcome.skipped` 计入 SKIP（口径同解析失败，不影响退出码）。

- 规则排序：`priority` 升序、同优先级按 ruleId 字典序（稳定输出）。
- 单条规则 `check` 抛出异常（防御式）→ 捕获、stderr 警告（含 ruleId/statementId）、跳过该规则。

### 11.3 RuleRegistry

- 静态注册内置规则（13 个类，见 §11.4）；`ServiceLoader<RuleProvider>` 追加外部规则（ID 与内置冲突 → 警告忽略）。
- 应用 `AppConfig.ruleSettings`：覆盖 `level`、`enabled`；被禁用规则不参与执行；未知 ruleId 已在 config 层告警。
- 规则优先级、短路标记为代码硬编码，不可配置。

### 11.4 内置规则详设

通用约定：`snippet` 取规范化 SQL 文本中相关片段（缺省全文），超 120 字符截断加 `…`；message 模板见各节。

#### H1 — UPDATE 无 WHERE（HIGH / 优先级 10）

- 判定：`kind==UPDATE && !hasWhere`。
- message：`UPDATE 语句缺少 WHERE，将全表更新，高危`；若 `droppedBranches` 含 `role=CONDITION` → 追加 `（WHERE 条件位于动态分支内，按保守策略视为不成立）`。
- 保守折叠致 WHERE 整体消失时同样命中（决策 D19）。

#### H2 — DELETE 无 WHERE（HIGH / 11）

- 判定：`kind==DELETE && !hasWhere`；message 同 H1 模板（"将全表删除"）。

#### H3 — SELECT 全表扫描（HIGH / 12）

- 判定：`kind==SELECT && !hasWhere && !hasLimit`（覆盖 `SELECT *` 且无过滤的无限制场景，决策 D19）。
- message：`SELECT 无有效过滤条件（无 WHERE 且无 LIMIT），存在全表扫描风险`。

#### M1 — 未命中索引（MEDIUM / 20）

```
对每个表 t in facts.tables:
    cols(t) = predicateColumnsByTable(t) ∪ joinOnColumnsByTable(t)    // 排除 UNRESOLVED
    if cols(t) 为空: continue                                        // 表无过滤职责（如仅出现在 SELECT 列表）
    meta = metadata.find(t)
    if meta 不存在: 记 unindexedTables += (t, cols(t), "表不在元数据中，默认无索引")   // D13/§8.7
    else if 不存在任何索引其首列 ∈ cols(t): unindexedTables += (t, cols(t), "未命中索引前缀")
if unindexedTables 非空: 命中
```

- message：`条件列未命中索引：t_order(user_id, status)（表不在元数据中，默认无索引）`；多表逐条串联。
- 边界：所有表均无过滤职责（如无 WHERE 的语句）→ M1 不判定（由 H 组覆盖）。

#### M2 — 索引列被函数包裹（MEDIUM / 21）

- 判定：`hasFunctionWrappedColumn`；snippet 输出该比较表达式（`SQLUtils.toMySqlString` 化简）。
- message：`索引列被函数包裹，无法走索引：{expr}`。

#### M3 — 前导通配符 LIKE（MEDIUM / 22）

- 判定：`hasLeadingWildcardLike`（含 Lambda `likeLeft()` 的确定性前导 %）。
- message：`前导通配符 LIKE 无法走索引：{expr}`；非字面量模式不判定。

#### M4 — 隐式类型转换（MEDIUM / 23）

- 判定：`hasImplicitCast`；类型信息缺失时该比较不参与（需求 §15）。
- message：`存在隐式类型转换，可能无法命中索引：{expr}（{col} 为 {type}）`。

#### M5 — 笛卡尔积（MEDIUM / 24）

- 判定：`tables.size() ≥ 2 && (hasJoinWithoutOn || (hasCommaJoin && !hasCrossTableWhereLink))`。
- message：`多表连接缺少关联条件，可能产生笛卡尔积：{tables}`。

#### M6 — OR 非索引条件（MEDIUM / 25）

- 判定：`hasOr && 存在某个 orBranch 不满足"含至少一个命中索引的列"`（分支无列引用如 `1=1` 视为不满足）。
- 索引信息同 M1（表不在元数据 → 未命中）。
- message：`OR 连接包含未命中索引的条件，可能全表扫描`。

#### M8 — `${}` 拼串（MEDIUM / **-100**，`shortCircuit=true`）

- 判定：语句**原文**（`meta` 关联的 `rawText`，含被丢弃分支）匹配 `\$\{[^}]*\}`；**排除根名已解析为 Wrapper 参数的占位符**（§9.5 第 6 条，v1.4）——全部 `${…}` 均被覆盖时不命中，其余任一未覆盖即命中。
- 行为：语句级短路，输出唯一 Finding，不再评估任何骨架与规则（AC-11）。
- message：`存在 ${} 拼串，归属中危，不再继续判断其他规则`。

#### L1 — 不推荐写法关键字（LOW / 30）

- 判定：规范化（去多余空白、大写化）后的语句原文包含配置的关键字（同样规范化）任一。
- message：`使用了不推荐写法：{keyword}`。

#### L2 — SELECT *（LOW / 31）

- 判定：`kind==SELECT && selectAll && (hasWhere || hasLimit || tables.size()>1)`（要求"已有过滤"）。
- message：`SELECT * 且已有过滤，建议显式列出字段`。

#### L4 — 未加 LIMIT（LOW / 32）

- 判定：`kind==SELECT && !hasLimit && hasWhere`。
- message：`未限制返回行数，建议添加 LIMIT`。

> 规则编号 L3、M7 保留不复用（已移除/缺号）；新增规则从 L5、M9 起编。

***

## 12. report 包

### 12.1 结构

`ReportModel` 字段顺序即 JSON 字段顺序（Gson 按声明顺序序列化）：`generatedAt → mode → headCommit → prodCommit → summary → results → ignored → skipped`。

### 12.2 ConsoleReporter

```
[SQL 风险检查] mode={mode}  head={headCommit 前 7 位}{ prod={prodCommit 前 7 位}（DIFF）/ 无（FULL）}
仓库: {repoPath}   扫描文件: {n}   提取语句: {total+skip+ignored}
高危 {high} | 中危 {medium} | 低危 {low} | SKIP {skip} | 已忽略 {ignored}
------------------------- 高危明细 -------------------------
[{ruleId}] {statementId}
     {file}:{line}  ({changeType}{ changedVia=… 若有})
     {sql（截断 200 字符）}          ← {message}
…（中危/低危明细仅 --verbose 输出）
报告已生成: {outputDir}/sql_risk_report.json
            {outputDir}/sql_risk_report.html
```

- 无语句：输出 `无差异语句`（DIFF 空差异）或 `未提取到 SQL 语句`；任一情况均正常 exit 0。
- 非 TTY（`System.console()==null`）不输出 ANSI 颜色。

### 12.3 JsonReporter

- Gson 配置：`serializeNulls()`（FULL 模式 `prodCommit: null` 键保留）、`disableHtmlEscaping()`、UTF-8 输出、`setPrettyPrinting()`。
- 序列化用 DTO（`report.dto` 内部类）与模型 1:1 映射，字段名固定：`generatedAt/mode/headCommit/prodCommit/summary{total,high,medium,low,skip,ignored}/results[]{statementId,source,file,line,changeType,sql,findings[]{ruleId,level,message,snippet,skeleton},level,uncertain,changedVia}/ignored[]{statementId,file,line,reason}/skipped[]{statementId,file,line,kind,reason}`。
- `sql` 字段语义（M9 回写）：命中规则时输出**命中骨架**；未命中输出骨架 #0；M8 语句级短路命中输出折叠空白后的**语句原文**（mapper.xml 语句含 XML 标签，为"原文扫描"语义的直接呈现）。控制台明细对 sql 统一折叠空白并截断 200 字符。
- 完整示例见附录 A。

### 12.4 ReportWriter

- 目标：`{outputDir}/sql_risk_report.json`；目录不存在则创建；已存在则覆盖；写失败（权限/磁盘）→ `ReportException`（exit 2）。

### 12.5 HtmlReporter（交付后增补，默认双产出）

- 目标：`{outputDir}/sql_risk_report.html`（与 JSON **默认同时产出**；写失败同 → `ReportException` exit 2）。JSON 仍是机器契约（CI/黄金样例基准），HTML 为**人工阅读层**。
- 单文件、离线自包含：内联 CSS + 原生 JS（无 CDN/外链，满足不联网约束）；全部动态文本 HTML 转义（语句原文含 XML 标签/比较符必须转义）。
- 结构：运行头（mode/commits/仓库/生成时间）→ 统计区（高/中/低/**通过**/SKIP/已忽略 + 口径恒等式行）→ 筛选栏（级别按钮 / 规则下拉 / 文件下拉 / 关键字）→ 分区明细（**高危默认展开**；中危/低危/**通过**/SKIP/已忽略默认折叠，`<details>` 原生可展开）→ 页脚（注明 JSON 契约）。
  「通过」= 参与分析且未命中任何规则的语句数（v1.7 由"未命中风险规则/无命中"更名，含头部统计卡片与恒等式行口径；JSON 契约不变）。
- 卡片模型：一条语句一张卡片（级别徽标 + statementId + `file:line (changeType, via …)` + 完整语句 SQL + 各 finding 消息与骨架序号）；级别筛选按语句最高级别。
- 交互（原生 JS，无第三方）：级别/规则/文件/关键字四维筛选联动显隐卡片并实时显示可见计数；无 JS 环境下靠 `<details>` 仍可完整阅读。
- 确定性：同一 `ReportModel` 输出逐字节一致（渲染不含当前时间等运行时值）；HTML 不参与黄金样例比对。

***

## 13. core 包

### 13.1 RunContext

```java
public final class RunContext {
    String repoPath; Mode mode; @Nullable String headCommitArg, prodCommitArg;
    AppConfig config;               // 含 datasource 连接配置与 dialect（v1.8）
    String outputDir; boolean verbose; Clock clock;
}
```

`mode` 判定：`prodCommitArg == null → FULL`，否则 `DIFF`（`--prod-commit` 与 `--head-commit` 相同时也按 DIFF，空差异快速返回）。

### 13.2 AnalysisEngine（P0~P9 编排）

```java
public final class AnalysisEngine {
    public ReportModel execute(RunContext ctx);
}
```

```
execute(ctx):
  # P1 配置已在 main 完成；此处加载元数据（v1.8：JDBC 一次加载，连接随加载关闭）
  metadata = JdbcMetadataProvider.load(ctx.config.datasource)            # 驱动/连接/权限/超时失败 → exit 2
  dialect  = SqlDialects.of(ctx.config.dialect)                          # 配置已校验；此处取实现
  gitRepo  = GitRepo.open(ctx.repoPath)                                  # 失败 → exit 2
  head     = gitRepo.resolveCommit(ctx.headCommitArg ?? "HEAD")          # 失败 → exit 2
  reader   = gitRepo.contentReader(head)

  # P2 模式判定与数据获取（数据源恒为提交树）
  if DIFF:
      prod = gitRepo.resolveCommit(ctx.prodCommitArg)                    # 失败 → exit 2
      if prod == head: return 空报告(mode=DIFF)                          # exit 0，提示"无差异语句"
      cs = DiffService.diff(prod, head)
      xmlCandidates = cs 中 xml 变更文件（非 DELETE）
      javaCandidates = cs 中 java 变更文件
  else:
      allCandidates = CommitTreeWalker.collectCandidates(head tree)
      xmlCandidates = javaCandidates = allCandidates                      # changeType=FULL

  # 索引构建（全仓范围：DIFF 模式也需全部 xml/java 以支持片段传播与实体解析）
  fragmentIndex = XmlFragmentIndex.build(全部 xml 候选（提交树）)
  srcIndex      = JavaSourceIndex.build(全部 java 候选)
  entityIndex   = EntityIndex.build(全部 java 候选, srcIndex)

  # P3 提取（并行：文件级）
  direct, propagated = FragmentPropagator.compute(cs, fragmentIndex, entityIndex)   # FULL 模式跳过
  提取任务集合 = 直接变更文件（xml/java） ∪ 派生命中语句所在文件
  逐文件执行 extractors → ExtractedStatement[] + skipped[]
  去重（statementId）、补 changedVia、排序

  # P4 白名单
  filter → ignored[]；其余进入分析集合

  # P5/P6 骨架 + 解析（并行：语句级）；惰性骨架由规则引擎按需拉取，此处仅构建迭代器
  # P7 规则
  results = RuleEngine.run(分析集合, deps(metadata, config, registry))

  # P8 汇总（§13.4）
  summary = SummaryCalculator.calc(results, ignored, skipped)

  # P9 输出由 main 完成（写 JSON + 打印）
  return ReportModel(clock.now(), mode, head.name(), prod?, summary, …)
```

并行调度细节：

- 线程池：`Executors.newFixedThreadPool(min(Runtime.availableProcessors(), 8))`；P3、P5~P7 两段复用同一池，`shutdown` 于 execute 结束。
- 每线程资源：`JavaParser` 的 `CombinedTypeSolver` 实例按线程创建（`ReflectionTypeSolver` + `JavaParserTypeSolver(src index)`）。
- 结果收集后按 `(file, line, statementId)` 排序，保证确定性（§16）。
- 单文件/单语句任务异常 → 捕获转 `SkippedItem(READ_ERROR/PARSE_FAILED)`，不中断。

### 13.3 WhitelistFilter

```java
public final class WhitelistFilter {
    List<IgnoredItem> filter(List<ExtractedStatement> stmts, List<String> patterns);
}
```

- 仅 `MAPPER_XML`、`ANNOTATION` 参与；`LAMBDA` 直接放行（D6 语义、FR-6）。
- 匹配：`id.equals(p) || (p.endsWith(".*") && id.startsWith(p.substring(0, p.length()-1)))`。
- 命中 → `IgnoredItem(..., "whitelist")`，不参与 P5 之后任何阶段。

### 13.4 SummaryCalculator（口径公式）

```
total   = results.size()                       // 参与分析的语句数
high    = count(results, level==HIGH)          // 按最高命中级别互斥归类
medium  = count(results, level==MEDIUM)
low     = count(results, level==LOW)
skip    = skipped.size()                       // 解析失败 + 无法提取 + 读取失败
ignored = ignored.size()
// 恒等式（单测断言）：total + skip + ignored == 提取总数；high+medium+low ≤ total
```

***

## 14. util 包

| 类              | 职责                                                          |
| -------------- | ----------------------------------------------------------- |
| `LineRanges`   | `Set<Integer>`（JDK 升序）行集合运算：`overlaps(start,end,lines)`、`isAllWithin(span,lines)`、`anchorWithin(span,hunk)` |
| `TextUtils`    | 空白归一化、大小写折叠、截断（120/200 字符）、驼峰→下划线转换                        |
| `IoUtils`      | 目录创建、UTF-8 覆盖写、文件编码探测（XML 声明）                               |
| `JavaSourceIndex` | 全仓 `CompilationUnit` 缓存与 `(package, 简名)` 索引（供常量/Provider/实体解析） |
| `Diagnostics`  | 诊断/警告/进度输出统一出口（**唯一**调用 `System.err` 的生产类，Sonar S106 集中豁免点；CLI 契约与 slf4j-nop 决策见 `docs/sonar-exceptions.md`） |

***

## 15. 异常体系与错误码

```
SqlRiskException (abstract, extends RuntimeException, int exitCode, String message)
├── CliUsageException          2   // 参数缺失/非法
├── ConfigException            2   // 配置文件缺失（显式指定）/语法错误/校验失败（含方言未支持、datasource 配置非法）
├── MetadataException          2   // 元数据加载失败：连接失败/权限不足/查询失败/超时（v1.8/D24；原文件缺失语义已移除）
├── DiffException              2   // 非 git 仓库/非法 commit/仓库不可读
└── ReportException            2   // 报告目录/文件写入失败
```

| 触发点                          | 行为                                                    |
| ---------------------------- | ----------------------------------------------------- |
| 单语句解析失败                      | 不抛异常：`SkippedItem(PARSE_FAILED)`，reason 前缀"解析失败："    |
| 单文件读取失败 / 单提取器失败             | 不抛异常：`SkippedItem(READ_ERROR)` / `UNSUPPORTED_CARRIER` |
| 单规则内部异常                      | 捕获 + stderr 警告 + 跳过该规则（防御式）                            |
| 顶层未捕获 Throwable              | stderr（`--verbose` 打堆栈）→ exit 2                        |
| 退出码：0 无高危；1 `summary.high>0`；2 上述受控错误与未知错误 | —                            |

***

## 16. 并发与确定性设计

| 关注点     | 方案                                                                         |
| ------- | -------------------------------------------------------------------------- |
| 并行单元    | P3：文件级；P5~P7：语句级（骨架惰性）                                                     |
| 共享对象    | `XmlFragmentIndex/EntityIndex/JavaSourceIndex/AppConfig/MetadataProvider` 构建后只读不可变 |
| 线程封闭    | JavaParser TypeSolver 每线程独立；SAX/Locator 实例每任务独立                            |
| 确定性     | 所有输出集合在进入 P8 前按 `(file, line, statementId)` 排序；Clock 注入；无随机数               |
| 报告可比对性  | 固定 Clock 下同一输入产生逐字节一致的 JSON                                               |

***

## 17. 常量与阈值

| 常量                       | 值      | 说明                           |
| ------------------------ | ------ | ---------------------------- |
| `MAX_SKELETONS`          | 8      | 单语句骨架枚举上限（choose 组合爆炸保护）     |
| `INCLUDE_DEPTH_LIMIT`    | 32     | include 展开深度上限（超出 → parseFailed） |
| `MAX_FILE_SIZE`          | 5 MB   | 单文件超限 → `SkippedItem(READ_ERROR, "文件过大")` |
| `POOL_SIZE`              | min(CPU, 8) | 并行度                    |
| `SNIPPET_LIMIT`          | 120 字符 | Finding.snippet 截断            |
| `CONSOLE_SQL_LIMIT`      | 200 字符 | 控制台 SQL 展示截断                 |
| `REPORT_FILE_NAME`       | `sql_risk_report.json` | JSON 报告文件名（机器契约）           |
| `REPORT_FILE_NAME_HTML`  | `sql_risk_report.html` | HTML 报告文件名（阅读层，默认双产出）  |
| `DB_CONNECTION_TIMEOUT_SECONDS` | 10 秒 | `datasource.connection_timeout_seconds` 缺省值（v1.8） |
| 规则默认优先级                  | M8=-100；H1..H3=10..12；M1..M6=20..25；L1/L2/L4=30..32 | §11.4 |

***

## 18. 测试设计

### 18.1 测试类清单（与生产类 1:1 或 1:N）

| 包    | 测试类                                                                                                                |
| ---- | ----------------------------------------------------------------------------------------------------------------- |
| cli  | `CliArgsTest`                                                                                                     |
| core | `AnalysisEngineIT`（端到端黄金样例）、`WhitelistFilterTest`、`SummaryCalculatorTest`                                             |
| config | `ConfigLoaderTest`、`ConfigValidatorTest`                                                                           |
| metadata | `JdbcMetadataProviderTest`（H2 内存库：表/列类型/索引加载、一次加载与连接关闭、连接失败/超时错误语义）（v1.8）        |
| diff | `GitRepoTest`、`DiffServiceTest`、`CommitTreeWalkerTest`、`LineRangesTest`                                             |
| extractor | `MapperXmlExtractorTest`、`XmlFragmentIndexTest`、`FragmentPropagatorTest`、`AnnotationSqlExtractorTest`、`ProviderMethodResolverTest`、`LambdaSqlExtractorTest`、`EntityIndexTest` |
| parser | `DynamicEvaluatorTest`、`LambdaSkeletonRendererTest`、`PlaceholderNormalizerTest`、`QueryFactsBuilderTest`、`DruidSqlParserTest`、`SqlDialectsTest`（注册/取值/未支持报错/分页识别，v1.8） |
| rule | `H1RuleTest`…`L4RuleTest`（13 个）、`RuleEngineTest`（M8 短路/多骨架首命中即停/禁用规则）、`RuleRegistryTest`（13 内置/执行顺序/级别覆盖/禁用）                              |
| report | `JsonReporterTest`（黄金 JSON）、`ConsoleReporterTest`                                                                |

### 18.2 夹具与黄金样例

- `FixtureRepoBuilder`（test util）：JGit 程序化构造临时仓库——按 `files.txt` 清单写文件 → 提交为 prod → 再按 `changes.txt` 修改 → 提交为 head；产出 (repoPath, prodSha, headSha)。
  **确定性（M9）**：作者/提交者固定为 `fixture/fixture@test` 且提交时间固定为测试时钟（2026-10-14T08:00+08:00），提交 SHA 完全由内容决定 → `expected/report.json` 可直接比对真实 head/prod SHA；注意 JGit `PersonIdent(PersonIdent)` 拷贝构造器会重置时间为当前值，必须复用固定实例。
- 黄金 IT 类为 `AnalysisEngineIT`（surefire 显式收录 `**/*IT.java`）；管线执行"**用例 `schema.sql` 执行到 H2 内存库 → JdbcMetadataProvider 加载** → ConfigLoader → RunContext(固定 Clock) → AnalysisEngine → JsonReporter"，并对每个用例强断言 §13.4 口径恒等式。regen 模式（需要更新 expected 时）：临时以真实管线重写 `expected/report.json` 后人工复核，复核通过即删除该临时类（不随库交付）。
  **v1.8 变更**：元数据来源由"落盘 JSON → 加载"改为"H2 建库 → JDBC 加载"（D24）；H2 为 test scope 依赖（§3.5），首次引入需在线校验依赖后离线可用。
- `GoldenCaseRunner`：以固定 `Clock.fixed` 执行完整管线（不经 main），JSON 归一化（去空白）后与 `expected/report.json` 全量比对；断言退出码由 `ExitCode` 推导函数给出。
- 用例目录结构：

```
src/test/resources/cases/<caseId>/
├── repo/            # 初始提交内容（相对路径 → 文件）
├── changes/         # 第二次提交的改动（覆盖/新增/删除标记）
├── config.yaml      # 可选，缺省用内置
├── schema.sql       # 可选：H2 兼容 DDL，测试启动时执行到内存库（v1.8 替代 metadata.json）
├── args             # 手工场景的参数（供 IT 直跑调试）
└── expected/report.json
```

### 18.3 关键用例明细（AC → case）

| caseId                       | 覆盖点（AC）                                                    |
| ---------------------------- | ---------------------------------------------------------- |
| `ac01-diff-added-modified`   | DIFF 仅输出新增/修改语句，删除不出现（AC-1）；断言 diff 行命中即整句提取               |
| `ac02-full-mode`             | FULL 模式全量提取（AC-2）；断言数据源为提交树——工作区存在未提交改动时结果不变（D22）          |
| `ac03-high-rules`            | UPDATE/DELETE 无 WHERE → H1/H2（AC-3）；`<if>` 唯一条件折叠后 WHERE 消失 → H1（AC-4） |
| `ac04-choose-skeletons`      | `<choose>` 双骨架、任一命中即停（AC-4）                                 |
| `ac05-xml-dynamic-tags`      | if/choose/foreach/where/trim/set/include 样例正确提取分析（AC-5）      |
| `ac05b-fragment-propagation` | `<sql>` 片段改动 → 全部（含跨文件、传递）引用语句 MODIFIED + `changedVia`（D20） |
| `ac06-annotation-lambda`     | 注解（含 script/Provider 失败跳过）与 Lambda（全链方法、嵌套组、布尔重载丢弃）（AC-6）     |
| `ac07-whitelist`             | 精确/前缀命中 → ignored；Lambda 不参与白名单（AC-7）                       |
| `ac08-report-shape`          | 报告字段齐全、口径恒等式成立（AC-8）                                       |
| `ac09-error-codes`           | 非 git 仓库/非法 commit/坏配置/**方言未支持**/**数据库连接失败**（AC-9，v1.8 调整元数据错误形态）      |
| `ac11-dollar-shortcircuit`   | `${}` 出现在被丢弃分支内 → 仍短路定级 MEDIUM，无其他 findings（AC-11）           |
| `skip-parse-failed`          | 解析失败/无法提取 → skipped[]（kind=PARSE_FAILED/UNSUPPORTED_CARRIER），不影响退出码 |
| `m1-metadata-semantics`      | 表不在元数据 → 无索引；类型缺失 → M4 不判定                                  |
| `ac12-annotation-wrapper`    | 注解 `${ew.customSqlSegment}` + `@Param("ew") Wrapper` 参数：仅 SQL 变更 → 不命中 M8（代换解析、uncertain）；仅参数行变更致绑定失配 → 恢复 M8（v1.4）；代换后仍不可解析（固定条件与 `${ew.sqlSegment}` 混排）→ 空骨架归 SKIP（v1.6） |
| `ac13-lambda-branches`       | Lambda 控制流分支：if/else 两骨架（首命中即停）；switch default 路径骨架命中 H1（skeleton=1，含分支保守附注）；分支体内行变更照常提取（v1.5） |

> v1.8：全部用例的元数据改为用例内 `schema.sql` 经 H2 建库后由 `JdbcMetadataProvider` 加载（`m1-metadata-semantics` 的语义断言不变）；`ac09` 新增"连接失败（非法 url）→ exit 2"与"方言未支持 → exit 2"两个错误形态。

### 18.4 单测边界清单（每规则）

每规则测试须含：≥1 正例（命中）、≥1 反例（不命中）、≥1 边界（如 M3 非字面量不判定、M4 类型缺失不判定、M6 纯变量分支、M1 无谓词列不判定）。

***

## 19. 详细设计决策（DD 清单）

| 编号   | 决策                                                                                          | 涉及章节           |
| ---- | ------------------------------------------------------------------------------------------- | -------------- |
| DD-1 | 取消 `StatementCategory` 独立枚举，归类由 `level` + skipped 表达                                       | §4.1           |
| DD-2 | CLI 手写解析（不引入 picocli 等框架），参数保持精简集合（v1.8 含 `--dialect/--db-url/--db-username/--db-password`）                                                        | §5             |
| DD-3 | XML 原文切片依赖 SAX `Locator` 的 endElement 行号语义（JDK Xerces），编入单测                                   | §9.2           |
| DD-4 | `<choose>` 骨架**惰性枚举**，`MAX_SKELETONS=8` 上限，超限截断并标 `truncated/uncertain`（需求方已确认）                | §10.2          |
| DD-5 | 解析失败在 skipped[] 中以 `kind=PARSE_FAILED` + reason 前缀"解析失败："区分                                   | §4.2、§10.6     |
| DD-6 | 规则优先级数值表（M8=-100 先行；H/M/L 分组）                                                              | §11.4、§17      |
| DD-7 | Lambda 语句范围包含 wrapper 变量的执行调用行（D20 落地）                                                      | §9.7           |
| DD-8 | 规则未收录的链式方法 → 忽略条件 + `hasUnknownFragment` + uncertain（不猜测语义）（需求方已确认）                          | §10.3          |
| DD-9 | 片段与直接变更同时命中 → 直接变更优先，`changedVia=null`（需求方已确认）                                                 | §9.4           |
| DD-10 | 多表谓词列归属歧义 → 归 `UNRESOLVED`，不参与 M1/M6 判定（需求方已确认）                                              | §10.5          |
| DD-11 | 单文件 5MB / include 深度 32 防护阈值                                                                 | §17            |
| DD-12 | 报告 DTO 与模型 1:1、`serializeNulls`、字段顺序固定                                                        | §12.3          |
| DD-13 | 元数据 **JDBC 一次快照**：P1 单次连接加载 → 关闭 → 只读内存（不重复连接/加载）；失败即 exit 2（v1.8/D24）                           | §7、§13.2       |
| DD-14 | **方言扩展流程**：实现 `SqlDialect` → `SqlDialects` 注册 → 分页识别用例 → 放开配置值；未支持值报错不降级（v1.8/D25）                     | §10.7          |
| DD-15 | JDBC 驱动**直接在 pom 中引用**（MySQL Connector/J，compile → 随 shaded jar；无外置驱动目录）；测试元数据用 H2 内存库（test scope）（v1.8/D27，2026-10-15 确认）        | §7、§18.2       |

> DD-4、DD-8、DD-9、DD-10 已由需求方确认（2026-10-14）；DD-13/DD-14 随 2026-10-15 扩展需求确认，DD-15 待复核；其余 DD 项为实现级细化，随编码落地，如与需求方预期不符走评审调整。

***

## 20. 追溯矩阵（详细设计 ↔ 概要设计）

| 概要设计                | 详细设计                  |
| ------------------- | --------------------- |
| §3 架构 / §3.2 模块职责   | §3、§4~§14             |
| §4.1 阶段管线 P0~P9     | §13.2                 |
| §4.3 两模式 / D21/D22  | §8.2、§8.3、§13.1        |
| §5.2 配置校验           | §6                    |
| §5.3 元数据             | §7                    |
| §5.4 diff            | §8                    |
| §5.5 extractor / D20 | §9（含 XmlFragmentIndex）  |
| §5.6 parser          | §10                   |
| §5.7 rule 与内置规则     | §11                   |
| §6 数据模型             | §4、§10.6               |
| §7 SPI              | §8.4、§9.1、§11.1        |
| §8.1 差异→语句映射        | §9.4                  |
| §8.2 保守求值           | §10.2                 |
| §8.3 Lambda 还原       | §10.3                 |
| §8.4 白名单            | §13.3                 |
| §8.5 M8 短路          | §11.4 M8              |
| §9.1 CLI / 退出码       | §5                    |
| §9.4 报告与口径          | §12、§13.4、附录 A        |
| §10 异常与错误码          | §15                   |
| §11 非功能             | §16、§17               |
| §12 测试              | §18                   |
| §14 D1~D23          | §19 DD 清单（补充细化）       |
| §14 D24~D27（v1.8）   | §7、§10.7、§6、§5（DD-13~DD-15） |
| FR-8 数据库元数据（需求 v1.1） | §7、§13.2、§15、§18.2  |
| FR-9 多方言（需求 v1.1）    | §10.4、§10.5、§10.7、§6、§5 |

***

## 附录 A：JSON 报告完整示例

```json
{
  "generatedAt": "2026-10-14T11:05:30+08:00",
  "mode": "DIFF",
  "headCommit": "abc1234def5678",
  "prodCommit": "def4567abc1234",
  "summary": { "total": 12, "high": 1, "medium": 2, "low": 1, "skip": 1, "ignored": 1 },
  "results": [
    {
      "statementId": "com.demo.mapper.OrderMapper.updateByUser",
      "source": "MAPPER_XML",
      "file": "src/main/resources/mapper/OrderMapper.xml",
      "line": 42,
      "changeType": "MODIFIED",
      "sql": "UPDATE t_order SET status=?",
      "findings": [
        { "ruleId": "H1", "level": "HIGH", "message": "UPDATE 语句缺少 WHERE，将全表更新，高危（WHERE 条件位于动态分支内，按保守策略视为不成立）", "snippet": "UPDATE t_order SET status=?", "skeleton": 0 }
      ],
      "level": "HIGH",
      "uncertain": true,
      "changedVia": null
    },
    {
      "statementId": "OrderServiceImpl.java:86",
      "source": "LAMBDA",
      "file": "src/main/java/com/demo/service/impl/OrderServiceImpl.java",
      "line": 86,
      "changeType": "MODIFIED",
      "sql": "SELECT * FROM t_order",
      "findings": [
        { "ruleId": "H3", "level": "HIGH", "message": "SELECT 无有效过滤条件（无 WHERE 且无 LIMIT），存在全表扫描风险", "snippet": "SELECT * FROM t_order", "skeleton": 0 }
      ],
      "level": "HIGH",
      "uncertain": false,
      "changedVia": null
    }
  ],
  "ignored": [
    { "statementId": "com.demo.mapper.OrderMapper.selectById", "file": "src/main/resources/mapper/OrderMapper.xml", "line": 12, "reason": "whitelist" }
  ],
  "skipped": [
    { "statementId": "com.demo.mapper.OrderMapper.dynamicSearch", "file": "src/main/resources/mapper/OrderMapper.xml", "line": 77, "kind": "PARSE_FAILED", "reason": "解析失败：含无法解析的 include refid：com.demo.mapper.OtherMapper.unknownFragment" }
  ]
}
```

## 附录 B：CLI --help 文本

```
SQL 上线风险静态检查工具 v0.1
用法: java -jar sql-risk-checker-shaded.jar --repo <path> [options]

  --repo <path>        目标 Git 仓库绝对路径（必填）
  --head-commit <sha>  当前分支最新 commit；缺省取 HEAD。仅分析已提交内容
  --prod-commit <sha>  生产 commit id；不传则对 head 提交树做全量分析
  --config <path>      规则/白名单/数据库连接配置文件（yaml）；缺省用内置默认配置
  --dialect <name>     SQL 方言；缺省 mysql（oracle/postgresql 为预留值，未实现报错）
  --db-url <url>       数据库 JDBC URL（元数据来源；未传读 yaml datasource.url）
  --db-username <u>    数据库账号（未传读 yaml）
  --db-password <pwd>  数据库密码（优先级：本参数 > 环境变量 SQL_RISK_DB_PASSWORD > yaml）
  --output <path>      报告输出目录；缺省当前目录
  --format json        显式声明结构化输出（缺省即控制台 + JSON 双输出）
  --verbose            输出详细过程日志与中低危明细（stderr）
  --help               显示本帮助

退出码: 0 无高危 | 1 存在高危 | 2 参数或环境错误
```

***

*文档完。v1.1（2026-10-14）：DD-4/DD-8/DD-9/DD-10 经需求方确认；其余 DD 项为实现级细化。基于需求文档 v1.0 与概要设计 v1.7 编制；如与概要设计冲突以本文为准并回写概要设计。*

*v1.2（2026-10-14，M9 收尾回写）：① D19 role 推断补充裸 `<if>WHERE …</if>` 形态（§9.5 动态求值——H1/H2/H3 保守附注修复，ac03 暴露）；② changeType 判定细则与 changedVia 格式明确（§9.4）；③ `Set<Integer>` 行集合、ConfigLoader 实际签名、SqlSourceExtractor 实现形态回写（§8.3/§6/§9.1）；④ §12.3 `sql` 字段三态语义（命中骨架/骨架#0/M8 原文）；⑤ §18.2 夹具确定性（固定提交身份与时间）与黄金 IT 执行约定。*

*v1.3（2026-10-14，交付后增补，需求方确认）：① 新增 §12.5 `HtmlReporter`——`--output` 目录默认双产出 `sql_risk_report.json` + `sql_risk_report.html`（JSON 仍为机器契约，HTML 为阅读层，含级别/规则/文件/关键字筛选与折叠）；② §12.2 控制台"报告已生成"两行列出；③ §17 增 `REPORT_FILE_NAME_HTML`；④ §5 入口调试便利（argv 为空 → `DEBUG_DEFAULT_ARGV`）。*

*v1.4（2026-10-15，需求方驱动）：① §9.5 增补「Wrapper SQL 段识别」——注解 `${ew.customSqlSegment}` 等占位符在 `@Param`/参数名解析到 Wrapper 家族参数时不再误报 M8，绑定失配仍命中；② §10.6 增补占位符代换规则与 `uncertain` 语义；③ §11.4 M8 判定增加排除项；④ §18.3 增 `ac12-annotation-wrapper` 用例。*

*v1.5（2026-10-15，需求方驱动）：① §9.7 增补「控制流分支识别」——wrapper 变量在 if/else、switch/case 中添加条件时记录分支坐标（M15）；② §10.3 增补分支枚举渲染（每分支一骨架，对齐 `<choose>`；无 else 的 if / 循环体按 `<if>` 保守丢弃；组合超 MAX_SKELETONS 截断）；③ §18.3 增 `ac13-lambda-branches` 用例。*

*v1.6（2026-10-15，需求方驱动）：§11.2 增「空骨架归口」——解析产物为空（含 `${}` 解析失败的短路候选）且语句级短路未命中时归 SKIP（PARSE_FAILED），不再作为 `level=null` 结果输出；短路命中语义不变（AC-11）。§10.6/§18.3 同步。*

*v1.7（2026-10-15，需求方驱动）：§12.5 HTML 报告文案与统计——「未命中风险规则/无命中」更名为「**通过**」（分区标题、卡片徽标、恒等式行口径），头部统计卡片新增「通过」数量；JSON 报告契约不变。*

*v1.8（2026-10-15，需求方扩展驱动，FR-8/FR-9）：① §7 重写——元数据改为 `JdbcMetadataProvider` **JDBC 一次加载**（连接即关、失败 exit 2），文件通道（`JsonMetadataProvider`/`--metadata`/`metadata_file`）移除（D24/DD-13）；② §10.4/§10.5/新增 §10.7——`SqlDialect` 方言 SPI（MySQL 实现 + Oracle/PG 预留要点 + 未支持值报错，D25/DD-14）；③ §6 增 `DataSourceSetting` 与连接三通道/密码优先级（D26）；④ §7/§18.2 JDBC 驱动**pom 内嵌**（MySQL Connector/J）与 H2 测试载体（D27/DD-15，2026-10-15 确认）；⑤ §5/§13/§15/§17/§18/附录 B 同步。*

*v1.8 实现回写（2026-10-15，M10/M11 落地）：① §7 类型跨库别名归一（CHARACTER VARYING/CHAR VARYING→VARCHAR、CHARACTER→CHAR、INTEGER→INT，H2 实测）；② §6 ConfigLoader 增环境变量注入重载（测试用，密码优先级语义不变）；③ §10.7 方言经 `ParseContext` 装配（3 参构造缺省 MySQL，引擎注入配置值）；④ 概要设计 §3.3 依赖图新增 `config → parser` 边（方言取值校验经 `SqlDialects.supportedNames()`，无环）。*

*v1.9（2026-10-14，Sonar 规范治理，需求方驱动）：① §14 新增 `Diagnostics`——诊断输出统一出口，生产代码 `System.err` 用法集中至此一处（S106 设计性豁免登记化）；② §11.1 `RuleContext.facts()` 契约收紧——语句级上下文（parsedSql=null）调用即抛 `IllegalStateException`（fail fast，原返回 null 的误用隐患消除；引擎 `safeCheck` 兜底不变，S2259 类问题根治）；③ §6 `ConfigLoader` 移除未使用的 `repoPath` 保留参数（S1172）；④ 全量代码按 Sonar 规范重构（复杂度拆分、重复字面量常量化、循环→Stream、断言规范等），设计性例外集中登记于 `docs/sonar-exceptions.md`（S106/S107/S1181）；⑤ §18.3 `ac06` 夹具按 S7158 调整（`String.isEmpty()`）并更新 expected 的 head SHA（语义字段零漂移）。*
