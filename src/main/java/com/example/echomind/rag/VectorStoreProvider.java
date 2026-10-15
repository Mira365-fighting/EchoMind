package com.example.echomind.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.client.MilvusServiceClient;

/**
 * 向量库句柄 + 它的"身份信息"。
 *
 * <p>做成一个 holder 而不是直接暴露 {@link EmbeddingStore}，是为了让
 * {@code /api/rag/status} 和启动日志能说清楚：<b>现在到底用的是哪个向量库、维度多少</b>。
 * "Milvus 集合维度必须和 Embedding 维度一致"这个约束，靠这里把 dimension 显式带出来落地。
 *
 * @param store        真正干活的向量库（Milvus 或内存）
 * @param type         {@code milvus} / {@code memory}
 * @param dimension    向量维度，必须等于 Embedding 模型输出的维度
 * @param collection   集合名（内存库时为 "-"）
 * @param detail       人话说明，比如为什么降级到了内存
 * @param milvusClient Milvus 客户端（内存向量库时为 {@code null}）。
 *                     由 {@code DocumentRegistry} 在启动时用来核对「登记簿 vs 库内实际数据」。
 *                     注意：这个 client 的生命周期归 {@link EmbeddingStore} 所有，别在这里关它。
 */
public record VectorStoreProvider(
        EmbeddingStore<TextSegment> store,
        String type,
        int dimension,
        String collection,
        String detail,
        MilvusServiceClient milvusClient) {

    public boolean isMilvus() {
        return "milvus".equals(type);
    }

    /** 给日志/接口用的中文名 */
    public String label() {
        return isMilvus() ? "Milvus" : "进程内存（降级）";
    }

    /** 手写 toString，避免默认实现把 Milvus 客户端整个对象打出来刷屏 */
    @Override
    public String toString() {
        return "VectorStoreProvider[type=%s, dimension=%d, collection=%s]"
                .formatted(type, dimension, collection);
    }
}

