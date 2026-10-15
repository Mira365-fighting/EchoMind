package com.example.echomind.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.markdown.MarkdownDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * v2.0 上半段流水线：<b>解析 → 切片 → 向量化 → 入库</b>。
 *
 * <pre>
 *   PDF / Markdown / TXT
 *        │  ApachePdfBoxDocumentParser / MarkdownDocumentParser / 直接读文本
 *        ▼
 *   Document（纯文本 + 元数据）
 *        │  DocumentSplitters.recursive(400, 80)   按 token 递归切，相邻留 80 token 重叠
 *        ▼
 *   List&lt;TextSegment&gt;   每片补上 file_name / chunk_index 等元数据
 *        │  EmbeddingModel.embedAll()   本地 BGE-small-zh-v1.5，512 维，批量编码
 *        ▼
 *   List&lt;Embedding&gt;  ──addAll──▶  Milvus collection（维度 512，COSINE）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** 元数据键名。过滤删除时要用同一个字符串，所以抽成常量。 */
    public static final String META_FILE_NAME = "file_name";
    public static final String META_FILE_TYPE = "file_type";
    public static final String META_CHUNK_INDEX = "chunk_index";
    public static final String META_DOC_ID = "doc_id";

    private static final List<String> PDF_EXT = List.of(".pdf");
    private static final List<String> MARKDOWN_EXT = List.of(".md", ".markdown");
    private static final List<String> TEXT_EXT = List.of(".txt", ".text", ".log", ".csv", ".json", ".yml", ".yaml");

    private final EmbeddingModel embeddingModel;
    private final VectorStoreProvider vectorStoreProvider;
    private final DocumentRegistry registry;
    private final RagProperties properties;

    /**
     * 上传一个文档：解析 → 切片 → 向量化 → 入库。
     *
     * <p>同一个文件名重复上传会<b>先删掉旧切片再写入</b>，避免出现两份内容打架。
     */
    public IngestResult ingest(String originalFileName, byte[] bytes) {
        long start = System.currentTimeMillis();
        String fileName = sanitizeFileName(originalFileName);
        long maxBytes = properties.getMaxFileSize().toBytes();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空：" + fileName);
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "文件超过大小上限（" + properties.getMaxFileSize() + "）：" + fileName);
        }

        // ---------- 1) 解析 ----------
        String fileType = detectType(fileName);
        String text = parse(fileName, fileType, bytes);
        if (text.isBlank()) {
            throw new IllegalArgumentException("解析后没有提取到任何文本（可能是扫描版 PDF，需要 OCR）：" + fileName);
        }

        // ---------- 2) 切片 ----------
        Document document = Document.from(text);
        DocumentSplitter splitter = DocumentSplitters.recursive(
                properties.getMaxSegmentSize(), properties.getMaxOverlapSize());
        List<TextSegment> rawSegments = splitter.split(document);
        if (rawSegments.isEmpty()) {
            throw new IllegalArgumentException("切片结果为空：" + fileName);
        }

        String docId = UUID.randomUUID().toString();
        String ingestedAt = OffsetDateTime.now().toString();
        List<TextSegment> segments = new ArrayList<>(rawSegments.size());
        int totalChars = 0;
        for (int i = 0; i < rawSegments.size(); i++) {
            String segmentText = rawSegments.get(i).text();
            totalChars += segmentText.length();
            // 每片都带上"来自哪个文件的第几片"，检索命中后才能给出出处
            Metadata metadata = new Metadata()
                    .put(META_FILE_NAME, fileName)
                    .put(META_FILE_TYPE, fileType)
                    .put(META_CHUNK_INDEX, i)
                    .put(META_DOC_ID, docId);
            segments.add(TextSegment.from(segmentText, metadata));
        }

        // ---------- 3) 向量化 ----------
        long embedStart = System.currentTimeMillis();
        Response<List<Embedding>> embedded = embeddingModel.embedAll(segments);
        List<Embedding> vectors = embedded.content();
        if (vectors == null || vectors.size() != segments.size()) {
            throw new IllegalStateException("向量化结果数量与切片数量不一致："
                    + (vectors == null ? "null" : vectors.size()) + " vs " + segments.size());
        }
        long embedCost = System.currentTimeMillis() - embedStart;

        // 维度一致性检查：Embedding 输出维度必须和向量库声明的维度一致，否则 Milvus 写入会失败
        int actualDimension = vectors.get(0).dimension();
        if (actualDimension != vectorStoreProvider.dimension()) {
            throw new IllegalStateException(String.format(
                    "维度不一致：Embedding 输出 %d 维，但当前向量库声明的是 %d 维。"
                            + "请让 Milvus collection 维度与 Embedding 模型保持一致（删掉旧 collection 会按新维度重建）。",
                    actualDimension, vectorStoreProvider.dimension()));
        }

        // ---------- 4) 入库 ----------
        // 同名文件先清掉旧切片，保证幂等（重复上传不会出现两份）
        if (registry.find(fileName).isPresent()) {
            log.info("RAG | 检测到同名文档，先删除旧切片：{}", fileName);
            purgeVectors(fileName);
        }
        if (segments.size() == 1) {
            vectorStoreProvider.store().add(vectors.get(0), segments.get(0));
        } else {
            vectorStoreProvider.store().addAll(vectors, segments);
        }

        registry.put(new DocumentInfo(fileName, fileType, bytes.length, segments.size(),
                totalChars, ingestedAt, embeddingModel.modelName(), actualDimension));

        long cost = System.currentTimeMillis() - start;
        log.info("RAG | 入库完成：{}（{} 字符 → {} 片 → {} 维向量），向量化 {} ms，总计 {} ms，写入 {}",
                fileName, totalChars, segments.size(), actualDimension, embedCost, cost,
                vectorStoreProvider.label());

        return new IngestResult(fileName, fileType, bytes.length, totalChars, segments.size(),
                segments.size(), actualDimension, properties.getMaxSegmentSize(),
                properties.getMaxOverlapSize(), vectorStoreProvider.label(), cost);
    }

    public List<DocumentInfo> list() {
        return registry.list();
    }

    /** 按文件名删除：先清向量，再清登记簿。 */
    public boolean delete(String fileName) {
        if (registry.find(fileName).isEmpty()) {
            return false;
        }
        purgeVectors(fileName);
        registry.remove(fileName);
        log.info("RAG | 已删除文档：{}", fileName);
        return true;
    }

    /** 清空所有文档（向量 + 登记簿）。 */
    public int clearAll() {
        int count = registry.count();
        vectorStoreProvider.store().removeAll();
        registry.clear();
        log.info("RAG | 已清空向量库与登记簿，共 {} 个文档", count);
        return count;
    }

    /** 只按元数据过滤删除，不动登记簿 */
    private void purgeVectors(String fileName) {
        vectorStoreProvider.store().removeAll(
                MetadataFilterBuilder.metadataKey(META_FILE_NAME).isEqualTo(fileName));
    }

    // ==================== 解析 ====================

    private String parse(String fileName, String fileType, byte[] bytes) {
        return switch (fileType) {
            case "pdf" -> new ApachePdfBoxDocumentParser().parse(new ByteArrayInputStream(bytes)).text();
            case "markdown" -> new MarkdownDocumentParser().parse(new ByteArrayInputStream(bytes)).text();
            default -> new String(bytes, StandardCharsets.UTF_8);
        };
    }

    private String detectType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (PDF_EXT.stream().anyMatch(lower::endsWith)) {
            return "pdf";
        }
        if (MARKDOWN_EXT.stream().anyMatch(lower::endsWith)) {
            return "markdown";
        }
        if (TEXT_EXT.stream().anyMatch(lower::endsWith)) {
            return "text";
        }
        throw new IllegalArgumentException(
                "不支持的文件类型：" + fileName + "（当前支持 .pdf / .md / .txt）");
    }

    /** 去掉路径部分，避免有人用 "../../x" 这种文件名 */
    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("非法文件名：" + fileName);
        }
        return name;
    }
}
