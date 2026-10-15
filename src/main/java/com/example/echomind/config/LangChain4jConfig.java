package com.example.echomind.config;

import com.example.echomind.memory.ResilientChatMemoryStore;
import com.example.echomind.service.ChatAssistant;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 装配中心：把「模型 + 记忆 + 提示词」粘成一个可以注入的 {@link ChatAssistant}。
 *
 * <p>三层职责划分（v1.0 的核心就这三件事）：
 * <pre>
 *   StreamingChatModel   —— 由 application.yml 的 langchain4j.open-ai.streaming-chat-model 自动装配
 *          +
 *   ChatMemoryProvider   —— 决定"每个会话用哪块记忆"，底层落到 ResilientChatMemoryStore（先 Redis，连不上退进程内存）
 *          ↓
 *   AiServices.builder() —— 生成 ChatAssistant 的代理实现
 * </pre>
 *
 * <p>v2.0 起系统提示词<b>不再写在这里</b>，而是搬到 {@link ChatAssistant} 的
 * {@code @SystemMessage} 注解上：因为 RAG 需要"每次调用注入不同的参考资料"，
 * 而注解上的 {@code @V} 模板变量正好支持逐次解析，比配置一个固定字符串更合适。
 */
@Slf4j
@Configuration
public class LangChain4jConfig {

    /** 单个会话最多保留的消息条数（一问一答算 2 条，即约 10 轮上下文） */
    private static final int MAX_MEMORY_MESSAGES = 20;

    /**
     * 记忆提供者：LangChain4j 处理每一次请求前，都会用 memoryId 调它拿一个 ChatMemory。
     *
     * <p>这里刻意写成「每次新建一个 MessageWindowChatMemory 实例」而不是复用同一个：
     * 因为 {@code id} 是和实例绑定的，每个会话必须有自己的窗口。
     * 真正的数据落在 {@link ResilientChatMemoryStore} 里（内部先走 Redis），
     * 所以正常情况下应用重启上下文也不会丢；Redis 不可用时会自动退化为进程内存，
     * 保证「对话」这件事本身不会因为中间件挂掉而整个失败。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ResilientChatMemoryStore chatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_MEMORY_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * 声明式 AI Service 实例。
     * 注意返回值是接口类型 {@link ChatAssistant}，实现由 LangChain4j 在运行时用动态代理生成。
     *
     * <p>这里<b>不</b>调用 {@code .systemMessage(...)}：系统提示词由接口上的
     * {@code @SystemMessage} 注解提供，两处同时设置会导致优先级不易察觉，留一处就好。
     */
    @Bean
    public ChatAssistant chatAssistant(StreamingChatModel streamingChatModel,
                                       ChatMemoryProvider chatMemoryProvider) {
        log.info("正在装配 ChatAssistant：流式模型 + 会话记忆（Redis，不可用时降级进程内存；窗口 {} 条）；"
                + "普通对话与 RAG 对话共用同一个实例（提示词按方法区分）", MAX_MEMORY_MESSAGES);
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
