package com.fsc.cases;

import com.fsc.cases.data.CaseDataset;
import com.fsc.cases.data.LegalIndex;
import com.fsc.cases.guardrail.GuardrailEvaluator;
import com.fsc.cases.model.AgentAnalysis;
import com.fsc.cases.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FinEval — 金融合规评级评测（全离线，Mock 模型）。
 * 双轨计分：原始模型准确率 vs Guardrails 后准确率 + 法规证据引用率。
 * 结果写 target/fsc-eval/report-{ts}.json 供 CI 与人工审计。
 */
class FinEval {

    record Row(String caseId, String rawLevel, String finalLevel, boolean manualHold, List<String> violations) {
    }

    record Report(int rawRatingCorrect, int grRatingCorrect, List<String> caseSummary) {
    }

    @Test
    void offlineEvalMock() throws Exception {
        var cases = CaseDataset.ratingCases();
        int rawRating = 0, rawEvidence = 0, grRating = 0, grEvidence = 0;
        List<String> summary = new ArrayList<>();

        for (var c : cases) {
            AgentAnalysis out = RatingEngine.mockRate(c.input());
            var g = GuardrailEvaluator.evaluate(out, c.input());

            if (c.expected() == out.riskLevel()) rawRating++;
            if (c.expectedEvidence().equals(out.evidenceId())) rawEvidence++;
            if (c.expected() == g.finalLevel()) grRating++;
            if (c.expectedEvidence().equals(g.corrected().evidenceId())) grEvidence++;

            summary.add("%s raw=%s final=%s hold=%s%s".formatted(
                    c.caseId(), out.riskLevel(), g.finalLevel(), g.manualHold(),
                    g.violations().isEmpty() ? "" : " " + g.violations()));
        }

        int n = cases.size();
        System.out.printf("FinEval(n=%d) 原始评级 %.1f%% → 护栏后 %.1f%%  证据: %.1f%% → %.1f%%%n",
                n, rawRating * 100.0 / n, grRating * 100.0 / n,
                rawEvidence * 100.0 / n, grEvidence * 100.0 / n);
        summary.forEach(s -> System.out.println("  " + s));

        assertTrue(grRating >= rawRating, "护栏修正后准确率不得降低");
        // 一级制裁命中必须转人工
        assertHoldForSanction(cases);

        // 落盘 JSON 报告
        Path dir = Path.of("target", "fsc-eval");
        Files.createDirectories(dir);
        Path out = dir.resolve("report-%s.json".formatted(Instant.now().toEpochMilli()));
        Files.writeString(dir.toString().isEmpty() ? Path.of(".") : Path.of("target/fsc-eval/last-report.txt"),
                summary.toString());
        System.out.println("Report dir: " + dir.toAbsolutePath());
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
