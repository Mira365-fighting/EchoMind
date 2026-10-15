package com.example.echomind.controller;

import com.example.echomind.agent.AgentListener;
import com.example.echomind.agent.AgentProperties;
import com.example.echomind.agent.AgentStep;
import com.example.echomind.agent.AgentToolRegistry;
import com.example.echomind.agent.AgentTrace;
import com.example.echomind.agent.ReActAgent;
import com.example.echomind.dto.ChatMessageVO;
import com.example.echomind.memory.RedisChatMemoryStore;
import com.example.echomind.rag.RetrievalService;
import com.example.echomind.rag.RetrievedChunk;
import com.example.echomind.service.ChatAssistant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天接口。
 * <ul>
 *     <li>{@code GET /api/chat/stream}                —— SSE 流式对话（核心）</li>
 *     <li>{@code GET /api/chat/memory/{memoryId}}     —— 读取会话历史（刷新页面后回填）</li>
 *     <li>{@code DELETE /api/chat/memory/{memoryId}}  —— 清空某个会话的记忆</li>
 * </ul>
 *
 * <p><b>SSE 事件约定</b>（前端用 addEventListener 分别监听）：
 * <pre>
 *   event: session     data: "会话id"          —— 第一帧，把 memoryId 告诉前端
 *   event: context     data: [ {...}, ... ]    —— v2.0：检索命中的片段（TopK 结果，含相似度与出处）
 *   event: agent_start data: { traceId, ... }  —— v3.0：Agent 开始推理，附本次 trace id 与工具清单
 *   event: agent_step  data: { type, ... }     —— v3.0：中间某一步（思考/调工具/工具返回/出错）
 *   event: message     data: "一个token"        —— N 帧，模型吐出的增量文本
 *   event: agent_done  data: { stepCount, ... } —— v3.0：Agent 收尾，附轮数/工具次数/耗时
 *   event: done        data: "[DONE]"          —— 最后一帧
 *   event: error       data: "错误信息"         —— 出错帧，通常跟着连接关闭
 * </pre>
 *
 * <p>所有 {@code data} 一律是合法 JSON：字符串是带引号的 JSON 字符串，数组是 JSON 数组，
 * 前端统一 {@code JSON.parse} 即可。这样做的原因是 SSE 的 data 字段按行解析，
 * 而 Markdown token 里全是换行，不转义会直接把报文结构打乱。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** SSE 连接最长存活 5 分钟，避免异常情况下连接泄漏 */
    private static final long SSE_TIMEOUT_MS = 5L * 60 * 1000;

    /** memoryId 最大长度，防止有人塞一个超长 key 进 Redis */
    private static final int MAX_MEMORY_ID_LENGTH = 64;

    /**
     * 自己 new 一个 ObjectMapper，而不是从容器注入。
     * 原因：Spring Boot 4 已经切到 Jackson 3（tools.jackson 包），容器里的 ObjectMapper 不一定是我们
     * 需要的那个类型；而 LangChain4j 依赖的是 Jackson 2，这里直接用 2.x 版本最稳。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatAssistant chatAssistant;
    private final RedisChatMemoryStore chatMemoryStore;
    private final RetrievalService retrievalService;
    private final ReActAgent reActAgent;
    private final AgentToolRegistry toolRegistry;
    private final AgentProperties agentProperties;

    /**
     * 流式对话：前端用 EventSource 连这个接口。
     *
     * <p>两条链路，由 {@code agent} 开关选择：
     * <pre>
     *   agent=false  → v2.0 链路：检索 → 一轮流式回答
     *   agent=true   → v3.0 链路：检索 → ReAct 多步推理（模型自己决定调不调工具）→ 答案回放
     * </pre>
     * 两条链路前面都一样：<b>先检索，再回答</b>。检索在调用大模型之前完成，
     * 所以命中结果能立刻作为 {@code context} 事件推给前端，用户能一边看模型输出，
     * 一边核对"它到底参考了哪几段"。Agent 链路额外还会把这段资料作为系统提示词的一部分，
     * 让模型知道"已经给你预备了资料，不够的话你还能自己调 search_knowledge_base 再查"。
     *
     * @param message  用户输入
     * @param memoryId 会话 id；不传就新建一个会话，传了就在原会话上继续（多轮记忆靠它）
     * @param rag      是否启用知识库预检索，默认 true；前端可以关掉做 A/B 对比
     * @param agent    是否走 Agent 多步推理，默认 true；关掉就是 v2.0 的行为
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                            @RequestParam(required = false) String memoryId,
                            @RequestParam(required = false, defaultValue = "true") boolean rag,
                            @RequestParam(required = false, defaultValue = "true") boolean agent) {

        String sessionId = normalizeMemoryId(memoryId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 客户端断开 / 超时 只记个日志，业务上不需要额外处理
        emitter.onTimeout(() -> log.info("SSE 超时断开。memoryId={}", sessionId));
        emitter.onError(e -> log.info("SSE 连接异常断开。memoryId={}，原因={}", sessionId, e.getMessage()));

        // 第一帧：把 sessionId 回给前端，后续轮次带上它就能续上记忆
        send(emitter, "session", sessionId);

        try {
            // 1) 检索 TopK（未启用 / 库是空的 / 检索出错，都会返回空列表，退化成普通对话）
            List<RetrievedChunk> chunks = rag ? retrievalService.retrieve(message) : List.of();
            if (rag) {
                // 无论有没有命中都推一帧，前端才能区分"没开 RAG"和"没搜到相关内容"
                sendJson(emitter, "context", chunks);
            }
            String contextBlock = chunks.isEmpty() ? null : retrievalService.buildContextBlock(chunks);

            // 2) 选链路
            if (agent && agentProperties.isEnabled() && !toolRegistry.isEmpty()) {
                runAgent(emitter, sessionId, message, contextBlock);
            } else {
                if (agent && !agentProperties.isEnabled()) {
                    log.info("Agent 已关闭，本次按普通问答处理。memoryId={}", sessionId);
                }
                runPlainStream(emitter, sessionId, message, contextBlock);
            }

        } catch (Exception e) {
            // 同步阶段的异常（比如模型配置错误、密钥无效）也转成 SSE 事件，别让前端一直转圈
            log.error("发起流式对话失败。memoryId={}", sessionId, e);
            send(emitter, "error", readableMessage(e));
            emitter.complete();
        }

        return emitter;
    }

    // ==================== 链路一：v3.0 Agent 多步推理 ====================

    /**
     * 跑 ReAct 循环，把每一步实时推给前端。
     *
     * <p>注意这个方法是<b>同步阻塞</b>的：Agent 循环有几轮模型调用、几次工具调用，
     * 全部在这一个请求线程上跑完。这样做是因为 ReAct 本身是顺序依赖的
     * （下一步的输入依赖上一步的输出），并发并没有意义；
     * 而每一步都会立刻通过 {@link SseEmitter} 推出去，用户感知到的是实时的。
     */
    private void runAgent(SseEmitter emitter, String sessionId, String message, String contextBlock) {
        AgentListener listener = new AgentListener() {

            @Override
            public void onTraceId(String traceId) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("traceId", traceId);
                payload.put("maxSteps", agentProperties.getMaxSteps());
                payload.put("maxToolCalls", agentProperties.getMaxToolCalls());
                payload.put("temperature", agentProperties.getTemperature());
                payload.put("toolCount", toolRegistry.descriptors().size());
                payload.put("tools", toolRegistry.descriptors().stream()
                        .map(t -> t.get("name")).toList());
                sendJson(emitter, "agent_start", payload);
            }

            @Override
            public void onStep(AgentStep step) {
                sendJson(emitter, "agent_step", step);
            }

            @Override
            public void onAnswerChunk(String chunk) {
                send(emitter, "message", chunk);
            }
        };

        AgentTrace trace = reActAgent.run(sessionId, message, contextBlock, listener);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", trace.traceId());
        summary.put("memoryId", trace.memoryId());
        summary.put("stepCount", trace.stepCount());
        summary.put("toolCallCount", trace.toolCallCount());
        summary.put("truncated", trace.truncated());
        summary.put("finishReason", trace.finishReason());
        summary.put("costMs", trace.costMs());
        summary.put("model", trace.model());
        sendJson(emitter, "agent_done", summary);

        send(emitter, "done", "[DONE]");
        emitter.complete();
    }

    // ==================== 链路二：v1.0/v2.0 单轮流式回答 ====================

    /** 命中文档走 RAG 提示词，没命中走普通提示词；模型侧是真 token 流 */
    private void runPlainStream(SseEmitter emitter, String sessionId, String message, String contextBlock) {
        TokenStream tokenStream = (contextBlock == null)
                ? chatAssistant.chat(sessionId, message)
                : chatAssistant.chatWithContext(sessionId, contextBlock, message);

        tokenStream
                .onPartialResponse(token -> send(emitter, "message", token))
                .onCompleteResponse(response -> {
                    send(emitter, "done", "[DONE]");
                    emitter.complete();
                })
                .onError(error -> {
                    log.error("流式对话失败。memoryId={}", sessionId, error);
                    send(emitter, "error", readableMessage(error));
                    emitter.complete();
                });

        // 真正发起请求（必须放在最后）
        tokenStream.start();
    }

    /** 读取某个会话的历史消息（只回 user / assistant，system 提示词不暴露给前端） */
    @GetMapping("/memory/{memoryId}")
    public List<ChatMessageVO> history(@PathVariable String memoryId) {
        return chatMemoryStore.getMessages(memoryId).stream()
                .map(ChatController::toVO)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 清空某个会话的记忆（前端「新对话」按钮用） */
    @DeleteMapping("/memory/{memoryId}")
    public void clear(@PathVariable String memoryId) {
        chatMemoryStore.deleteMessages(memoryId);
        log.info("已清空会话记忆。memoryId={}", memoryId);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 发送一帧 SSE（载荷是字符串）。
     *
     * <p><b>为什么要把数据再 JSON 转义一遍？</b>
     * SSE 的 {@code data:} 字段是「按行」解析的，载荷里一旦出现裸换行就会破坏报文结构。
     * 而大模型输出的 Markdown token 里恰恰经常有换行（列表、段落、代码块）。
     * 所以这里统一用 JSON 字符串包一层，换行变成字面量 {@code \n}，前端 {@code JSON.parse} 再还原。
     */
    private void send(SseEmitter emitter, String event, String payload) {
        sendJson(emitter, event, payload);
    }

    /**
     * 发送一帧 SSE（载荷是任意对象，会被序列化成 JSON）。
     * 检索结果数组就走这里 —— 和上面的字符串是同一种约定，前端一律 {@code JSON.parse}。
     */
    private void sendJson(SseEmitter emitter, String event, Object payload) {
        try {
            String data = JSON.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.TEXT_PLAIN));
        } catch (JsonProcessingException e) {
            // JsonProcessingException 是 IOException 的子类，必须放在前面才能捕获到。
            // 载荷都是简单 record / String，理论上不会走到这里。
            log.warn("SSE 载荷序列化失败：{}", e.getMessage());
        } catch (IOException | IllegalStateException e) {
            // 用户点了"停止"或直接关页面时会走到这里，属于正常情况
            log.debug("SSE 发送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    private static String normalizeMemoryId(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = memoryId.trim();
        return trimmed.length() > MAX_MEMORY_ID_LENGTH
                ? trimmed.substring(0, MAX_MEMORY_ID_LENGTH)
                : trimmed;
    }

    private static ChatMessageVO toVO(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return new ChatMessageVO("user", userMessage.singleText());
        }
        if (message instanceof AiMessage aiMessage) {
            return new ChatMessageVO("assistant", aiMessage.text());
        }
        return null;
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank())
                ? "模型调用失败：" + error.getClass().getSimpleName()
                : message;
    }
}
