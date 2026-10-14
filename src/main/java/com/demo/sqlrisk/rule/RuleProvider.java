package com.demo.sqlrisk.rule;

import java.util.List;

/** 规则提供者 SPI（ServiceLoader 扩展点，§5.7）：第三方/团队自定义规则包实现本接口即可接入。 */
public interface RuleProvider {

    List<Rule> rules();
}
