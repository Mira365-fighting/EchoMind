package com.example.echomind.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的会话记忆存储。
 *
 * <p>LangChain4j 的 {@link ChatMemoryStore} 只定义了三个方法，属于「最底层」的持久化接口：
 * <ul>
 *     <li>{@link #getMessages(Object)}  —— 按会话 id 读历史</li>
 *     <li>{@link #updateMessages(Object, List)} —— 覆盖式写回</li>
 *     <li>{@link #deleteMessages(Object)} —— 删除</li>
 * </ul>
 * 再往上一层的「只保留最近 N 条」逻辑由 {@code MessageWindowChatMemory} 负责（见 LangChain4jConfig），
 * 所以这里只管「怎么存」，不管「存多少」。
 *
 * <p>存储格式：直接把 {@code ChatMessage} 列表序列化成 LangChain4j 自带的 JSON，
 * 一个会话一条 String 记录，key = {@code echomind:chat:memory:{memoryId}}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** Redis Key 前缀：echomind:chat:memory:{memoryId} */
    private static final String KEY_PREFIX = "echomind:chat:memory:";

    /** 会话存活时间：7 天不活跃就自动清理，避免 Redis 被历史会话撑爆 */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            // 历史数据格式不兼容（比如换过 LangChain4j 版本）时不要拖垮整轮对话，直接丢弃重建
            log.warn("会话记忆反序列化失败，已清空重建。memoryId={}", memoryId, e);
            deleteMessages(memoryId);
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }
        redisTemplate.opsForValue().set(key(memoryId), ChatMessageSerializer.messagesToJson(messages), TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
