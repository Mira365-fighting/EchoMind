package com.example.echomind.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.0 下半段流水线的前半步：<b>用户提问 → 向量检索 TopK</b>。
 *
 * <p>检索出问题这一步先算好，好处有两个：
 * <ol>
 *     <li>命中的片段能原样推给前端「检索到的上下文」面板，验证"回答确实基于文档"；</li>
 *     <li>Prompt 拼装完全由我们自己控制（CoT + Few-Shot + 出处标注），比黑盒注入好讲。</li>
 * </ol>
 *
 * <p>注意分数口径：LangChain4j 的 score 是 {@code (余弦相似度 + 1) / 2}，
 * 不是原始余弦值。0.5 分 = 余弦 0，0.75 分 = 余弦 0.5。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingModel embeddingModel;
    private final VectorStoreProvider vectorStoreProvider;
    private final DocumentRegistry registry;
    private final RagProperties properties;

    /** 向量库里现在有多少片可检索内容（<b>按登记簿统计</b>，不是去问 Milvus） */
    public int indexedChunks() {
        return registry.totalChunks();
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 「有内容可检索」的判断，<b>只用于状态展示</b>。
     *
     * <p>注意它读的是登记簿（磁盘上的清单），不是向量库的实时内容。
     * 所以它可能和真实情况有出入：登记簿丢了但 Milvus 里有数据时它会说 false。
     * 正因为如此，{@link #retrieve} <b>不再用它作为闸门</b> ——
     * 检索的唯一依据应该是向量库里到底有没有数据，而那只有真去搜一次才知道。
     */
    public boolean isReady() {
        return properties.isEnabled() && indexedChunks() > 0;
    }

    /**
     * 检索 TopK。任何异常都吞掉并返回空列表 ——
     * <b>检索失败不该让对话整个挂掉</b>，退化成无 RAG 的普通回答即可。
     */
    public List<RetrievedChunk> retrieve(String question) {
        return retrieve(question, null, null);
    }

    /**
     * 检索 TopK，可临时覆盖 TopK / 相似度下限（给 {@code /api/rag/search} 调参用）。
     *
     * <p><b>为什么不拿登记簿当闸门</b>：早期版本在这里先判 {@code isReady()}（即登记簿里有没有文档），
     * 没有就直接返回空。结果踩了坑 —— 有一次登记簿被清掉、而 Milvus 里数据完好，
     * 于是「明明搜得到的东西变得永远搜不到」，而且症状是静默的（只是返回空）。
     * 现在改成一律真去搜：集合是空的话，搜索本来就会返回 0 条，结果一样；
     * 而集合有数据时就不会被一份磁盘清单挡住。
     *
     * @param topKOverride     不传则用配置值
     * @param minScoreOverride 不传则用配置值
     */
    public List<RetrievedChunk> retrieve(String question, Integer topKOverride, Double minScoreOverride) {
        if (!isEnabled() || question == null || question.isBlank()) {
            return List.of();
        }
        int topK = topKOverride != null && topKOverride > 0 ? topKOverride : properties.getTopK();
        double minScore = minScoreOverride != null ? minScoreOverride : properties.getMinScore();
        try {
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = vectorStoreProvider.store()
                    .search(request).matches();

            List<RetrievedChunk> chunks = new ArrayList<>(matches.size());
            int index = 1;
            for (EmbeddingMatch<TextSegment> match : matches) {
                TextSegment segment = match.embedded();
                if (segment == null) {
                    continue;
                }
                chunks.add(new RetrievedChunk(
                        index++,
                        match.score(),
                        metadataString(segment, DocumentService.META_FILE_NAME),
                        metadataInt(segment, DocumentService.META_CHUNK_INDEX),
                        segment.text()));
            }
            log.info("RAG | 检索「{}」命中 {} 片（TopK={}，minScore={}，向量库={}）",
                    abbreviate(question), chunks.size(), topK, minScore, vectorStoreProvider.label());
            return chunks;
        } catch (Exception e) {
            log.warn("RAG | 向量检索失败，本轮退化为普通对话：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 把命中的片段拼成 Prompt 里的【参考资料】块。
     * 每条都带编号和出处，让模型能写出「来源：xxx.pdf#片段3」这样的标注。
     */
    public String buildContextBlock(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            sb.append('[').append(chunk.index()).append("] ")
                    .append("来源：").append(chunk.fileName())
                    .append("#片段").append(chunk.chunkIndex())
                    .append("（相似度 ").append(String.format("%.3f", chunk.score())).append("）")
                    .append('\n')
                    .append(chunk.text().strip())
                    .append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String metadataString(TextSegment segment, String key) {
        String value = segment.metadata().getString(key);
        return value == null ? "(未知来源)" : value;
    }

    private static int metadataInt(TextSegment segment, String key) {
        Integer value = segment.metadata().getInteger(key);
        return value == null ? -1 : value;
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 40 ? oneLine.substring(0, 40) + "…" : oneLine;
    }
}
