package com.fsc.cases.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线确定性 embedding：字符 n-gram 哈希 → 固定维度向量。
 *
 * <p>目的是让 RAG 全链路（embed → store → retrieve → 增强 prompt）在**无网络、无需下载任何模型**的
 * 前提下可跑、可进 CI。它做的是<b>词法近似</b>匹配，不是语义匹配。
 *
 * <p>生产环境应替换为真实语义 embedding 模型（如 bge、text-embedding-3 等），
 * 只要实现同一个 {@link EmbeddingModel} 接口即可无缝替换，其余 RAG 装配代码无需改动。
 */
public final class HashingEmbeddingModel implements EmbeddingModel {

    /** 向量维度 */
    private static final int DIMENSION = 256;

    /** 参与哈希的字符 n-gram 长度 */
    private static final int[] NGRAM_SIZES = {2, 3};

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return Response.from(Embedding.from(vectorize(textSegment.text())));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>(textSegments.size());
        for (TextSegment segment : textSegments) {
            embeddings.add(Embedding.from(vectorize(segment.text())));
        }
        return Response.from(embeddings);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String modelName() {
        return "offline-hashing";
    }

    /** 字符 n-gram 哈希落桶 + L2 归一化（纯确定性，无随机源） */
    private static float[] vectorize(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        String normalized = text.toLowerCase();
        for (int n : NGRAM_SIZES) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n);
                int bucket = Math.floorMod(gram.hashCode(), DIMENSION);
                vector[bucket] += 1.0f;
            }
        }
        return l2Normalize(vector);
    }

    private static float[] l2Normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float v : vector) {
            sumSquares += v * v;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm == 0.0) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
