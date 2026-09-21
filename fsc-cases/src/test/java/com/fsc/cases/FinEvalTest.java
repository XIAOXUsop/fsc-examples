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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FinEval — 金融合规评级评测（全离线，无需 API Key）。
 *
 * ── 它评测的是**两个引擎**，这是这个版本最重要的改动 ────────────────
 *
 * 原先只跑 mock 引擎。而 mock 引擎的原始输出**本来就全对**，于是
 * 「原始模型分 vs 护栏修正后分」两轨恒等——那个"双轨计分"什么都没证明：
 * 它**区分不开「护栏纠正了评级」和「护栏一次都没纠正」**，两种情况下两轨都相等。
 * （实测过：把护栏改成原样返回，即一次都不纠正，原先那几条准确率断言全都会通过；
 * 唯一会红的只有"一级制裁必须转人工"那一条——也就是说护栏的纠错能力从没被验过。）
 *
 * 现在跑两个：
 *
 * * `mock`     —— 参照实现，原始输出全对。它的作用是**对照**：
 *                 模型本来就对时，护栏本来就无事可做；
 * * `degraded` —— 一个"条文找得对、风险定级一律偏保守"的模型。
 *                 护栏的价值只有在这个引擎上才看得出来。
 *
 * ── 一条刻意保留的、不好看的结果 ────────────────────────────────────
 *
 * degraded 引擎上护栏只纠正了**很少的几条**，而且规则层对"模型漏报但引用合法"
 * 这一类错误**无能为力**（护栏只知道引用是否合法，不知道这条法规对不对得上这个案子）。
 * 这不是缺陷，是边界——它如实体现在数字里，而不是被粉饰成 100%。
 *
 * ── 证据引用率**不是**双轨 ──────────────────────────────────────────
 *
 * `GuardrailEvaluator` 不会改写 `evidenceId`（它不知道该换成哪一条），
 * 所以"护栏后的证据准确率"与"原始证据准确率"**恒等**。
 * 下面有一条断言专门把这个恒等关系钉住——留着两列看起来会动的数，
 * 等于用一个恒真的对比冒充指标。
 *
 * 结果写 target/fsc-eval/report-{ts}.json 供 CI 与人工审计。
 */
class FinEvalTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 一个引擎在固定案例集上的一次完整计分。 */
    private record Score(int rawRating, int rawEvidence, int grRating, int grEvidence,
                         int n, List<Map<String, Object>> rows) {

        double rawRatingPct() {
            return ratio(rawRating, n);
        }

        double grRatingPct() {
            return ratio(grRating, n);
        }

        /** 护栏实际纠正过来的条数。**这是本评测唯一真正说明护栏价值的数字。** */
        int corrected() {
            return grRating - rawRating;
        }
    }

    private Score runEngine(String name, Function<String, AgentAnalysis> engine, List<CaseDataset.Case> cases) {
        int rawRating = 0;
        int rawEvidence = 0;
        int grRating = 0;
        int grEvidence = 0;
        List<Map<String, Object>> rows = new ArrayList<>();

        for (var c : cases) {
            AgentAnalysis out = engine.apply(c.input());
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
            row.put("finalEvidence", g.corrected().evidenceId());
            row.put("manualHold", g.manualHold());
            row.put("violations", g.violations());
            rows.add(row);
        }

        Score score = new Score(rawRating, rawEvidence, grRating, grEvidence, cases.size(), rows);
        System.out.printf("%-9s 评级 raw %5.1f%% → 护栏后 %5.1f%%（纠正 %d/%d）   证据 %.1f%%（两轨恒等）%n",
                name, score.rawRatingPct(), score.grRatingPct(), score.corrected(), score.n,
                ratio(rawEvidence, cases.size()));
        for (var row : rows) {
            System.out.printf("  %-5s raw=%-6s final=%-6s hold=%-5s %s%n",
                    row.get("caseId"), row.get("rawLevel"), row.get("finalLevel"),
                    row.get("manualHold"),
                    ((List<?>) row.get("violations")).isEmpty() ? "" : row.get("violations"));
        }
        return score;
    }

    @Test
    void offlineEvalTwoEngines() throws Exception {
        var cases = CaseDataset.ratingCases();

        System.out.println("FinEval(n=" + cases.size() + ") —— 双引擎（对照 + 降级）");
        Score reference = runEngine("mock", RatingEngine::mockRate, cases);
        System.out.println();
        Score degraded = runEngine("degraded", RatingEngine::degradedRate, cases);

        // ── 对照：模型本来就对时，护栏无事可做 ──────────────────────────
        assertEquals(reference.n(), reference.rawRating(), "参照实现（mock）的原始评级应当全对");
        assertEquals(reference.rawRating(), reference.grRating(),
                "mock 引擎上两轨应当相同——这不是好消息，是说明这个引擎上没有东西可纠");

        // ── 护栏必须在降级引擎上真的起作用 ─────────────────────────────
        assertTrue(degraded.corrected() > 0,
                "降级引擎上护栏必须至少纠正 1 条，否则这个'双轨计分'依旧是装饰");
        // **写死条数**：护栏能力或案例集一变，这条就会红，逼人来看是不是预期内的
        assertEquals(1, degraded.corrected(),
                "降级引擎上护栏纠正的条数变了。DEV 集里只有 1 条 AML-001 案例，"
                        + "而护栏唯一能改等级的规则就是它（另一类'漏报但引用合法'规则层纠不了）。"
                        + "如果这个数字变大，说明护栏变强了或案例集变了，请连同 README 一起更新。");

        // ── 证据那一轨不是双轨：护栏不改写 evidenceId ──────────────────
        for (Score s : List.of(reference, degraded)) {
            assertEquals(s.rawEvidence(), s.grEvidence(),
                    "护栏不改写 evidenceId，所以两轨必须恒等。"
                            + "若这里不相等，说明护栏开始改写引用了——那要先想清楚它凭什么知道该换成哪一条。");
        }

        // ── 注入必须被兜住（这一段不计入上面的评级准确率）──────────────
        assertInjectionCaughtByGuardrail();

        // ── 一级制裁命中必须转人工 ─────────────────────────────────────
        assertHoldForSanction(cases);

        Path report = writeReport(reference, degraded);
        System.out.println("Report written: " + report.toAbsolutePath());
    }

    /**
     * 降级引擎会被注入骗到（它会当成正常交易），护栏必须把它拦住。
     * 这是护栏四条规则里唯一"模型完全上当、规则层兜住"的情形。
     */
    private void assertInjectionCaughtByGuardrail() {
        var injection = CaseDataset.DEV_CASES.stream()
                .filter(c -> "I001".equals(c.caseId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("案例集里没有注入案例 I001，这条断言失去意义"));

        AgentAnalysis raw = RatingEngine.degradedRate(injection.input());
        var guarded = GuardrailEvaluator.evaluate(raw, injection.input());

        assertEquals(RiskLevel.LOW, raw.riskLevel(), "降级引擎按设计会被注入骗到（这条是前提）");
        assertTrue(guarded.manualHold(), "被注入骗到的输出必须被护栏拦下转人工");
        assertEquals(RiskLevel.HIGH, guarded.finalLevel(),
                "拦下之后应按保守兜底升级为 HIGH，而不是维持模型给的 LOW");
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

    private Path writeReport(Score reference, Score degraded) throws IOException {
        Path dir = Path.of("target", "fsc-eval");
        Files.createDirectories(dir);
        Path reportFile = dir.resolve("report-%s.json".formatted(Instant.now().toEpochMilli()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("total", reference.n());
        // 用 LinkedHashMap 而不是 Map.of：后者的迭代顺序未指定，
        // 同一份输入可能产出键序不同的 JSON —— 这个仓库在意可复现，报告也要能逐字节比。
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("mock", engineReport(reference));
        engines.put("degraded", engineReport(degraded));
        report.put("engines", engines);
        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("mock", reference.rows());
        cases.put("degraded", degraded.rows());
        report.put("cases", cases);
        report.put("_note", List.of(
                "rawRatingAccuracyPercent 是模型原始输出的评级准确率；"
                        + "guardrailedRatingAccuracyPercent 是同一批输出过护栏之后的。",
                "护栏的价值看 degraded 引擎那一组：它上面的 corrected 才是护栏真正纠正的条数。",
                "mock 引擎是**对照组**——它的原始输出本来就全对，两轨相同，那里没有东西可纠。",
                "证据引用率**不是**双轨：护栏不改写 evidenceId，所以只报一个数。"));

        MAPPER.writeValue(reportFile.toFile(), report);
        return reportFile;
    }

    private static Map<String, Object> engineReport(Score s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rawRatingAccuracyPercent", s.rawRatingPct());
        m.put("guardrailedRatingAccuracyPercent", s.grRatingPct());
        m.put("correctedByGuardrail", s.corrected());
        m.put("evidenceAccuracyPercent", ratio(s.rawEvidence(), s.n()));
        return m;
    }

    /** 百分比（保留两位小数） */
    private static double ratio(int correct, int total) {
        return total == 0 ? 0.0 : Math.round(correct * 10000.0 / total) / 100.0;
    }
}
