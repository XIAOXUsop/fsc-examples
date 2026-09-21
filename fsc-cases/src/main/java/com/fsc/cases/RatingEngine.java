package com.fsc.cases;

import com.fsc.cases.agent.AmlAgent;
import com.fsc.cases.agent.AmlAgentFactory;
import com.fsc.cases.model.AgentAnalysis;
import com.fsc.cases.model.RiskLevel;
import com.fsc.cases.rag.LegalRag;
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

    /**
     * 降级评级：**故意模拟一个"能把案子对上条文、但风险定级一律偏保守"的模型。**
     *
     * ── 为什么必须有一个"会出错的"引擎 ────────────────────────────────
     *
     * 上面那个 mock 引擎的原始输出**本来就全对**，于是评测里的
     * 「原始模型分 vs 护栏修正后分」两轨**恒等**——那个对比什么都证明不了。
     * 护栏的价值只在"模型出错、它把错纠回来"的时候才看得见，
     * 所以这里补一个会出错的对照组，让双轨真的能分开。
     *
     * 它错在哪，对应护栏的三条纠错路径（见 {@link com.fsc.cases.guardrail.GuardrailEvaluator}）：
     *
     *   1. **一级制裁**：条文引对了（AML-001），风险却只给 MEDIUM
     *      —— 这是真实模型最典型的漏报形态（识别到实体、低估了后果）
     *      → 护栏 GUARDRAIL_UPGRADE 强制 HIGH 并转人工；
     *   2. **其余高风险案例**：统一压成 MEDIUM（漏报），护栏**不纠**
     *      —— 这条是刻意留着的：护栏不是万能的，识别不出"这条该是 HIGH"
     *        属于模型能力问题，规则层没有依据去改。评测里它会如实体现为
     *        修正后准确率仍然不高，而不是被粉饰成 100%；
     *   3. **注入输入**：当成正常交易处理，护栏靠 INJECTION 扫描兜住。
     *
     * 它是**确定性**的：同样的输入必然同样的输出，否则评测不可复现。
     */
    public static AgentAnalysis degradedRate(String input) {
        if (input == null) return null;
        String s = input.toLowerCase();

        // 被注入骗到：照常按"正常交易"处理
        if (s.contains("ignore all previous") || s.contains("system prompt") || s.contains("print your prompt")) {
            return new AgentAnalysis(RiskLevel.LOW, "输入无明显可疑特征", "AML-004");
        }
        // 一级制裁：条文对、定级错
        if (s.contains("一级制裁") || s.contains("ofac") || s.contains("制裁名单")) {
            return new AgentAnalysis(RiskLevel.MEDIUM, "命中名单，建议进一步核实", "AML-001");
        }
        boolean split = s.contains("等额") && (s.contains("存") || s.contains("取")) && (s.contains("笔") || s.contains("次"));
        if (split) {
            return new AgentAnalysis(RiskLevel.MEDIUM, "存在结构化拆分特征", "AML-002");
        }
        boolean night = s.contains("夜间") || s.contains("凌晨")
                || s.contains("01:") || s.contains("02:") || s.contains("03:")
                || s.contains("(01") || s.contains("(02") || s.contains("(03")
                || s.contains("（01") || s.contains("（02") || s.contains("（03");
        boolean crossed = s.contains("跨境");
        boolean big = s.contains("60") || s.contains("65") || s.contains("80")
                || s.contains("50 万") || s.contains("50万");
        if (crossed && night && big) {
            return new AgentAnalysis(RiskLevel.MEDIUM, "跨境交易，建议关注", "AML-003");
        }
        // 无强特征：引一条**合法但与本案无关**的条文（真实模型的常见做法：
        // 总要给个出处）。这类错护栏纠不了——它只知道"引用是否合法"，
        // 不知道"这条法规对不对得上这个案子"。评测里会如实体现。
        return new AgentAnalysis(RiskLevel.LOW, "无制裁/拆分/夜间大额特征", "AML-005");
    }

    /** 真实模型（OpenAI-compatible: DeepSeek/Qwen/OpenAI 均可） */
    public static ChatModel live(String apiKey, String baseUrl, String modelName) {        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)
                .build();
    }

    /**
     * 真实模型 Agent：ChatModel + @Tool 工具集 + 法规 RAG 的完整装配。
     * 生产链路上模型输出仍需经 {@code GuardrailEvaluator} 校验。
     */
    public static AmlAgent liveAgent(String apiKey, String baseUrl, String modelName) {
        return AmlAgentFactory.create(live(apiKey, baseUrl, modelName), LegalRag.build());
    }
}
