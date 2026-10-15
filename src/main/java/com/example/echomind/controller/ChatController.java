package com.example.echomind.controller;

import com.example.echomind.dto.ChatMessageVO;
import com.example.echomind.memory.RedisChatMemoryStore;
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
import java.util.List;
import java.util.UUID;

/**
 * 聊天接口。v1.0 只有三个端点：
 * <ul>
 *     <li>{@code GET /api/chat/stream}                —— SSE 流式对话（核心）</li>
 *     <li>{@code GET /api/chat/memory/{memoryId}}     —— 读取会话历史（刷新页面后回填）</li>
 *     <li>{@code DELETE /api/chat/memory/{memoryId}}  —— 清空某个会话的记忆</li>
 * </ul>
 *
 * <p><b>SSE 事件约定</b>（前端用 addEventListener 分别监听）：
 * <pre>
 *   event: session  data: "会话id"        —— 第一帧，把 memoryId 告诉前端
 *   event: message  data: "一个token"     —— N 帧，模型吐出的增量文本
 *   event: done     data: "[DONE]"        —— 最后一帧，模型说完了
 *   event: error    data: "错误信息"       —— 出错帧，通常跟着连接关闭
 * </pre>
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

    /**
     * 流式对话：前端用 EventSource 连这个接口。
     *
     * @param message  用户输入
     * @param memoryId 会话 id；不传就新建一个会话，传了就在原会话上继续（多轮记忆靠它）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message,
                            @RequestParam(required = false) String memoryId) {

        String sessionId = normalizeMemoryId(memoryId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 客户端断开 / 超时 只记个日志，业务上不需要额外处理
        emitter.onTimeout(() -> log.info("SSE 超时断开。memoryId={}", sessionId));
        emitter.onError(e -> log.info("SSE 连接异常断开。memoryId={}，原因={}", sessionId, e.getMessage()));

        // 第一帧：把 sessionId 回给前端，后续轮次带上它就能续上记忆
        send(emitter, "session", sessionId);

        try {
            // 1) 拿到 TokenStream（此时请求还没发出去）
            TokenStream tokenStream = chatAssistant.chat(sessionId, message);

            // 2) 注册三种回调
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

            // 3) 真正发起请求（必须放在最后）
            tokenStream.start();

        } catch (Exception e) {
            // 同步阶段的异常（比如模型配置错误、密钥无效）也转成 SSE 事件，别让前端一直转圈
            log.error("发起流式对话失败。memoryId={}", sessionId, e);
            send(emitter, "error", readableMessage(e));
            emitter.complete();
        }

        return emitter;
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
     * 发送一帧 SSE。
     *
     * <p><b>为什么要把数据再 JSON 转义一遍？</b>
     * SSE 的 {@code data:} 字段是「按行」解析的，载荷里一旦出现裸换行就会破坏报文结构。
     * 而大模型输出的 Markdown token 里恰恰经常有换行（列表、段落、代码块）。
     * 所以这里统一用 JSON 字符串包一层，换行变成字面量 {@code \n}，前端 {@code JSON.parse} 再还原。
     */
    private void send(SseEmitter emitter, String event, String payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(toJsonString(payload), MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            // 用户点了"停止"或直接关页面时会走到这里，属于正常情况
            log.debug("SSE 发送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    private static String toJsonString(String raw) {
        try {
            return JSON.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            // 基本不可能发生（String 一定能序列化），兜底返回原串
            return "\"\"";
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
