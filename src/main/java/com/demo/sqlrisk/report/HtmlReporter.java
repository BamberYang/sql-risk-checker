package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.Summary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTML 报告（详细设计 §12.5，默认双产出）：单文件、离线自包含（内联 CSS + 原生 JS，无外链），
 * 供人工阅读——高危默认展开，中/低危/通过/SKIP/已忽略折叠；级别/规则/文件/关键字四维筛选；
 * 全部动态文本 HTML 转义；同一 {@link ReportModel} 输出逐字节一致（不参与黄金样例比对）。
 */
public final class HtmlReporter {

    public static final String REPORT_FILE_NAME = "sql_risk_report.html"; // §17
    private static final String SPAN_END = "</span>";

    private HtmlReporter() {
    }

    public static String toHtml(ReportModel report) {
        StringBuilder sb = new StringBuilder(32768);
        String title = "SQL 风险检查报告 — " + report.mode() + " " + abbreviate(report.headCommit());
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(title)).append("</title>\n")
                .append(STYLE).append("</head>\n<body>\n");
        appendHeader(sb, report);
        appendSummary(sb, report);
        appendFilterBar(sb, report);
        appendResultSections(sb, report);
        appendExtraSections(sb, report);
        sb.append("<footer>报告由 sql-risk-checker 生成 · 机器可读契约：<code>sql_risk_report.json</code></footer>\n")
                .append(SCRIPT).append("</body>\n</html>\n");
        return sb.toString();
    }

    /** @return HTML 文件绝对路径；目录不存在则创建、同名覆盖、失败 → {@link ReportException}（exit 2）。 */
    public static Path write(ReportModel report, String outputDir) {
        Path file = Paths.get(outputDir).resolve(REPORT_FILE_NAME);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, toHtml(report).getBytes(StandardCharsets.UTF_8));
            return file.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new ReportException("HTML 报告写出失败: " + file + "（" + e.getMessage() + "）");
        }
    }

    // ---------- 区块渲染 ----------

    private static void appendHeader(StringBuilder sb, ReportModel report) {
        sb.append("<header>\n<h1>SQL 上线风险静态检查报告</h1>\n<div class=\"meta\">")
                .append("<span class=\"kv\">模式 <b>").append(escape(report.mode().name())).append("</b></span>")
                .append("<span class=\"kv\">head <code>").append(escape(abbreviate(report.headCommit()))).append("</code></span>")
                .append(report.prodCommit() != null
                        ? "<span class=\"kv\">prod <code>" + escape(abbreviate(report.prodCommit())) + "</code></span>"
                        : "<span class=\"kv\">prod —</span>")
                .append("<span class=\"kv\">生成时间 ").append(escape(
                        report.generatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))).append(SPAN_END)
                .append("</div>\n</header>\n");
    }

    private static void appendSummary(StringBuilder sb, ReportModel report) {
        Summary s = report.summary();
        int passed = s.total() - s.high() - s.medium() - s.low();
        sb.append("<section class=\"summary\">\n")
                .append(stat("high", "高危", s.high()))
                .append(stat("medium", "中危", s.medium()))
                .append(stat("low", "低危", s.low()))
                .append(stat("passed", "通过", passed))
                .append(stat("skip", "SKIP", s.skip()))
                .append(stat("ignored", "已忽略", s.ignored()))
                .append("<div class=\"identity\">参与分析 ").append(s.total())
                .append(" = 高 ").append(s.high()).append(" + 中 ").append(s.medium())
                .append(" + 低 ").append(s.low()).append(" + 通过 ").append(passed)
                .append("；提取总数 ").append(s.total() + s.skip() + s.ignored())
                .append(" = 参与分析 ").append(s.total())
                .append(" + SKIP ").append(s.skip())
                .append(" + 已忽略 ").append(s.ignored()).append("</div>\n</section>\n");
    }

    private static String stat(String cls, String label, int count) {
        return "<div class=\"stat " + cls + "\"><span class=\"num\">" + count + "</span><span class=\"lbl\">"
                + label + "</span></div>";
    }

    private static void appendFilterBar(StringBuilder sb, ReportModel report) {
        Set<String> rules = new LinkedHashSet<String>();
        Set<String> files = new LinkedHashSet<String>();
        for (StatementResult result : report.results()) {
            files.add(result.meta().file());
            for (Finding finding : result.findings()) {
                rules.add(finding.ruleId());
            }
        }
        List<String> sortedRules = new ArrayList<String>(rules);
        Collections.sort(sortedRules);
        List<String> sortedFiles = new ArrayList<String>(files);
        Collections.sort(sortedFiles);

        sb.append("<section class=\"filters\">\n<span class=\"f-label\">级别</span>\n")
                .append("<button type=\"button\" class=\"f-level active\" data-level=\"ALL\">全部</button>")
                .append("<button type=\"button\" class=\"f-level\" data-level=\"HIGH\">高危</button>")
                .append("<button type=\"button\" class=\"f-level\" data-level=\"MEDIUM\">中危</button>")
                .append("<button type=\"button\" class=\"f-level\" data-level=\"LOW\">低危</button>")
                .append("\n<span class=\"f-label\">规则</span><select id=\"f-rule\"><option value=\"ALL\">全部规则</option>");
        for (String rule : sortedRules) {
            sb.append("<option value=\"").append(escape(rule)).append("\">").append(escape(rule)).append("</option>");
        }
        sb.append("</select>\n<span class=\"f-label\">文件</span><select id=\"f-file\"><option value=\"ALL\">全部文件</option>");
        for (String file : sortedFiles) {
            sb.append("<option value=\"").append(escape(file)).append("\">").append(escape(file)).append("</option>");
        }
        sb.append("</select>\n<span class=\"f-label\">搜索</span>")
                .append("<input id=\"f-q\" type=\"search\" placeholder=\"statementId / SQL / 消息关键字\">")
                .append("<span class=\"f-count\">可见 <b id=\"f-count\">").append(report.results().size())
                .append("</b> 条</span>\n</section>\n");
    }

    private static void appendResultSections(StringBuilder sb, ReportModel report) {
        appendCards(sb, "HIGH", "高危明细", true, filterLevel(report, RiskLevel.HIGH));
        appendCards(sb, "MEDIUM", "中危明细", false, filterLevel(report, RiskLevel.MEDIUM));
        appendCards(sb, "LOW", "低危明细", false, filterLevel(report, RiskLevel.LOW));
        appendCards(sb, "NONE", "通过", false, filterLevel(report, null));
    }

    private static List<StatementResult> filterLevel(ReportModel report, final RiskLevel level) {
        return report.results().stream()
                .filter(result -> result.level() == level)
                .collect(Collectors.toList());
    }

    private static void appendCards(StringBuilder sb, String dataLevel, String title, boolean open,
                                    List<StatementResult> results) {
        sb.append("<details class=\"level-sec\" data-level=\"").append(dataLevel).append('"')
                .append(open ? " open" : "").append(">\n<summary>").append(title)
                .append("（").append(results.size()).append("）</summary>\n");
        for (StatementResult result : results) {
            appendCard(sb, result);
        }
        sb.append("</details>\n");
    }

    private static void appendCard(StringBuilder sb, StatementResult result) {
        RiskLevel level = result.level();
        StringBuilder rules = new StringBuilder();
        for (Finding finding : result.findings()) {
            if (rules.length() > 0) {
                rules.append(' ');
            }
            rules.append(finding.ruleId());
        }
        sb.append("<article class=\"card\" data-level=\"").append(level == null ? "NONE" : level.name())
                .append("\" data-rules=\"").append(escape(rules.toString()))
                .append("\" data-file=\"").append(escape(result.meta().file())).append("\">\n")
                .append("<div class=\"card-head\">")
                .append("<span class=\"badge b-").append(level == null ? "NONE" : level.name()).append("\">")
                .append(levelLabel(level)).append(SPAN_END)
                .append("<span class=\"sid\">").append(escape(result.meta().statementId())).append(SPAN_END)
                .append("<span class=\"tag\">").append(escape(result.meta().source().name())).append(SPAN_END)
                .append("<span class=\"tag\">").append(escape(result.meta().changeType().name())).append(SPAN_END);
        if (result.meta().changedVia() != null) {
            sb.append("<span class=\"tag via\">via ").append(escape(result.meta().changedVia())).append(SPAN_END);
        }
        if (result.uncertain()) {
            sb.append("<span class=\"tag warn\">还原不确定</span>");
        }
        sb.append("</div>\n<div class=\"loc\">").append(escape(result.meta().file()))
                .append(':').append(result.meta().line()).append("</div>\n")
                .append("<pre class=\"sql\">").append(escape(result.sql())).append("</pre>\n");
        if (result.findings().isEmpty()) {
            sb.append("<p class=\"nofinding\">未命中任何风险规则，通过</p>\n");
        } else {
            sb.append("<ul class=\"findings\">\n");
            for (Finding finding : result.findings()) {
                sb.append("<li><span class=\"badge b-").append(finding.level().name()).append("\">")
                        .append(escape(finding.ruleId())).append("</span> ")
                        .append(escape(finding.message()))
                        .append(" <span class=\"meta\">骨架 #").append(finding.skeletonIndex()).append("</span></li>\n");
            }
            sb.append("</ul>\n");
        }
        sb.append("</article>\n");
    }

    private static void appendExtraSections(StringBuilder sb, ReportModel report) {
        sb.append("<details class=\"list-sec\">\n<summary>SKIP（").append(report.skipped().size())
                .append("）——无法分析项，不影响退出码</summary>\n<ul class=\"plain-list\">\n");
        for (SkippedItem item : report.skipped()) {
            sb.append("<li><span class=\"tag\">").append(escape(item.kind().name())).append("</span> ")
                    .append("<code>").append(escape(item.statementId() == null ? "（未知）" : item.statementId()))
                    .append("</code> <span class=\"loc-inline\">").append(escape(item.file())).append(':')
                    .append(item.line()).append("</span> — ").append(escape(item.reason())).append("</li>\n");
        }
        sb.append("</ul>\n</details>\n");

        sb.append("<details class=\"list-sec\">\n<summary>已忽略（").append(report.ignored().size())
                .append("）——白名单命中，不参与分析</summary>\n<ul class=\"plain-list\">\n");
        for (IgnoredItem item : report.ignored()) {
            sb.append("<li><code>").append(escape(item.statementId())).append("</code> <span class=\"loc-inline\">")
                    .append(escape(item.file())).append(':').append(item.line()).append("</span> — ")
                    .append(escape(item.reason())).append("</li>\n");
        }
        sb.append("</ul>\n</details>\n");
    }

    // ---------- 工具 ----------

    private static String levelLabel(RiskLevel level) {
        if (level == RiskLevel.HIGH) {
            return "高危";
        }
        if (level == RiskLevel.MEDIUM) {
            return "中危";
        }
        if (level == RiskLevel.LOW) {
            return "低危";
        }
        return "通过";
    }

    private static String abbreviate(String commit) {
        return commit != null && commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    /** HTML 转义（文本与属性通用；语句原文含 XML 标签/比较符，必须转义）。 */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- 内联资源（离线自包含） ----------

    private static final String STYLE = "<style>\n"
            + ":root{--high:#c62828;--medium:#ef6c00;--low:#2e7d32;--passed:#00838f;--skip:#607d8b;--ignored:#757575;"
            + "--bg:#fafafa;--card:#fff;--border:#e0e0e0;}\n"
            + "*{box-sizing:border-box;}\n"
            + "body{margin:0;background:var(--bg);color:#212121;font:14px/1.6 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif;}\n"
            + "header{background:#263238;color:#fff;padding:20px 28px;}\n"
            + "header h1{margin:0 0 8px;font-size:20px;}\n"
            + ".meta .kv{margin-right:20px;opacity:.9;}\n"
            + ".meta code{background:rgba(255,255,255,.15);padding:1px 6px;border-radius:3px;}\n"
            + ".summary{display:flex;align-items:center;gap:14px;flex-wrap:wrap;padding:16px 28px;}\n"
            + ".stat{background:var(--card);border:1px solid var(--border);border-left-width:5px;border-radius:6px;"
            + "padding:8px 16px;display:flex;flex-direction:column;align-items:center;min-width:84px;}\n"
            + ".stat .num{font-size:22px;font-weight:700;}\n"
            + ".stat.high{border-left-color:var(--high);} .stat.high .num{color:var(--high);}\n"
            + ".stat.medium{border-left-color:var(--medium);} .stat.medium .num{color:var(--medium);}\n"
            + ".stat.low{border-left-color:var(--low);} .stat.low .num{color:var(--low);}\n"
            + ".stat.passed{border-left-color:var(--passed);} .stat.passed .num{color:var(--passed);}\n"
            + ".stat.skip{border-left-color:var(--skip);} .stat.ignored{border-left-color:var(--ignored);}\n"
            + ".identity{color:#555;font-size:13px;}\n"
            + ".filters{padding:0 28px 12px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;}\n"
            + ".f-label{color:#666;}\n"
            + ".filters select,.filters input{padding:5px 8px;border:1px solid var(--border);border-radius:4px;background:#fff;font:inherit;}\n"
            + ".filters input{min-width:240px;}\n"
            + ".f-level{padding:5px 14px;border:1px solid var(--border);border-radius:999px;background:#fff;cursor:pointer;font:inherit;}\n"
            + ".f-level.active{background:#263238;color:#fff;border-color:#263238;}\n"
            + ".f-count{color:#666;margin-left:auto;}\n"
            + "details.level-sec,details.list-sec{margin:10px 28px;background:var(--card);border:1px solid var(--border);border-radius:6px;}\n"
            + "details summary{padding:10px 16px;cursor:pointer;font-weight:600;user-select:none;}\n"
            + ".card{border-top:1px solid var(--border);padding:12px 16px;}\n"
            + ".card-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;}\n"
            + ".sid{font-weight:600;}\n"
            + ".badge{display:inline-block;color:#fff;border-radius:4px;padding:0 8px;font-size:12px;font-weight:600;line-height:20px;}\n"
            + ".b-HIGH{background:var(--high);} .b-MEDIUM{background:var(--medium);} .b-LOW{background:var(--low);} .b-NONE{background:var(--skip);}\n"
            + ".tag{background:#eceff1;color:#455a64;border-radius:4px;padding:0 8px;font-size:12px;line-height:20px;}\n"
            + ".tag.via{background:#e3f2fd;color:#1565c0;} .tag.warn{background:#fff3e0;color:#e65100;}\n"
            + ".loc{color:#777;font-size:12px;margin:2px 0 6px;}\n"
            + ".loc-inline{color:#777;font-size:12px;}\n"
            + ".sql{background:#263238;color:#eceff1;padding:10px 12px;border-radius:5px;margin:6px 0;"
            + "white-space:pre-wrap;word-break:break-all;font:12.5px/1.5 ui-monospace,Menlo,Consolas,monospace;}\n"
            + ".findings{margin:6px 0 0;padding-left:0;list-style:none;}\n"
            + ".findings li{padding:4px 0;border-top:1px dashed #eee;}\n"
            + ".findings .meta{color:#888;font-size:12px;}\n"
            + ".nofinding{color:#888;margin:6px 0 0;}\n"
            + ".plain-list{margin:0;padding:6px 16px 12px 34px;}\n"
            + ".plain-list li{padding:2px 0;}\n"
            + ".plain-list code{background:#eceff1;padding:1px 5px;border-radius:3px;}\n"
            + "footer{color:#888;text-align:center;padding:18px;font-size:12px;}\n"
            + ".card[hidden],details[hidden]{display:none;}\n"
            + "</style>\n";

    private static final String SCRIPT = "<script>\n"
            + "(function(){\n"
            + "  var cards=[].slice.call(document.querySelectorAll('article.card'));\n"
            + "  var secs=[].slice.call(document.querySelectorAll('details.level-sec'));\n"
            + "  var ruleSel=document.getElementById('f-rule'),fileSel=document.getElementById('f-file'),q=document.getElementById('f-q');\n"
            + "  var btns=[].slice.call(document.querySelectorAll('.f-level'));\n"
            + "  var count=document.getElementById('f-count');\n"
            + "  var level='ALL';\n"
            + "  function apply(){\n"
            + "    var rule=ruleSel.value,file=fileSel.value,kw=q.value.trim().toLowerCase(),shown=0;\n"
            + "    cards.forEach(function(c){\n"
            + "      var ok=(level==='ALL'||c.dataset.level===level)\n"
            + "        &&(rule==='ALL'||(' '+c.dataset.rules+' ').indexOf(' '+rule+' ')>=0)\n"
            + "        &&(file==='ALL'||c.dataset.file===file)\n"
            + "        &&(kw===''||c.textContent.toLowerCase().indexOf(kw)>=0);\n"
            + "      c.hidden=!ok; if(ok){shown++;}\n"
            + "    });\n"
            + "    secs.forEach(function(s){\n"
            + "      var vis=[].slice.call(s.querySelectorAll('article.card')).filter(function(c){return !c.hidden;});\n"
            + "      s.hidden=vis.length===0;\n"
            + "      if(level!=='ALL'&&s.dataset.level===level&&vis.length>0){s.open=true;}\n"
            + "    });\n"
            + "    count.textContent=shown;\n"
            + "  }\n"
            + "  btns.forEach(function(b){\n"
            + "    b.addEventListener('click',function(){\n"
            + "      btns.forEach(function(x){x.classList.remove('active');});\n"
            + "      b.classList.add('active'); level=b.dataset.level; apply();\n"
            + "    });\n"
            + "  });\n"
            + "  ruleSel.addEventListener('change',apply);\n"
            + "  fileSel.addEventListener('change',apply);\n"
            + "  q.addEventListener('input',apply);\n"
            + "})();\n"
            + "</script>\n";
}
