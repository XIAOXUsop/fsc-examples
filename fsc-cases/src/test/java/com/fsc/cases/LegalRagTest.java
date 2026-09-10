package com.fsc.cases;

import com.fsc.cases.data.LegalIndex;
import com.fsc.cases.rag.HashingEmbeddingModel;
import com.fsc.cases.rag.LegalRag;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 法规 RAG 的离线测试（不调用任何外部模型/网络）。
 *
 * <p>说明：这里用的是哈希 embedding，检索本质是<b>词法近似</b>而非语义匹配，
 * 因此断言只校验"能命中关键词对应的条文"，不承诺检索质量达到生产水准。
 */
class LegalRagTest {

    private static final String EVIDENCE_ID_KEY = "evidenceId";

    @Test
    void embeddingIsDeterministicAndCorrectlySized() {
        HashingEmbeddingModel model = new HashingEmbeddingModel();

        Embedding first = model.embed("一级制裁名单").content();
        Embedding second = model.embed("一级制裁名单").content();

        assertArrayEquals(first.vector(), second.vector(), "同文本两次 embedding 必须一致");
        assertEquals(256, model.dimension());
        assertEquals(256, first.dimension(), "向量维度必须等于声明的 dimension()");
    }

    @Test
    void differentTextsProduceDifferentEmbeddings() {
        HashingEmbeddingModel model = new HashingEmbeddingModel();

        assertFalse(Arrays.equals(
                        model.embed("制裁名单").content().vector(),
                        model.embed("正常客户基线").content().vector()),
                "不同文本不应产生相同向量");
    }

    @Test
    void storeIngestsEveryArticle() {
        LegalRag rag = LegalRag.build();

        for (LegalIndex.LegalArticle article : LegalIndex.ARTICLES) {
            List<Content> contents = rag.contentRetriever().retrieve(Query.from(article.title()));
            assertTrue(contents.stream().anyMatch(c -> article.evidenceId().equals(evidenceIdOf(c))),
                    "以条文自身标题检索应命中 " + article.evidenceId());
        }
    }

    @Test
    void retrievesSanctionArticleByKeyword() {
        LegalRag rag = LegalRag.build();

        List<Content> contents = rag.contentRetriever().retrieve(Query.from("一级制裁名单"));

        assertFalse(contents.isEmpty(), "检索不应为空");
        assertTrue(contents.stream().anyMatch(c -> "AML-001".equals(evidenceIdOf(c))),
                "查询'一级制裁名单'应命中 AML-001，实际命中："
                        + contents.stream().map(LegalRagTest::evidenceIdOf).toList());
    }

    private static String evidenceIdOf(Content content) {
        return content.textSegment().metadata().getString(EVIDENCE_ID_KEY);
    }
}
