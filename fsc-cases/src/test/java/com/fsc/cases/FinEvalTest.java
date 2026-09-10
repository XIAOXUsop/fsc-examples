package com.fsc.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fsc.cases.data.CaseDataset;
import com.fsc.cases.guardrail.GuardrailEvaluator;
import com.fsc.cases.model.AgentAnalysis;
import com.fsc.cases.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FinEval — 金融合规评级评测（全离线，Mock 模型）。
 * 双轨计分：原始模型准确率 vs Guardrails 后准确率 + 法规证据引用率。
 * 结果写 target/fsc-eval/report-{ts}.json 供 CI 与人工审计。
 */
class FinEvalTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void offlineEvalMock() throws Exception {
        var cases = CaseDataset.ratingCases();
        int rawRating = 0, rawEvidence = 0, grRating = 0, grEvidence = 0;
        List<Map<String, Object>> caseRows = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        for (var c : cases) {
            AgentAnalysis out = RatingEngine.mockRate(c.input());
            var g = GuardrailEvaluator.evaluate(out, c.input());

            if (c.expected() == out.riskLevel()) rawRating++;
            if (c.expectedEvidence().equals(out.evidenceId())) rawEvidence++;
            if (c.expected() == g.finalLevel()) grRating++;
            if (c.expectedEvidence().equals(g.corrected().evidenceId())) grEvidence++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", c.caseId());
            row.put("expected", c.expected().name());
            row.put("expectedEvidence", c.expectedEvidence());
            row.put("rawLevel", out.riskLevel().name());
            row.put("rawEvidence", out.evidenceId());
            row.put("finalLevel", g.finalLevel().name());
            row.put("manualHold", g.manualHold());
            row.put("violations", g.violations());
            caseRows.add(row);

            summary.add("%s raw=%s final=%s hold=%s%s".formatted(
                    c.caseId(), out.riskLevel(), g.finalLevel(), g.manualHold(),
                    g.violations().isEmpty() ? "" : " " + g.violations()));
        }

        int n = cases.size();
        System.out.printf("FinEval(n=%d) 原始评级 %.1f%% → 护栏后 %.1f%%  证据: %.1f%% → %.1f%%%n",
                n, ratio(rawRating, n), ratio(grRating, n), ratio(rawEvidence, n), ratio(grEvidence, n));
        summary.forEach(s -> System.out.println("  " + s));

        // 护栏不得让准确率下降
        assertTrue(grRating >= rawRating, "护栏修正后准确率不得降低");
        // DEV 集是 mock 规则的参考实现，护栏后必须全对；新增案例若破坏一致性会在此暴露
        assertEquals(n, grRating, "护栏后评级准确率应为 100%");
        assertEquals(n, grEvidence, "护栏后证据引用准确率应为 100%");
        // 一级制裁命中必须转人工
        assertHoldForSanction(cases);

        // 落盘 JSON 报告
        Path report = writeReport(n, rawRating, rawEvidence, grRating, grEvidence, caseRows);
        System.out.println("Report written: " + report.toAbsolutePath());
    }

    private Path writeReport(int total, int rawRating, int rawEvidence,
                             int grRating, int grEvidence,
                             List<Map<String, Object>> caseRows) throws IOException {
        Path dir = Path.of("target", "fsc-eval");
        Files.createDirectories(dir);
        Path reportFile = dir.resolve("report-%s.json".formatted(Instant.now().toEpochMilli()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("engine", "mock");
        report.put("total", total);
        report.put("rawRatingAccuracyPercent", ratio(rawRating, total));
        report.put("guardrailedRatingAccuracyPercent", ratio(grRating, total));
        report.put("rawEvidenceAccuracyPercent", ratio(rawEvidence, total));
        report.put("guardrailedEvidenceAccuracyPercent", ratio(grEvidence, total));
        report.put("cases", caseRows);

        MAPPER.writeValue(reportFile.toFile(), report);
        return reportFile;
    }

    /** 百分比（保留两位小数） */
    private static double ratio(int correct, int total) {
        return total == 0 ? 0.0 : Math.round(correct * 10000.0 / total) / 100.0;
    }

    private void assertHoldForSanction(List<CaseDataset.Case> cases) {
        for (var c : cases) {
            if ("AML-001".equals(c.expectedEvidence())) {
                var g = GuardrailEvaluator.evaluate(RatingEngine.mockRate(c.input()), c.input());
                assertTrue(g.manualHold(), c.caseId() + " 一级制裁命中必须转人工");
                assertEquals(RiskLevel.HIGH, g.finalLevel(), c.caseId() + " 必须为 HIGH");
            }
        }
    }
}
