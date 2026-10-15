package com.example.echomind.controller;

import com.example.echomind.memory.ResilientChatMemoryStore;
import com.example.echomind.rag.DocumentInfo;
import com.example.echomind.rag.DocumentRegistry;
import com.example.echomind.rag.DocumentService;
import com.example.echomind.rag.IngestResult;
import com.example.echomind.rag.RagProperties;
import com.example.echomind.rag.RagStatus;
import com.example.echomind.rag.RetrievalService;
import com.example.echomind.rag.RetrievedChunk;
import com.example.echomind.rag.VectorStoreProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * v2.0 知识库接口。
 *
 * <ul>
 *     <li>{@code POST   /api/documents}        —— 上传文档：解析 → 切片 → 向量化 → 入库</li>
 *     <li>{@code GET    /api/documents}        —— 已入库文档列表</li>
 *     <li>{@code DELETE /api/documents}        —— 按文件名删除</li>
 *     <li>{@code DELETE /api/documents/all}    —— 清空知识库</li>
 *     <li>{@code GET    /api/rag/status}       —— RAG 状态（向量库、维度、文档数…）</li>
 *     <li>{@code GET    /api/rag/search}       —— <b>检索调试</b>：直接看 TopK 命中了什么（不调用大模型）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final RetrievalService retrievalService;
    private final VectorStoreProvider vectorStoreProvider;
    private final DocumentRegistry documentRegistry;
    private final RagProperties ragProperties;
    /** 只用来读模型名展示在状态里 */
    private final EmbeddingModel embeddingModel;
    /** 只用来展示会话记忆当前落在 Redis 还是降级到了进程内存 */
    private final ResilientChatMemoryStore chatMemoryStore;

    /** 上传并入库。返回切片数、向量维度、耗时等实测数字，方便前端展示和自检。 */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("收到上传：{}（{} 字节）", fileName, file.getSize());
        return documentService.ingest(fileName, file.getBytes());
    }

    /** 已入库文档列表 */
    @GetMapping("/documents")
    public List<DocumentInfo> list() {
        return documentService.list();
    }

    /** 按文件名删除（向量 + 登记簿一起清） */
    @DeleteMapping("/documents")
    public Map<String, Object> delete(@RequestParam String fileName) {
        boolean removed = documentService.delete(fileName);
        return Map.of("fileName", fileName, "deleted", removed);
    }

    /** 清空知识库 */
    @DeleteMapping("/documents/all")
    public Map<String, Object> clearAll() {
        int count = documentService.clearAll();
        return Map.of("deletedDocuments", count);
    }

    /** RAG 当前状态 */
    @GetMapping("/rag/status")
    public RagStatus status() {
        return new RagStatus(
                ragProperties.isEnabled(),
                retrievalService.isReady(),
                embeddingModel.modelName(),
                vectorStoreProvider.label(),
                vectorStoreProvider.dimension(),
                vectorStoreProvider.collection(),
                vectorStoreProvider.detail(),
                chatMemoryStore.activeStore(),
                documentRegistry.count(),
                documentRegistry.totalChunks(),
                ragProperties.getTopK(),
                ragProperties.getMinScore(),
                ragProperties.getMaxSegmentSize(),
                ragProperties.getMaxOverlapSize());
    }

    /**
     * 检索调试接口：只做「问题 → 向量检索」这一步，不调用大模型。
     * 用来验证"检索质量到底行不行" —— 如果这里都搜不出正确的片段，那 Prompt 再怎么调都没用。
     *
     * @param question 查询文本
     * @param topK     临时覆盖 TopK（不传用配置值）
     * @param minScore 临时覆盖相似度下限（不传用配置值）
     */
    @GetMapping("/rag/search")
    public List<RetrievedChunk> search(@RequestParam String question,
                                       @RequestParam(required = false) Integer topK,
                                       @RequestParam(required = false) Double minScore) {
        return retrievalService.retrieve(question, topK, minScore);
    }
}
