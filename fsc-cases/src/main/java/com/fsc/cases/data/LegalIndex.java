package com.fsc.cases.data;

import java.util.List;
import java.util.Map;

/**
 * 内置 AML 法规片段（evidenceId → 条文）。生产应接入向量库，示例内置保离线可跑。
 */
public final class LegalIndex {

    private LegalIndex() {
    }

    public static final record LegalArticle(String evidenceId, String title, String body) {
    }

    public static final List<LegalArticle> ARTICLES = List.of(
            new LegalArticle("AML-001", "一级制裁名单命中", """
                    与 OFAC / 联合国 / 欧盟制裁名单精确命中的客户，无论交易金额大小，
                    一律评为 HIGH 风险，且必须转人工复核，不得自动放行。
                    """),
            new LegalArticle("AML-002", "可疑交易结构化拆分", """
                    客户在短期内出现多笔低于报告阈值的等额/近似等额存取（拆分交易），
                    应评为 HIGH 或 MEDIUM 风险，并记录拆分特征。
                    """),
            new LegalArticle("AML-003", "夜间跨境大额", """
                    单笔等值 50 万人民币以上且发生于 00:00-06:00 的跨境交易，
                    视为可疑交易特征，需在报告中显式提及。
                    """),
            new LegalArticle("AML-004", "正常客户基线", """
                    无制裁命中、无拆分、无夜间大额跨境交易的客户按常规流程处理，
                    评为 LOW 风险并出具常规报告。
                    """),
            new LegalArticle("AML-005", "证据引用要求", """
                    任何风险评级结论必须引用至少一条本索引内法规（通过 evidenceId），
                    未能引用的结论视同无依据，进入人工复核。
                    """)
    );

    /** evidenceId 白名单 */
    public static final java.util.Set<String> VALID_EVIDENCE_IDS =
            ARTICLES.stream().map(LegalArticle::evidenceId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public static LegalArticle byId(String id) {
        return ARTICLES.stream().filter(a -> a.evidenceId().equals(id)).findFirst().orElse(null);
    }
}
