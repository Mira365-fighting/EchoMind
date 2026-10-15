package com.example.echomind.rag;

/**
 * 已入库文档的登记信息（对应 {@code data/documents.json} 里的一条记录）。
 *
 * <p>为什么不直接查 Milvus 列表？因为 {@code MilvusEmbeddingStore} 只提供
 * "按向量搜 / 按过滤表达式删"，没有"列出所有文件"这种聚合能力。
 * 所以自己维护一份登记簿，顺带把入库时用的是哪个 Embedding 模型和维度记下来，
 * 重启后就能立刻发现"库里的维度和现在的模型对不上"。
 *
 * @param fileName      文件名
 * @param fileType      pdf / markdown / text
 * @param size          原始文件字节数
 * @param chunkCount    切成了几片
 * @param charCount     解析出的纯文本总字符数
 * @param ingestedAt    入库时间（ISO-8601）
 * @param embeddingModel 入库时用的 Embedding 模型名
 * @param dimension     入库时的向量维度
 */
public record DocumentInfo(
        String fileName,
        String fileType,
        long size,
        int chunkCount,
        int charCount,
        String ingestedAt,
        String embeddingModel,
        int dimension) {
}
