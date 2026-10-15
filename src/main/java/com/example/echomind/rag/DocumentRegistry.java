package com.example.echomind.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档登记簿：记录"哪些文件已经进了向量库"，落盘成 JSON，重启不丢。
 *
 * <p>用 Jackson 2 而不是容器里的 ObjectMapper —— Spring Boot 4 已经切到 Jackson 3
 * （{@code tools.jackson}），而 LangChain4j 依赖的是 Jackson 2，这里跟着 LangChain4j 走最稳。
 *
 * <p><b>重要不变式</b>：登记簿是"向量库内容的镜像"。磁盘上的登记簿可能来自上一次运行，
 * 而向量库是活的，两者随时可能对不上。所以 {@link #load()} 有两道闸：
 * <ol>
 *     <li><b>向量库不是 Milvus</b>（本次用的内存向量库，内容是空的）→ 直接丢弃登记簿；</li>
 *     <li><b>是 Milvus 但数量对不上</b> → 以 Milvus 为准，丢弃登记簿并提示重新上传。</li>
 * </ol>
 * 两道闸都是为了同一个目标：宁可列表暂时是空的，也不要让 {@code /api/rag/status}
 * 谎报"已入库 N 篇"，而检索其实什么都搜不到。
 */
@Slf4j
@Component
public class DocumentRegistry {

    private final Path file;
    private final VectorStoreProvider vectorStoreProvider;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();

    public DocumentRegistry(RagProperties properties, VectorStoreProvider vectorStoreProvider) {
        this.file = Path.of(properties.getRegistryPath()).toAbsolutePath();
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @PostConstruct
    void load() {
        if (!vectorStoreProvider.isMilvus()) {
            discardStaleRegistry();
            return;
        }
        if (!Files.isRegularFile(file)) {
            log.info("RAG | 文档登记簿不存在，跳过加载：{}", file);
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return;
            }
            List<DocumentInfo> list = json.readValue(raw, new TypeReference<List<DocumentInfo>>() {
            });
            list.forEach(info -> documents.put(info.fileName(), info));
            log.info("RAG | 已从登记簿恢复 {} 个文档：{}", documents.size(), file);
            reconcileWithMilvus();
        } catch (Exception e) {
            // 登记簿坏了不应该拦着应用启动，顶多是列表不准
            log.warn("RAG | 登记簿读取失败（忽略，不影响检索）：{}，原因={}", file, e.getMessage());
        }
    }

    /**
     * 把登记簿和 Milvus 里的<b>实际数据量</b>对一遍。
     *
     * <p>为什么光判断"向量库是不是 Milvus"还不够：登记簿是磁盘上的（可能来自上一次运行），
     * 而 Milvus 是活的，两者的内容可以完全无关。真实遇到过的两种情况：
     * <ul>
     *     <li>上次运行时 Milvus 没起来，降级成内存向量库，文档只进了内存；这次 Milvus 起来了，
     *         集合是新建的空集合，但登记簿还留着上次那几篇 → 状态接口显示"已入库 3 篇"，
     *         检索却只能搜到这次新上传的那一篇。</li>
     *     <li>Milvus 里的集合被重建 / 清空过（换模型、手动 drop），登记簿不知情。</li>
     * </ul>
     * 两种情况都会让 {@code /api/rag/status} 谎报，而"状态接口说谎"比"没有状态接口"更糟。
     *
     * <p>所以以 Milvus 为准：数量对不上就丢弃登记簿（只是列表不准，不动 Milvus 里的数据），
     * 并提示重新上传。取不到统计时保守处理 —— 不动登记簿，避免误删。
     */
    private void reconcileWithMilvus() {
        Long rowCount = milvusRowCount();
        if (rowCount == null) {
            return;
        }
        int tracked = totalChunks();
        if (rowCount == tracked) {
            if (tracked > 0) {
                log.info("RAG | 登记簿与 Milvus 核对一致：{} 篇 / {} 个切片",
                        documents.size(), tracked);
            }
            return;
        }
        log.warn("""
                RAG | 登记簿与 Milvus 对不上，已丢弃登记簿（以 Milvus 为准）。
                      登记簿记录 {} 篇 / {} 个切片；Milvus 集合「{}」实际只有 {} 条向量。
                      常见原因：上次运行时 Milvus 没起来（降级成了内存向量库，文档没进 Milvus），
                      或者 Milvus 里的集合被重建 / 清空过。
                      影响：知识库列表已清空，但 Milvus 里的数据没动 —— 请重新上传文档。""",
                documents.size(), tracked, vectorStoreProvider.collection(), rowCount);
        documents.clear();
        flush();
    }

    /**
     * 读 Milvus 集合的 row_count。
     *
     * <p>{@code withFlush(true)} 是刻意的：不 flush 的话刚写入的向量可能还没计入统计，
     * 会被误判成"登记簿多记了"而把好数据清掉。宁可启动慢一点，也不要误删。
     *
     * @return 行数；读不到（内存向量库 / 网络异常 / 返回结构变化）时返回 {@code null}
     */
    private Long milvusRowCount() {
        MilvusServiceClient client = vectorStoreProvider.milvusClient();
        if (client == null) {
            return null;
        }
        try {
            R<GetCollectionStatisticsResponse> stats = client.getCollectionStatistics(
                    GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(vectorStoreProvider.collection())
                            .withFlush(true)
                            .build());
            if (stats.getException() != null || stats.getData() == null) {
                log.warn("RAG | 读取 Milvus 统计失败，跳过登记簿核对：{}", stats.getMessage());
                return null;
            }
            for (KeyValuePair pair : stats.getData().getStatsList()) {
                if ("row_count".equalsIgnoreCase(pair.getKey())) {
                    return Long.valueOf(pair.getValue());
                }
            }
            log.warn("RAG | Milvus 统计里没有 row_count 字段，跳过登记簿核对");
            return null;
        } catch (Exception e) {
            log.warn("RAG | 读取 Milvus 统计异常，跳过登记簿核对：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 向量库不是 Milvus（本次进程用的是内存向量库，内容是空的）时的清理逻辑。
     *
     * <p>磁盘上的登记簿属于<b>上一个进程</b>，与本进程的向量库对不上，必须丢弃：
     * 留着只会让状态接口谎报、让用户以为"文档还在"。
     * 顺便把文件覆盖成空列表，下次启动就不会再读到这份脏数据。
     */
    private void discardStaleRegistry() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (!raw.isBlank()) {
                List<DocumentInfo> stale = json.readValue(raw, new TypeReference<List<DocumentInfo>>() {
                });
                if (!stale.isEmpty()) {
                    log.warn("RAG | 向量库是「进程内存」（不持久化），登记簿里 {} 个文档在本次进程中没有对应向量，"
                            + "已忽略并清空登记簿；请重新上传文档。", stale.size());
                }
            }
            documents.clear();
            flush();
        } catch (Exception e) {
            log.warn("RAG | 清理过期登记簿失败（忽略）：{}，原因={}", file, e.getMessage());
        }
    }

    public Optional<DocumentInfo> find(String fileName) {
        return Optional.ofNullable(documents.get(fileName));
    }

    /** 按入库时间倒序，最新的排前面 */
    public List<DocumentInfo> list() {
        List<DocumentInfo> list = new ArrayList<>(documents.values());
        list.sort(Comparator.comparing(DocumentInfo::ingestedAt).reversed());
        return list;
    }

    public int count() {
        return documents.size();
    }

    /** 所有文档的切片总数 */
    public int totalChunks() {
        return documents.values().stream().mapToInt(DocumentInfo::chunkCount).sum();
    }

    public void put(DocumentInfo info) {
        documents.put(info.fileName(), info);
        flush();
    }

    public boolean remove(String fileName) {
        boolean removed = documents.remove(fileName) != null;
        if (removed) {
            flush();
        }
        return removed;
    }

    public void clear() {
        documents.clear();
        flush();
    }

    private synchronized void flush() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(list()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("RAG | 登记簿写盘失败（不影响本次检索）：{}，原因={}", file, e.getMessage());
        }
    }
}
