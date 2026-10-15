package com.example.echomind.rag;

/**
 * 一条检索结果。会被推给前端展示（"检索到的上下文"面板），也是回答的引用来源。
 *
 * @param index      第几条，从 1 开始（对应 Prompt 里的 [1] [2]）
 * @param score      LangChain4j 相似度分数，范围 0~1，等于 (余弦相似度 + 1) / 2
 * @param fileName   来源文件名
 * @param chunkIndex 该文件内的第几个切片，从 0 开始
 * @param text       切片正文
 */
public record RetrievedChunk(
        int index,
        double score,
        String fileName,
        int chunkIndex,
        String text) {
}
