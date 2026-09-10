package com.fsc.cases.guardrail;

import com.fsc.cases.data.LegalIndex;
import com.fsc.cases.model.AgentAnalysis;
import com.fsc.cases.model.RiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 合规护栏（独立于大模型的确定性规则）：
 *  1. 法规证据引用白名单 —— 不引用 → 强制 HIGH + 人工标记
 *  2. 条文语义强制 —— 引用 AML-001（一级制裁）必须 HIGH + 转人工
 *  3. Prompt 注入特征扫描
 *  4. 无依据兜底保守升级
 */
public final class GuardrailEvaluator {

    private static final Pattern INJECTION = Pattern.compile(
            "(ignore\\s+(all\\s+)?(previous|prior)\\s+instructions|disregard\\s+(your|all)|system\\s+prompt|print\\s+your\\s+prompt|you\\s+are\\s+now\\s+a)",
            Pattern.CASE_INSENSITIVE);

    public record GuardedResult(
            RiskLevel finalLevel,
            boolean manualHold,
            AgentAnalysis corrected,
            List<String> violations
    ) {
    }

    /**
     * @param raw      模型原始输出（允许 null，视为无效）
     * @param rawInput 用户原始输入（用于注入扫描）
     */
    public static GuardedResult evaluate(AgentAnalysis raw, String rawInput) {
        List<String> findings = new ArrayList<>();
        RiskLevel level = raw != null && raw.riskLevel() != null ? raw.riskLevel() : RiskLevel.HIGH;
        String evidence = raw != null ? raw.evidenceId() : null;
        boolean hold = false;

        // 1. 证据引用校验
        if (evidence == null || !LegalIndex.VALID_EVIDENCE_IDS.contains(evidence)) {
            findings.add("EVIDENCE_MISSING: 未引用合法 evidenceId");
            hold = true;
        } else {
            // 2. 条文语义强制：一级制裁命中必须 HIGH + 转人工
            if ("AML-001".equals(evidence) && level != RiskLevel.HIGH) {
                findings.add("GUARDRAIL_UPGRADE: AML-001 一级制裁命中强制 HIGH");
                level = RiskLevel.HIGH;
                hold = true;
            }
        }

        // 3. 注入扫描
        if (rawInput != null && INJECTION.matcher(rawInput).find()) {
            findings.add("INJECTION_SUSPECTED: 输入命中注入特征");
            hold = true;
        }

        // 4. 无依据兜底
        if (hold && level != RiskLevel.HIGH) {
            findings.add("FALLBACK_CONSERVATIVE: 无依据结论保守升级为 HIGH");
            level = RiskLevel.HIGH;
        }

        AgentAnalysis fixed = new AgentAnalysis(level,
                raw != null ? raw.rationale() : "", evidence);
        return new GuardedResult(level, hold, fixed, List.copyOf(findings));
    }

    /** 掩码预览工具（报警/日志不回显完整敏感数据） */
    public static String mask(String raw) {
        if (raw == null) return "";
        if (raw.length() <= 6) return "***";
        return raw.substring(0, 3) + "****" + raw.substring(raw.length() - 3);
    }
}
