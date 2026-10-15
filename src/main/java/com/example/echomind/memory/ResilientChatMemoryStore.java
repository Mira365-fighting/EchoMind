package com.example.echomind.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话记忆的「先 Redis、连不上就退到进程内存」包装器。
 *
 * <p><b>为什么需要它</b>：{@link RedisChatMemoryStore} 一旦 Redis 不可达，会直接把异常抛穿
 * {@code MessageWindowChatMemory}，导致<b>整轮对话失败</b>——用户看到的是
 * {@code event:error data:"Unable to connect to Redis"}，连大模型都没被调用。
 * 而 Milvus 那边（见 {@code RagConfig}）早就做了「连不上就自动降级」，
 * 记忆这一层却还是硬失败，两边行为不一致。
 *
 * <p><b>降级语义</b>：这是<b>一次性</b>降级 —— 第一次碰到 Redis 异常就永久切到内存实现，
 * 后续请求不再反复去连 Redis（否则每次对话都要白等一次连接超时）。
 * 代价是历史会话不再跨重启保留；Redis 恢复后重启应用即可切回。
 *
 * <p>探测是<b>惰性</b>的：不在启动时 ping，而是等第一次真正用到记忆时才试。
 * 好处是启动不会多花一个连接超时的时间，且 Redis 在运行期挂掉也能被接住。
 *
 * <p>用 {@link Primary} 标记，保证按类型注入 {@link ChatMemoryStore} 时拿到的是这个包装器
 * 而不是裸的 {@link RedisChatMemoryStore}。
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class ResilientChatMemoryStore implements ChatMemoryStore {

    /** 降级后的兜底实现，LangChain4j 自带，内部就是一个 ConcurrentHashMap */
    private final ChatMemoryStore fallback = new InMemoryChatMemoryStore();

    private final RedisChatMemoryStore redisStore;

    /** 是否已经降级。volatile：读写发生在不同请求线程上 */
    private volatile boolean degraded = false;

    /** 供状态接口展示 */
    public boolean isDegraded() {
        return degraded;
    }

    /** 供状态接口展示：还没用过记忆时是「未探测」，避免在 Redis 其实是挂的时候谎报「Redis」 */
    public String activeStore() {
        if (degraded) {
            return "进程内存（降级）";
        }
        return touched ? "Redis" : "Redis（未探测）";
    }

    private volatile boolean touched = false;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        touched = true;
        if (degraded) {
            return fallback.getMessages(memoryId);
        }
        try {
            return redisStore.getMessages(memoryId);
        } catch (DataAccessException e) {
            degrade(e);
            return fallback.getMessages(memoryId);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        touched = true;
        if (degraded) {
            fallback.updateMessages(memoryId, messages);
            return;
        }
        try {
            redisStore.updateMessages(memoryId, messages);
        } catch (DataAccessException e) {
            degrade(e);
            fallback.updateMessages(memoryId, messages);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        touched = true;
        if (degraded) {
            fallback.deleteMessages(memoryId);
            return;
        }
        try {
            redisStore.deleteMessages(memoryId);
        } catch (DataAccessException e) {
            degrade(e);
            fallback.deleteMessages(memoryId);
        }
    }

    /** 只在第一次失败时打日志，避免每个请求刷一屏 WARN */
    private void degrade(DataAccessException e) {
        synchronized (this) {
            if (degraded) {
                return;
            }
            degraded = true;
            log.warn("会话记忆 | Redis 不可用，本次对话已自动降级为「进程内存」，多轮上下文仍然可用。原因：{}",
                    rootMessage(e));
            log.warn("会话记忆 | 降级后历史会话不再跨重启保留；Redis 恢复后重启应用即可切回。"
                    + "（对比：向量库走的是 echomind.rag.store=auto 的同类降级策略）");
        }
    }

    /** 把最内层原因挖出来，Redis 的异常链又长又吵 */
    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
