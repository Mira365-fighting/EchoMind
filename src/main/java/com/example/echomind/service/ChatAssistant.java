package com.example.echomind.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 声明式 AI Service：只写接口，不写实现，实现类由 LangChain4j 的 {@code AiServices} 动态生成
 * （在 {@code LangChain4jConfig} 里用 {@code streamingChatModel} + {@code chatMemoryProvider} 装配）。
 *
 * <p>方法返回值是 {@link TokenStream}，代表"这是一次流式调用"。
 * 拿到 TokenStream 后要先注册回调，最后必须调用 {@code start()} 才会真正发起请求。
 */
public interface ChatAssistant {

    /**
     * 流式对话。
     *
     * @param memoryId    会话 id，LangChain4j 用它去 ChatMemoryProvider 取（Redis 里的）历史消息
     * @param userMessage 用户这一轮说的话
     * @return 尚未启动的 TokenStream，由调用方注册回调后 start()
     */
    TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
