package com.example.echomind.config;

import com.example.echomind.memory.RedisChatMemoryStore;
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
 *   ChatMemoryProvider   —— 决定"每个会话用哪块记忆"，底层落到 Redis
 *          +
 *   SystemPrompt         —— 人设 / 输出规范
 *          ↓
 *   AiServices.builder() —— 生成 ChatAssistant 的代理实现
 * </pre>
 */
@Slf4j
@Configuration
public class LangChain4jConfig {

    /** 系统提示词：定人设，并约束用 Markdown 输出（配合前端 marked.js 渲染） */
    private static final String SYSTEM_PROMPT = """
            你是 EchoMind，一个中文 AI 助手。
            回答要准确、简洁、有条理，先给结论再展开。
            可以使用 Markdown 语法来组织内容（标题、列表、表格、代码块），代码块请标注语言。
            """;

    /** 单个会话最多保留的消息条数（一问一答算 2 条，即约 10 轮上下文） */
    private static final int MAX_MEMORY_MESSAGES = 20;

    /**
     * 记忆提供者：LangChain4j 处理每一次请求前，都会用 memoryId 调它拿一个 ChatMemory。
     *
     * <p>这里刻意写成「每次新建一个 MessageWindowChatMemory 实例」而不是复用同一个：
     * 因为 {@code id} 是和实例绑定的，每个会话必须有自己的窗口。
     * 真正的数据落在 {@link RedisChatMemoryStore} 里，所以应用重启上下文也不会丢。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore redisChatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_MEMORY_MESSAGES)
                .chatMemoryStore(redisChatMemoryStore)
                .build();
    }

    /**
     * 声明式 AI Service 实例。
     * 注意返回值是接口类型 {@link ChatAssistant}，实现由 LangChain4j 在运行时用动态代理生成。
     */
    @Bean
    public ChatAssistant chatAssistant(StreamingChatModel streamingChatModel,
                                       ChatMemoryProvider chatMemoryProvider) {
        log.info("正在装配 ChatAssistant：流式模型 + Redis 会话记忆（窗口 {} 条）", MAX_MEMORY_MESSAGES);
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessage(SYSTEM_PROMPT)
                .build();
    }
}
