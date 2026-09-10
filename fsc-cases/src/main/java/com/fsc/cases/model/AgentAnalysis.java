package com.fsc.cases.model;

/**
 * 尽调分析结构化输出（不携带客户身份信息；evidenceId 必须来自 LegalIndex）
 */
public record AgentAnalysis(
        RiskLevel riskLevel,
        String rationale,
        /** 合法引用：AML-001 ~ AML-005 */
        String evidenceId
) {
}
