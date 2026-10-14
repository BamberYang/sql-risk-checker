package com.demo.sqlrisk.testutil;

import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.Mode;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.RiskLevel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementMeta;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.model.Summary;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * M8 报告测试夹具：构造字段组合齐备的 {@link ReportModel}（结果 4 条：H/M/L/无命中；
 * SKIP 2 条（含 statementId=null）；白名单 1 条；DIFF 模式带 prodCommit）。
 * 恒等式：提取总数 7 = total 4 + skip 2 + ignored 1。
 */
public final class ReportFixtures {

    public static final String HEAD_COMMIT = "a32658d8c2e4f1a9b3d5e7f9012345678abcdef1";
    public static final String PROD_COMMIT = "b7c1d2e3f4a5b6c7d8e9f0123456789abcdef012";
    public static final String GENERATED_AT = "2026-10-14T08:00:00+08:00";
    public static final String REPORT_PATH = "/tmp/sql-risk-out/sql_risk_report.json";
    public static final String HTML_REPORT_PATH = "/tmp/sql-risk-out/sql_risk_report.html";
    public static final String REPO_PATH = "/repo/demo";

    public static final String H1_MESSAGE = "UPDATE 语句缺少 WHERE 条件，可能导致全表更新";
    public static final String M1_MESSAGE = "查询未命中索引：t_order (no_index)";

    private ReportFixtures() {
    }

    public static ReportModel sampleReport() {
        return sampleReport(Mode.DIFF, PROD_COMMIT);
    }

    /** 结果 4 条（1 高危 / 1 中危 / 1 低危 / 1 无命中）+ 白名单 1 + SKIP 2。 */
    public static ReportModel sampleReport(Mode mode, String prodCommit) {
        List<StatementResult> results = new ArrayList<StatementResult>();

        Finding h1 = new Finding("H1", RiskLevel.HIGH, H1_MESSAGE,
                "UPDATE t_order SET status = ?", 0);
        results.add(new StatementResult(
                meta("com.demo.mapper.OrderMapper.updateStatus", StatementSource.MAPPER_XML,
                        "src/main/resources/mapper/OrderMapper.xml", 42, ChangeType.MODIFIED,
                        "com.demo.mapper.Common.baseColumns"),
                "UPDATE t_order SET status = ?", Collections.singletonList(h1), RiskLevel.HIGH, false));

        Finding m1 = new Finding("M1", RiskLevel.MEDIUM, M1_MESSAGE,
                "SELECT id FROM t_order WHERE name = ?", 0);
        results.add(new StatementResult(
                meta("com.demo.mapper.OrderMapper.findByName", StatementSource.ANNOTATION,
                        "src/main/java/com/demo/mapper/OrderMapper.java", 10, ChangeType.MODIFIED, null),
                "SELECT id FROM t_order WHERE name = ?", Collections.singletonList(m1), RiskLevel.MEDIUM, true));

        Finding l2 = new Finding("L2", RiskLevel.LOW, "SELECT * 且带过滤条件",
                "SELECT * FROM t_order WHERE id = ?", 0);
        results.add(new StatementResult(
                meta("com.demo.mapper.OrderMapper.find", StatementSource.MAPPER_XML,
                        "src/main/resources/mapper/OrderMapper.xml", 60, ChangeType.ADDED, null),
                "SELECT * FROM t_order WHERE id = ?", Collections.singletonList(l2), RiskLevel.LOW, false));

        results.add(new StatementResult(
                meta("com.demo.mapper.OrderMapper.count", StatementSource.MAPPER_XML,
                        "src/main/resources/mapper/OrderMapper.xml", 80, ChangeType.MODIFIED, null),
                "SELECT count(1) FROM t_order WHERE state = ?", Collections.<Finding>emptyList(), null, false));

        List<IgnoredItem> ignored = Collections.singletonList(
                new IgnoredItem("m.legacy", "src/main/resources/mapper/Legacy.xml", 5, "whitelist"));
        List<SkippedItem> skipped = Arrays.asList(
                new SkippedItem("m.bad", "src/main/resources/mapper/Bad.xml", 3,
                        SkippedItem.Kind.PARSE_FAILED, "解析失败：意外的记号 '&&'"),
                new SkippedItem(null, "src/main/java/com/demo/mapper/Prov.java", 0,
                        SkippedItem.Kind.UNSUPPORTED_CARRIER, "Provider 方法无法静态求值"));

        Summary summary = new Summary(results.size(), 1, 1, 1, skipped.size(), ignored.size());
        return new ReportModel(generatedAt(), mode, HEAD_COMMIT, prodCommit, summary,
                results, ignored, skipped);
    }

    /** 空报告（无差异 / 未提取到语句场景）。 */
    public static ReportModel emptyReport(Mode mode) {
        return new ReportModel(generatedAt(), mode, HEAD_COMMIT, null, new Summary(0, 0, 0, 0, 0, 0),
                Collections.<StatementResult>emptyList(), Collections.<IgnoredItem>emptyList(),
                Collections.<SkippedItem>emptyList());
    }

    public static OffsetDateTime generatedAt() {
        return OffsetDateTime.ofInstant(TestClocks.FIXED_INSTANT, TestClocks.FIXED_ZONE);
    }

    private static StatementMeta meta(String id, StatementSource source, String file, int line,
                                      ChangeType changeType, String changedVia) {
        return StatementMeta.from(new ExtractedStatement(id, source, changeType, file, line,
                "raw", new Object(), changedVia));
    }
}
