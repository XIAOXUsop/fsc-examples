package com.fsc.cases;

import com.fsc.cases.model.AgentAnalysis;
import com.fsc.cases.model.RiskLevel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * 评级引擎：
 *  - mockRate: 关键词模拟打分（离线演示，多轮工具调用的行为仿真）
 *  - live:     真实模型接入（OpenAI-compatible，DeepSeek/通义/OpenAI 均可）
 */
public final class RatingEngine {

    private RatingEngine() {
    }

    /** Mock 评级：基于关键词模拟模型输出结构（用于离线全链路） */
    public static AgentAnalysis mockRate(String input) {
        if (input == null) return null;
        String s = input.toLowerCase();

        // 注入输入 → 引用 AML-005（按其语义无依据结论必须转人工）
        if (s.contains("ignore all previous") || s.contains("system prompt") || s.contains("print your prompt")) {
            return new AgentAnalysis(RiskLevel.HIGH, "输入含注入特征，按 AML-005 转人工", "AML-005");
        }
        // 一级制裁
        if (s.contains("一级制裁") || s.contains("ofac") || s.contains("制裁名单")) {
            return new AgentAnalysis(RiskLevel.HIGH, "命中一级制裁名单", "AML-001");
        }
        // 拆分：等额 + 多笔 + 存取
        boolean split = s.contains("等额") && (s.contains("存") || s.contains("取")) && (s.contains("笔") || s.contains("次"));
        if (split) {
            // 大额拆分 → HIGH；小额结构性拆分 → MEDIUM
            boolean big = s.contains("5 万") || s.contains("5万") || s.contains("5 万元");
            return new AgentAnalysis(big ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                    "结构化拆分交易特征", "AML-002");
        }
        // 夜间跨境大额
        if (s.contains("跨境")) {
            boolean night = s.contains("夜间") || s.contains("凌晨")
                    || s.contains("01:") || s.contains("02:") || s.contains("03:")
                    || s.contains("01：") || s.contains("02：") || s.contains("03：")
                    || s.contains("(01") || s.contains("(02") || s.contains("(03")
                    || s.contains("（01") || s.contains("（02") || s.contains("（03");
            boolean big = s.contains("60") || s.contains("65") || s.contains("80")
                    || s.contains("50 万") || s.contains("50万");
            if (night && big) {
                return new AgentAnalysis(RiskLevel.HIGH, "夜间跨境大额交易", "AML-003");
            }
        }
        // 默认：基线正常
        return new AgentAnalysis(RiskLevel.LOW, "无制裁/拆分/夜间大额特征", "AML-004");
    }

    /** 真实模型（OpenAI-compatible: DeepSeek/Qwen/OpenAI 均可） */
    public static ChatModel live(String apiKey, String baseUrl, String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)
                .build();
    }
}
