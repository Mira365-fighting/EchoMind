package com.example.echomind.rag;

/**
 * {@code GET /api/rag/status} 的返回体，一眼看清 RAG 现在处于什么状态。
 *
 * @param enabled          配置里的总开关
 * @param ready            是否真正能检索（开关打开 <b>且</b> 库里已有内容）
 * @param embeddingModel   Embedding 模型名
 * @param vectorStore      当前用的是哪个向量库："Milvus" 或 "进程内存（降级）"
 * @param dimension        向量维度，应当恒等于 Embedding 模型输出维度
 * @param collection       Milvus 集合名（内存库为 "-"）
 * @param detail           向量库说明，降级时会写明原因
 * @param memoryStore      会话记忆当前的落点："Redis（未探测）" / "Redis" / "进程内存（降级）"
 * @param documentCount    已入库文档数
 * @param chunkCount       已入库切片总数
 * @param topK             检索返回条数
 * @param minScore         相似度下限（注意是 (cosine+1)/2，不是原始余弦值）
 * @param maxSegmentSize   切片窗口大小（token）
 * @param maxOverlapSize   切片重叠大小（token）
 */
public record RagStatus(
        boolean enabled,
        boolean ready,
        String embeddingModel,
        String vectorStore,
        int dimension,
        String collection,
        String detail,
        String memoryStore,
        int documentCount,
        int chunkCount,
        int topK,
        double minScore,
        int maxSegmentSize,
        int maxOverlapSize) {
}
