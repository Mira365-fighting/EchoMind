package com.example.echomind.config;

import com.example.echomind.rag.RagProperties;
import com.example.echomind.rag.VectorStoreProvider;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * v2.0 RAG 装配：<b>Embedding 模型 + 向量库</b>。
 *
 * <p>最关键的一条约束在这里落地：<b>向量维度必须唯一</b>。
 * 维度只有一个来源 —— {@code embeddingModel.dimension()}，
 * Milvus collection 的维度由它推导，不允许两边各写一个数字。这样就不可能配错。
 *
 * <p>可用性策略（{@code echomind.rag.store}）：
 * <pre>
 *   auto   —— 先试 Milvus，连不上就降级进程内存，应用照常启动（默认，开发期用）
 *   milvus —— 必须 Milvus，连不上直接启动失败（演示/部署用，避免"悄悄降级"）
 *   memory —— 完全不碰 Milvus
 * </pre>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /**
     * 这个模型给外面看/记日志用的名字。
     *
     * <p>为什么要自己起名：{@code BgeSmallZhV15QuantizedEmbeddingModel} 没有提供设置模型名的入口，
     * 它从基类继承来的 {@code modelName()} 一律返回 {@code "unknown"}，
     * 记进 {@code documents.json} 和状态接口里会很难看。所以下面用匿名子类把它覆盖掉。
     */
    public static final String EMBEDDING_MODEL_NAME = "bge-small-zh-v1.5-q (ONNX quantized)";

    /**
     * Embedding 模型：BGE-small-zh-v1.5 量化版。
     *
     * <p>选它的原因：模型文件（ONNX + tokenizer）直接打包在 jar 里，进程内推理，
     * <b>不需要任何 API Key，也不联网</b>，维度 512。换模型（比如 M3E）时维度会变，
     * Milvus 那边的 collection 必须删掉重建。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        EmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel() {
            @Override
            public String modelName() {
                return EMBEDDING_MODEL_NAME;
            }
        };
        log.info("RAG | Embedding 模型 = {}，输出维度 = {}（本地 ONNX 推理，无需 API Key）",
                model.modelName(), model.dimension());
        return model;
    }

    @Bean
    public VectorStoreProvider vectorStoreProvider(EmbeddingModel embeddingModel, RagProperties properties) {
        int dimension = embeddingModel.dimension();
        String wanted = properties.getStore() == null
                ? "auto"
                : properties.getStore().trim().toLowerCase(Locale.ROOT);
        RagProperties.Milvus milvus = properties.getMilvus();
        String endpoint = milvus.getHost() + ":" + milvus.getPort();

        if ("memory".equals(wanted)) {
            log.info("RAG | 向量库 = 进程内存（echomind.rag.store=memory，不连接 Milvus）");
            return inMemory(dimension, "配置指定 echomind.rag.store=memory");
        }

        try {
            return milvus(dimension, milvus);
        } catch (Exception e) {
            String reason = rootMessage(e);
            if ("milvus".equals(wanted)) {
                throw new IllegalStateException("echomind.rag.store=milvus 要求必须连上 Milvus，但 " + endpoint
                        + " 不可用：" + reason
                        + "。若希望连不上时自动降级到内存向量库，请把 echomind.rag.store 改成 auto。", e);
            }
            log.warn("""
                    RAG | Milvus（{}）不可用，自动降级为「进程内存向量库」。
                          降级后功能完整，但——重启应用数据就没了，且不共享给其它进程。
                          等虚拟机里的 Milvus 起来以后重启本应用即可切回。
                          失败原因：{}""", endpoint, reason);
            return inMemory(dimension, "Milvus(" + endpoint + ") 不可用：" + reason);
        }
    }

    // ==================== Milvus ====================

    private VectorStoreProvider milvus(int dimension, RagProperties.Milvus cfg) {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(cfg.getHost())
                .withPort(cfg.getPort())
                .withDatabaseName(cfg.getDatabaseName())
                // 连不上要快速失败，不然应用启动会卡住
                .withConnectTimeout(cfg.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();

        MilvusServiceClient client = new MilvusServiceClient(connectParam);
        boolean handedOver = false;
        try {
            R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(cfg.getCollectionName())
                    .build());
            if (has.getException() != null) {
                throw new IllegalStateException("探测集合失败：" + has.getMessage(), has.getException());
            }

            if (Boolean.TRUE.equals(has.getData())) {
                // 集合已存在 —— 这是维度最容易出问题的地方：旧集合的维度可能和现在的模型对不上
                Integer existing = describeVectorDimension(client, cfg.getCollectionName());
                if (existing != null && existing != dimension) {
                    throw new IllegalStateException(String.format(
                            "集合 %s 现有维度是 %d，而 Embedding 模型输出 %d 维 —— 两者必须一致。"
                                    + "解决办法：把 Milvus 里这个 collection 删掉（会自动按 %d 维重建），"
                                    + "或者把 echomind.rag.milvus.collection-name 换成一个新的名字。",
                            cfg.getCollectionName(), existing, dimension, dimension));
                }
                log.info("RAG | Milvus 集合已存在：{}，维度 {} —— 与 Embedding 模型一致",
                        cfg.getCollectionName(), existing);
            } else {
                log.info("RAG | Milvus 集合尚不存在，首次写入时自动创建：{}（维度 {}）",
                        cfg.getCollectionName(), dimension);
            }

            EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                    .milvusClient(client)
                    .collectionName(cfg.getCollectionName())
                    .dimension(dimension)
                    // BGE 中文模型官方建议余弦相似度；langchain4j 的 Milvus 实现内部也是 COSINE 默认
                    .metricType(MetricType.COSINE)
                    // 数据量在十万条以内时，FLAT 精确检索又快又准，不需要调索引参数
                    .indexType(IndexType.FLAT)
                    // 写完立即 flush，否则刚上传的文档要等一会儿才搜得到（答辩现场很尴尬）
                    .autoFlushOnInsert(true)
                    .build();
            handedOver = true;

            log.info("RAG | 向量库 = Milvus {} / 数据库 {} / 集合 {}（维度 {}，COSINE，FLAT）",
                    cfg.getHost() + ":" + cfg.getPort(), cfg.getDatabaseName(),
                    cfg.getCollectionName(), dimension);
            return new VectorStoreProvider(store, "milvus", dimension, cfg.getCollectionName(),
                    "Milvus " + cfg.getHost() + ":" + cfg.getPort() + " / " + cfg.getDatabaseName(),
                    client);
        } finally {
            if (!handedOver) {
                closeQuietly(client);
            }
        }
    }

    /**
     * 读出集合里向量字段的 dim。
     *
     * <p>Milvus 把维度放在向量字段的 type_params 里（key = {@code dim}），
     * 所以要先找到 FloatVector 字段，再从它的 type_params 里取。
     */
    private static Integer describeVectorDimension(MilvusServiceClient client, String collectionName) {
        R<DescribeCollectionResponse> described = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (described.getException() != null || described.getData() == null) {
            log.warn("RAG | 读取集合结构失败，跳过维度校验：{}", described.getMessage());
            return null;
        }
        CollectionSchema schema = described.getData().getSchema();
        if (schema == null) {
            return null;
        }
        for (FieldSchema field : schema.getFieldsList()) {
            if (field.getDataType() != DataType.FloatVector) {
                continue;
            }
            for (KeyValuePair typeParam : field.getTypeParamsList()) {
                if ("dim".equalsIgnoreCase(typeParam.getKey())) {
                    try {
                        return Integer.valueOf(typeParam.getValue());
                    } catch (NumberFormatException e) {
                        log.warn("RAG | 解析向量维度失败：dim={}", typeParam.getValue());
                    }
                }
            }
        }
        return null;
    }

    private static VectorStoreProvider inMemory(int dimension, String detail) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        // 内存向量库没有 Milvus 客户端，第六个参数传 null
        return new VectorStoreProvider(store, "memory", dimension, "-", detail, null);
    }

    private static void closeQuietly(MilvusServiceClient client) {
        try {
            // 参数单位是「秒」
            client.close(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("关闭 Milvus 探测连接时出错（忽略）：{}", e.getMessage());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        // Milvus 客户端的异常信息有时很长，截断一下
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
