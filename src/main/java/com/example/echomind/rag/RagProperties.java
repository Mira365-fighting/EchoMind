package com.example.echomind.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * v2.0 RAG 全部可调参数，对应 application.yml 里的 {@code echomind.rag.*}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "echomind.rag")
public class RagProperties {

    /** 总开关。关掉后所有接口照常，但不再做向量检索，退化成 v1.0 的纯对话。 */
    private boolean enabled = true;

    /**
     * 向量库类型：
     * <ul>
     *     <li>{@code auto}   —— 优先 Milvus，连不上就降级到进程内存（默认）</li>
     *     <li>{@code milvus} —— 必须用 Milvus，连不上直接启动失败（生产/答辩演示用）</li>
     *     <li>{@code memory} —— 只用内存向量库，完全不碰 Milvus</li>
     * </ul>
     */
    private String store = "auto";

    /** 检索返回的片段数（TopK） */
    private int topK = 5;

    /**
     * 相似度下限。<b>注意单位换算</b>：LangChain4j 的分数不是原始余弦值，
     * 而是 {@code (cosine + 1) / 2}，即 0.5 分对应余弦 0.0、0.75 分对应余弦 0.5。
     */
    private double minScore = 0.6;

    /** 单个切片的最大 token 数 */
    private int maxSegmentSize = 400;

    /** 相邻切片的重叠 token 数，避免一句话被切断后语义丢失 */
    private int maxOverlapSize = 80;

    /** 允许上传的单个文件大小上限 */
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    /** 文档登记簿（记录已入库的文件，重启后不丢）的落盘位置 */
    private String registryPath = "data/documents.json";

    /** Milvus 连接配置。刻意不放在 langchain4j.* 下，避免以后引入官方 starter 时前缀冲突。 */
    private Milvus milvus = new Milvus();

    @Getter
    @Setter
    public static class Milvus {
        private String host = "192.168.77.140";
        private int port = 19530;
        private String databaseName = "default";
        private String collectionName = "echomind_vectors";
        /** 探测连接的超时，连不上要快速失败，不能卡住应用启动 */
        private Duration connectTimeout = Duration.ofSeconds(5);
    }
}
