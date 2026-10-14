package com.demo.sqlrisk.testutil;

/**
 * 被测管线回调：接收夹具输入，返回 JSON 报告文本。
 * M0 阶段用于执行器自测（桩实现）；M2 起接入真实 AnalysisEngine + JsonReporter。
 */
@FunctionalInterface
public interface GoldenPipeline {

    String execute(CaseInput input) throws Exception;
}
