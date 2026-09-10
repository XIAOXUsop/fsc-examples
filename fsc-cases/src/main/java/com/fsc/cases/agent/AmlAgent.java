package com.fsc.cases.agent;

import com.fsc.cases.model.AgentAnalysis;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * AML 尽调 Agent（LangChain4j AI Service）。
 *
 * <p>由真实大模型驱动：可调用工具（制裁筛查 / 交易查询 / 法规检索），并借助检索到的法规条文，
 * 最终以<b>结构化对象</b> {@link AgentAnalysis} 返回评级结论。
 *
 * <p>模型输出仍需经过 {@code GuardrailEvaluator} 的确定性校验——这正是本示例想演示的重点：
 * 大模型负责研判，护栏负责兜底。
 */
public interface AmlAgent {

    @SystemMessage("""
            你是商业银行反洗钱（AML）尽职调查分析助手。
            收到客户可疑交易描述后，请按以下要求工作：
            1. 必要时调用工具：制裁名单筛查、交易画像查询、法规条文检索；
            2. 依据检索到的法规条文给出风险评级；
            3. riskLevel 只能是 LOW、MEDIUM、HIGH 之一；
            4. evidenceId 必须来自检索结果中真实存在的法规编号（如 AML-001），严禁编造；
            5. 不要在输出中回显客户真实身份信息。
            """)
    AgentAnalysis analyze(@UserMessage String caseInput);
}
