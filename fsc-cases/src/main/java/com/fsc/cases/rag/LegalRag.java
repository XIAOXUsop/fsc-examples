package com.fsc.cases.rag;

import com.fsc.cases.data.LegalIndex;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * 法规知识库的 RAG 装配：把 {@link LegalIndex} 的条文向量化后灌入内存向量库，并暴露检索器。
 *
 * <p>原文以 {@code LegalArticle} 常量形式内置（保证离线可跑），检索走标准 LangChain4j
 * {@link ContentRetriever} 链路；后续要换成真实向量库（pgvector 等）只需替换 {@link EmbeddingStore} 实现。
 */
public final class LegalRag {

    /** 单次检索返回的条文数量 */
    private static final int MAX_RESULTS = 2;

    private final EmbeddingStore<TextSegment> store;
    private final ContentRetriever contentRetriever;

    private LegalRag(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> store) {
        this.store = store;
        this.contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(MAX_RESULTS)
                .build();
    }

    /** 用默认的离线确定性 embedding 构建 */
    public static LegalRag build() {
        return build(new HashingEmbeddingModel());
    }

    /** 用指定 embedding 模型构建（便于替换为真实语义模型） */
    public static LegalRag build(EmbeddingModel embeddingModel) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        for (LegalIndex.LegalArticle article : LegalIndex.ARTICLES) {
            Metadata metadata = new Metadata();
            metadata.put("evidenceId", article.evidenceId());
            metadata.put("title", article.title());
            TextSegment segment = TextSegment.from(article.title() + "\n" + article.body(), metadata);
            store.add(embeddingModel.embed(segment).content(), segment);
        }
        return new LegalRag(embeddingModel, store);
    }

    public EmbeddingStore<TextSegment> store() {
        return store;
    }

    public ContentRetriever contentRetriever() {
        return contentRetriever;
    }
}
