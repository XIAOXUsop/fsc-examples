package com.fsc.cases.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fsc.cases.RatingEngine;
import com.fsc.cases.data.LegalIndex;
import com.fsc.cases.guardrail.GuardrailEvaluator;
import com.fsc.cases.model.AgentAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型 Agent 冒烟测试 —— <b>默认跳过</b>，仅当显式开启时才调用外部模型。
 *
 * <pre>
 * RUN_LIVE=true DEEPSEEK_API_KEY=xxx mvn -pl fsc-cases test -Dtest=AmlAgentLiveTest
 * </pre>
 *
 * 可选环境变量：{@code MODEL_BASE_URL}（默认 https://api.deepseek.com/v1）、
 * {@code MODEL_NAME}（默认 deepseek-chat）。
 *
 * <p>断言重点不是"模型答得多准"，而是链路可用且护栏能兜住：结构化输出非空、
 * 护栏后 evidenceId 落在白名单内。报告写入 {@code target/fsc-eval/}。
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE", matches = "true")
class AmlAgentLiveTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String CASE_INPUT =
            "客户张某近3月夜间(01:30)跨境等值80万元交易2笔，命中OFAC一级制裁名单";

    @Test
    void analyzesSanctionCaseWithRealModel() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertNotNull(apiKey, "RUN_LIVE=true 时必须提供 DEEPSEEK_API_KEY");
        String baseUrl = System.getenv().getOrDefault("MODEL_BASE_URL", "https://api.deepseek.com/v1");
        String modelName = System.getenv().getOrDefault("MODEL_NAME", "deepseek-chat");

        AmlAgent agent = RatingEngine.liveAgent(apiKey, baseUrl, modelName);

        AgentAnalysis raw = agent.analyze(CASE_INPUT);
        assertNotNull(raw, "模型应返回结构化结果");
        assertNotNull(raw.riskLevel(), "riskLevel 不应为空");

        GuardrailEvaluator.GuardedResult guarded = GuardrailEvaluator.evaluate(raw, CASE_INPUT);
        assertTrue(LegalIndex.VALID_EVIDENCE_IDS.contains(guarded.corrected().evidenceId()),
                "护栏后 evidenceId 必须在白名单内，实际：" + guarded.corrected().evidenceId());

        System.out.printf("Live OK: raw=%s/%s → final=%s hold=%s%n",
                raw.riskLevel(), raw.evidenceId(), guarded.finalLevel(), guarded.manualHold());

        writeReport(modelName, raw, guarded);
    }

    private void writeReport(String modelName, AgentAnalysis raw, GuardrailEvaluator.GuardedResult guarded)
            throws Exception {
        Path dir = Path.of("target", "fsc-eval");
        Files.createDirectories(dir);
        Path reportFile = dir.resolve("live-report-%s.json".formatted(Instant.now().toEpochMilli()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("engine", "live");
        report.put("model", modelName);
        report.put("rawLevel", raw.riskLevel().name());
        report.put("rawEvidence", raw.evidenceId());
        report.put("finalLevel", guarded.finalLevel().name());
        report.put("manualHold", guarded.manualHold());
        report.put("violations", guarded.violations());

        MAPPER.writeValue(reportFile.toFile(), report);
        System.out.println("Live report written: " + reportFile.toAbsolutePath());
    }
}
