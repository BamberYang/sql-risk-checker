package com.demo.sqlrisk.cli;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令行参数（详细设计 §5、FR-7 v1.8）：手写解析（DD-2），不引入 CLI 框架。
 *
 * <p>校验矩阵：缺 `--repo` / 未知参数 / 重复参数 / 参数缺值 / `--format` 非 json /
 * 已移除参数 `--metadata` → {@link CliUsageException}（exit 2）；
 * `--help` 优先短路并返回（由入口打印用法后 exit 0）。</p>
 */
public final class CliArgs {

    private static final List<String> VALUE_OPTIONS = Arrays.asList(
            "--repo", "--head-commit", "--prod-commit", "--config", "--output", "--format",
            "--dialect", "--db-url", "--db-username", "--db-password");

    /** v1.8 已移除参数（出现时给出明确指引，而非泛化"未知参数"）。 */
    private static final List<String> REMOVED_OPTIONS = Arrays.asList("--metadata");

    private String repoPath;      // 必填
    private String headCommit;    // nullable
    private String prodCommit;    // nullable
    private String configPath;    // nullable
    private String dialect;       // nullable（缺省 mysql）
    private String dbUrl;         // nullable（覆盖 yaml datasource.url）
    private String dbUsername;    // nullable
    private String dbPassword;    // nullable（优先级 CLI > 环境变量 > yaml）
    private String outputDir = ".";   // 缺省当前目录
    private boolean verbose;
    private boolean helpRequested;

    private CliArgs() {
    }

    public static CliArgs parse(String[] argv) {
        CliArgs args = new CliArgs();
        Set<String> seen = new HashSet<String>();
        int i = 0;
        while (i < argv.length) {
            String token = argv[i];
            if ("--help".equals(token)) {
                args.helpRequested = true;
                return args;
            }
            if (seen.contains(token)) {
                throw new CliUsageException("重复参数: " + token);
            }
            seen.add(token);
            if ("--verbose".equals(token)) {
                args.verbose = true;
                i++;
                continue;
            }
            if (REMOVED_OPTIONS.contains(token)) {
                throw new CliUsageException("参数 " + token + " 已移除：元数据改为从数据库加载"
                        + "（用 --db-url/--db-username/--db-password 配置连接，见 FR-8）");
            }
            if (!VALUE_OPTIONS.contains(token)) {
                throw new CliUsageException("未知参数: " + token);
            }
            if (i + 1 >= argv.length) {
                throw new CliUsageException("参数缺少值: " + token);
            }
            applyValue(args, token, argv[i + 1]);
            i += 2;
        }
        if (args.repoPath == null || args.repoPath.trim().isEmpty()) {
            throw new CliUsageException("缺少必填参数 --repo <path>");
        }
        return args;
    }

    /** 取值参数落位（token 必属 {@link #VALUE_OPTIONS}）。 */
    private static void applyValue(CliArgs args, String token, String value) {
        if ("--repo".equals(token)) {
            args.repoPath = value;
        } else if ("--head-commit".equals(token)) {
            args.headCommit = value;
        } else if ("--prod-commit".equals(token)) {
            args.prodCommit = value;
        } else if ("--config".equals(token)) {
            args.configPath = value;
        } else if ("--output".equals(token)) {
            args.outputDir = value;
        } else if ("--dialect".equals(token)) {
            args.dialect = value;
        } else if ("--db-url".equals(token)) {
            args.dbUrl = value;
        } else if ("--db-username".equals(token)) {
            args.dbUsername = value;
        } else if ("--db-password".equals(token)) {
            args.dbPassword = value;
        } else if ("--format".equals(token) && !"json".equals(value)) {
            throw new CliUsageException("--format 仅支持 json，收到: " + value);
        }
    }

    /** 用法文本（详细设计 附录 B，v1.8）。 */
    public static String usage() {
        return "SQL 上线风险静态检查工具 v0.1\n"
                + "用法: java -jar sql-risk-checker-shaded.jar --repo <path> [options]\n"
                + "\n"
                + "  --repo <path>        目标 Git 仓库绝对路径（必填）\n"
                + "  --head-commit <sha>  当前分支最新 commit；缺省取 HEAD。仅分析已提交内容\n"
                + "  --prod-commit <sha>  生产 commit id；不传则对 head 提交树做全量分析\n"
                + "  --config <path>      规则/白名单/数据库连接配置文件（yaml）；缺省用内置默认配置\n"
                + "  --dialect <name>     SQL 方言；缺省 mysql（oracle/postgresql 为预留值，未实现报错）\n"
                + "  --db-url <url>       数据库 JDBC URL（元数据来源；未传读 yaml datasource.url）\n"
                + "  --db-username <u>    数据库账号（未传读 yaml）\n"
                + "  --db-password <pwd>  数据库密码（优先级：本参数 > 环境变量 SQL_RISK_DB_PASSWORD > yaml）\n"
                + "  --output <path>      报告输出目录；缺省当前目录\n"
                + "  --format json        显式声明结构化输出（缺省即控制台 + JSON 双输出）\n"
                + "  --verbose            输出详细过程日志与中低危明细（stderr）\n"
                + "  --help               显示本帮助\n"
                + "\n"
                + "退出码: 0 无高危 | 1 存在高危 | 2 参数或环境错误";
    }

    public String repoPath() {
        return repoPath;
    }

    /** 可为 null：缺省取 HEAD。 */
    public String headCommit() {
        return headCommit;
    }

    /** 可为 null：不传则为 FULL 模式。 */
    public String prodCommit() {
        return prodCommit;
    }

    /** 可为 null：缺省用内置默认配置。 */
    public String configPath() {
        return configPath;
    }

    /** 可为 null：缺省 mysql。 */
    public String dialect() {
        return dialect;
    }

    /** 可为 null：未传读 yaml datasource.url。 */
    public String dbUrl() {
        return dbUrl;
    }

    /** 可为 null：未传读 yaml。 */
    public String dbUsername() {
        return dbUsername;
    }

    /** 可为 null：优先级 CLI > 环境变量 SQL_RISK_DB_PASSWORD > yaml。 */
    public String dbPassword() {
        return dbPassword;
    }

    public String outputDir() {
        return outputDir;
    }

    public boolean verbose() {
        return verbose;
    }

    public boolean helpRequested() {
        return helpRequested;
    }
}
