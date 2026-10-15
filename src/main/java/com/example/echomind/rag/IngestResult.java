package com.example.echomind.rag;

/**
 * 一次上传入库的结果，直接回给前端展示"解析 -> 切片 -> 向量化"的实测数字。
 *
 * @param fileName       文件名
 * @param fileType       识别出的类型
 * @param size           原始字节数
 * @param charCount      解析出的纯文本字符数
 * @param chunkCount     切片数量
 * @param vectorCount    实际写入向量库的向量条数（正常等于 chunkCount）
 * @param dimension      向量维度
 * @param maxSegmentSize 切片窗口大小（token）
 * @param maxOverlapSize 切片重叠大小（token）
 * @param vectorStore    写入到哪个向量库
 * @param costMs         整个流程耗时（毫秒）
 */
public record IngestResult(
        String fileName,
        String fileType,
        long size,
        int charCount,
        int chunkCount,
        int vectorCount,
        int dimension,
        int maxSegmentSize,
        int maxOverlapSize,
        String vectorStore,
        long costMs) {
}
