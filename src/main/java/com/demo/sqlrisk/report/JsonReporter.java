package com.demo.sqlrisk.report;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.IgnoredItem;
import com.demo.sqlrisk.model.ReportModel;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementResult;
import com.demo.sqlrisk.model.Summary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 报告序列化（详细设计 §12.3）：
 * Gson 配置 serializeNulls（FULL 模式 prodCommit: null 键保留）、disableHtmlEscaping、prettyPrinting；
 * DTO 字段声明顺序即 JSON 键顺序；字段契约 = 需求 §10.2 + 已确认追加字段（D5）。
 */
public final class JsonReporter {

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private JsonReporter() {
    }

    public static String toJson(ReportModel report) {
        return GSON.toJson(toDto(report));
    }

    private static ReportDto toDto(ReportModel report) {
        ReportDto dto = new ReportDto();
        dto.generatedAt = report.generatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        dto.mode = report.mode().name();
        dto.headCommit = report.headCommit();
        dto.prodCommit = report.prodCommit();
        dto.summary = new SummaryDto(report.summary());
        dto.results = new ArrayList<ResultDto>();
        for (StatementResult result : report.results()) {
            dto.results.add(new ResultDto(result));
        }
        dto.ignored = new ArrayList<IgnoredDto>();
        for (IgnoredItem item : report.ignored()) {
            dto.ignored.add(new IgnoredDto(item));
        }
        dto.skipped = new ArrayList<SkippedDto>();
        for (SkippedItem item : report.skipped()) {
            dto.skipped.add(new SkippedDto(item));
        }
        return dto;
    }

    // ---------- DTO（字段声明顺序 = JSON 键顺序） ----------

    private static final class ReportDto {
        String generatedAt;
        String mode;
        String headCommit;
        String prodCommit; // nullable
        SummaryDto summary;
        List<ResultDto> results;
        List<IgnoredDto> ignored;
        List<SkippedDto> skipped;
    }

    private static final class SummaryDto {
        int total;
        int high;
        int medium;
        int low;
        int skip;
        int ignored;

        SummaryDto(Summary summary) {
            this.total = summary.total();
            this.high = summary.high();
            this.medium = summary.medium();
            this.low = summary.low();
            this.skip = summary.skip();
            this.ignored = summary.ignored();
        }
    }

    private static final class ResultDto {
        String statementId;
        String source;
        String file;
        int line;
        String changeType;
        String sql;
        List<FindingDto> findings;
        String level;      // nullable（无命中）
        boolean uncertain;
        String changedVia; // nullable（片段派生命中）

        ResultDto(StatementResult result) {
            this.statementId = result.meta().statementId();
            this.source = result.meta().source().name();
            this.file = result.meta().file();
            this.line = result.meta().line();
            this.changeType = result.meta().changeType().name();
            this.sql = result.sql();
            this.findings = new ArrayList<FindingDto>();
            for (Finding finding : result.findings()) {
                this.findings.add(new FindingDto(finding));
            }
            this.level = result.level() == null ? null : result.level().name();
            this.uncertain = result.uncertain();
            this.changedVia = result.meta().changedVia();
        }
    }

    private static final class FindingDto {
        String ruleId;
        String level;
        String message;
        String snippet;
        int skeleton;

        FindingDto(Finding finding) {
            this.ruleId = finding.ruleId();
            this.level = finding.level().name();
            this.message = finding.message();
            this.snippet = finding.snippet();
            this.skeleton = finding.skeletonIndex();
        }
    }

    private static final class IgnoredDto {
        String statementId;
        String file;
        int line;
        String reason;

        IgnoredDto(IgnoredItem item) {
            this.statementId = item.statementId();
            this.file = item.file();
            this.line = item.line();
            this.reason = item.reason();
        }
    }

    private static final class SkippedDto {
        String statementId; // nullable
        String file;
        int line;
        String kind;
        String reason;

        SkippedDto(SkippedItem item) {
            this.statementId = item.statementId();
            this.file = item.file();
            this.line = item.line();
            this.kind = item.kind().name();
            this.reason = item.reason();
        }
    }
}
