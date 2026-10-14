package com.demo.sqlrisk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 提取阶段的语句条目（详细设计 §4.2/§5.5.1）。
 *
 * <p>{@code carrier} 为承载载荷，由后续阶段消费：
 * mapper.xml 语句的动态片段文本 / 注解 SQL 文本 / Lambda 链模型（M5 起定义具体类型）。</p>
 */
public final class ExtractedStatement {

    private final String statementId;
    private final StatementSource source;
    private final ChangeType changeType;
    private final String file;
    private final int line;
    private final String rawText;
    private final Object carrier;
    private final String changedVia; // nullable：片段（<sql>/<include>）派生命中时的来源片段 refid
    private final List<String> wrapperSqlParams; // 注解 SQL 中解析到 Wrapper 家族参数的 ${} 根名（§9.5-6），空=无

    /** 兼容构造：无 Wrapper SQL 段（mapper.xml / Lambda / 未解析出 Wrapper 参数）。 */
    public ExtractedStatement(String statementId,
                              StatementSource source,
                              ChangeType changeType,
                              String file,
                              int line,
                              String rawText,
                              Object carrier,
                              String changedVia) {
        this(statementId, source, changeType, file, line, rawText, carrier, changedVia,
                Collections.<String>emptyList());
    }

    public ExtractedStatement(String statementId,
                              StatementSource source,
                              ChangeType changeType,
                              String file,
                              int line,
                              String rawText,
                              Object carrier,
                              String changedVia,
                              List<String> wrapperSqlParams) {
        this.statementId = Objects.requireNonNull(statementId, "statementId");
        this.source = Objects.requireNonNull(source, "source");
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.rawText = Objects.requireNonNull(rawText, "rawText");
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        this.changedVia = changedVia;
        this.wrapperSqlParams = Collections.unmodifiableList(new ArrayList<String>(
                Objects.requireNonNull(wrapperSqlParams, "wrapperSqlParams")));
    }

    public String statementId() {
        return statementId;
    }

    public StatementSource source() {
        return source;
    }

    public ChangeType changeType() {
        return changeType;
    }

    /** 仓库相对路径。 */
    public String file() {
        return file;
    }

    /** 语句起始行（1 基）。 */
    public int line() {
        return line;
    }

    /** 语句原文（XML 片段 / 注解文本 / Lambda 链源码）：M8 短路与 L1 判定基于此（含被丢弃分支）。 */
    public String rawText() {
        return rawText;
    }

    public Object carrier() {
        return carrier;
    }

    /** 可为 null：因 &lt;sql&gt; 片段变更派生命中时的来源片段 refid（决策 D20）。 */
    public String changedVia() {
        return changedVia;
    }

    /** 是否为片段派生变更。 */
    public boolean isPropagated() {
        return changedVia != null;
    }

    /**
     * 注解 SQL 中根名解析到 MyBatis-Plus Wrapper 家族参数的 {@code ${name…}} 占位符根名集合（§9.5-6）。
     * 空列表 = 无此类占位符；供解析阶段代换（§10.6）与 M8 判定（§11.4）消费。
     */
    public List<String> wrapperSqlParams() {
        return wrapperSqlParams;
    }

    public boolean hasWrapperSqlParams() {
        return !wrapperSqlParams.isEmpty();
    }
}
