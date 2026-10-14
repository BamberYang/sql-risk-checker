package com.demo.sqlrisk.rule;

import com.demo.sqlrisk.model.Finding;
import com.demo.sqlrisk.model.RiskLevel;

import java.util.List;

/**
 * 规则 SPI（详细设计 §11.1）：新增规则只实现本接口 + 注册（内置注册表 / ServiceLoader 扩展），
 * 不改动核心流程（NFR 可维护性）。规则的 level/enabled 可由配置覆盖（§11.3）。
 */
public interface Rule {

    String id();

    /** 默认级别（配置可覆盖）。 */
    RiskLevel defaultLevel();

    /** 执行顺序（升序）；语句级短路规则为负值最先执行（§17 优先级表）。 */
    int priority();

    /** 命中后该语句停止后续全部规则（当前仅 M8）。 */
    boolean shortCircuit();

    /** @param ctx 语句级短路规则（{@link #shortCircuit()}）执行时 {@code ctx.parsedSql()} 为 null */
    List<Finding> check(RuleContext ctx);
}
