package com.example.echomind.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 声明式 AI Service：只写接口，不写实现，实现类由 LangChain4j 的 {@code AiServices} 动态生成
 * （在 {@code LangChain4jConfig} 里用 {@code streamingChatModel} + {@code chatMemoryProvider} 装配）。
 *
 * <p>方法返回值是 {@link TokenStream}，代表"这是一次流式调用"。
 * 拿到 TokenStream 后要先注册回调，最后必须调用 {@code start()} 才会真正发起请求。
 *
 * <h3>v2.0 为什么要两个方法</h3>
 * 命中文档和没命中文档，回答的<b>约束完全不同</b>：前者必须"只依据资料、答不了就拒答"，
 * 后者是普通闲聊。硬用一个提示词 + 拼接字符串去兼容会很别扭，
 * 所以拆成两个方法，各自带自己的 {@link SystemMessage}（注解里的字符串是编译期常量，
 * 直接引用 {@link Prompts} 里的常量即可）。
 *
 * <h3>关于 {{context}} 会不会污染对话记忆</h3>
 * 不会。{@code @V} 变量在<b>每次调用</b>时才会被解析成真实文本，
 * 而且 LangChain4j 把系统消息写进 ChatMemory 时是<b>替换</b>而非追加 ——
 * 也就是说记忆里始终只有一条系统消息，内容永远是最新这一轮的检索结果。
 * （这一点是读 {@code DefaultAiServices} 源码 + 写探针程序实测确认的，不是猜的。）
 */
public interface ChatAssistant {

    /**
     * 普通流式对话（未命中文档 / 关闭 RAG 时走这里）。
     *
     * @param memoryId    会话 id，LangChain4j 用它去 ChatMemoryProvider 取（Redis 里的）历史消息
     * @param userMessage 用户这一轮说的话
     * @return 尚未启动的 TokenStream，由调用方注册回调后 start()
     */
    @SystemMessage(Prompts.PLAIN)
    TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage);

    /**
     * RAG 流式对话：把检索到的片段作为系统消息注入，用户消息保持原样。
     *
     * @param memoryId    会话 id
     * @param context     由 {@code RetrievalService.buildContextBlock()} 拼好的【参考资料】文本
     * @param userMessage 用户的原始问题（<b>原样进记忆</b>，不带资料，避免历史越滚越大）
     * @return 尚未启动的 TokenStream
     */
    @SystemMessage(Prompts.RAG)
    TokenStream chatWithContext(@MemoryId String memoryId,
                                @V("context") String context,
                                @UserMessage String userMessage);
}
