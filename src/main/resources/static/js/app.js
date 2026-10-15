/**
 * EchoMind v1.0 前端逻辑
 *
 * 核心链路：
 *   EventSource 连 /api/chat/stream  →  收到 message 事件（增量 token）
 *   →  累加到 raw 字符串  →  节流后整体走一遍 Markdown 渲染
 *
 * 为什么要「整体重渲染」而不是「往 DOM 里追加 token」？
 *   Markdown 是上下文相关的（比如 ``` 围栏、列表缩进、表格），
 *   只有把当前已收到的全文重新解析一遍，才能保证渲染结果始终正确。
 *   代价是每渲染一次都要重新解析一遍全文，所以用 50ms 节流控制频率。
 */
(function () {
    'use strict';

    /* ==================== 配置 ==================== */

    // 直接双击打开 index.html（file://）时，需要显式指向后端；正常从后端访问则是同源
    const API_BASE = location.protocol === 'file:' ? 'http://localhost:8080' : '';
    const STORAGE_KEY = 'echomind.memoryId';
    // Markdown 重渲染节流间隔：太小会白烧 CPU，太大打字机就不顺
    const RENDER_INTERVAL_MS = 50;

    /* ==================== DOM ==================== */

    const el = {
        list: document.getElementById('messageList'),
        empty: document.getElementById('emptyState'),
        input: document.getElementById('input'),
        send: document.getElementById('sendBtn'),
        newChat: document.getElementById('newChatBtn'),
        chip: document.getElementById('sessionChip'),
        toast: document.getElementById('toast'),
    };

    /* ==================== 状态 ==================== */

    let memoryId = localStorage.getItem(STORAGE_KEY) || '';
    let session = null;   // 当前流式会话：{ es, bubble, settled }
    let renderTimer = null;

    /* ==================== Markdown 渲染 ==================== */

    if (window.marked) {
        marked.setOptions({ gfm: true, breaks: true });
    }

    function renderMarkdown(text) {
        if (!window.marked || !window.DOMPurify) {
            // 兜底：依赖没加载成功也不至于白屏
            return '<p>' + escapeHtml(text).replace(/\n/g, '<br>') + '</p>';
        }
        // 模型输出不可信，必须过一遍 DOMPurify 再塞进 innerHTML
        return DOMPurify.sanitize(marked.parse(text));
    }

    /**
     * 流式输出时代码块常常是「只开不闭」（``` 还没等到收尾）。
     * 这种半截状态会让 marked 把后面的内容全当成代码，
     * 所以渲染前先临时补一个闭合围栏。
     */
    function closeOpenFence(text) {
        const fences = text.match(/```/g);
        return fences && fences.length % 2 === 1 ? text + '\n```' : text;
    }

    function escapeHtml(text) {
        return String(text).replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
        })[c]);
    }

    function highlightCode(root) {
        if (!window.hljs) return;
        root.querySelectorAll('pre code').forEach((block) => {
            if (!block.dataset.highlighted) {
                hljs.highlightElement(block);
            }
        });
    }

    function addCopyButtons(root) {
        root.querySelectorAll('pre').forEach((pre) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'copy-btn';
            btn.textContent = '复制';
            btn.addEventListener('click', async () => {
                const code = pre.querySelector('code');
                try {
                    await navigator.clipboard.writeText(code ? code.innerText : pre.innerText);
                    btn.textContent = '已复制';
                    setTimeout(() => (btn.textContent = '复制'), 1500);
                } catch (e) {
                    toast('复制失败，请手动选择代码');
                }
            });
            pre.appendChild(btn);
        });
    }

    function appendCaret(root) {
        const caret = document.createElement('span');
        caret.className = 'caret';
        const last = root.lastElementChild;
        const inlineHost = last && /^(P|LI|H[1-6]|TD|TH|BLOCKQUOTE)$/.test(last.tagName);
        (inlineHost ? last : root).appendChild(caret);
    }

    /* ==================== 气泡渲染 ==================== */

    function paint(bubble, streaming) {
        const stick = isNearBottom();

        if (!bubble.raw) {
            bubble.el.innerHTML = '<span class="dots"><i></i><i></i><i></i></span>';
        } else {
            bubble.el.innerHTML = renderMarkdown(closeOpenFence(bubble.raw));
            highlightCode(bubble.el);
            addCopyButtons(bubble.el);
            if (streaming) appendCaret(bubble.el);
        }

        if (stick) scrollToBottom();
    }

    /** 节流：token 到达速度远快于 DOM 渲染速度，攒一攒再画 */
    function schedulePaint() {
        if (renderTimer || !session) return;
        renderTimer = setTimeout(() => {
            renderTimer = null;
            if (session && session.bubble) {
                paint(session.bubble, !session.settled);
            }
        }, RENDER_INTERVAL_MS);
    }

    function appendUserBubble(text) {
        const item = document.createElement('div');
        item.className = 'msg user';
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        item.appendChild(bubble);
        el.list.appendChild(item);
        scrollToBottom();
        return bubble;
    }

    function appendAssistantBubble() {
        const item = document.createElement('div');
        item.className = 'msg assistant';
        const bubbleEl = document.createElement('div');
        bubbleEl.className = 'bubble';
        item.appendChild(bubbleEl);
        el.list.appendChild(item);
        return { el: bubbleEl, raw: '' };
    }

    /* ==================== 滚动 ==================== */

    function isNearBottom() {
        return el.list.scrollHeight - el.list.scrollTop - el.list.clientHeight < 140;
    }

    function scrollToBottom() {
        el.list.scrollTop = el.list.scrollHeight;
    }

    /* ==================== 发送 / 接收 ==================== */

    function sendMessage(text) {
        const content = (text || '').trim();
        if (!content || session) return;

        hideEmpty();
        appendUserBubble(content);
        el.input.value = '';
        autoGrow();

        const bubble = appendAssistantBubble();

        session = { es: null, bubble, settled: false };
        setStreaming(true);
        paint(bubble, true);

        const url = `${API_BASE}/api/chat/stream?message=${encodeURIComponent(content)}`
            + (memoryId ? `&memoryId=${encodeURIComponent(memoryId)}` : '');

        const es = new EventSource(url);
        session.es = es;

        // 第一帧：后端下发本次会话 id，存下来，下一轮就带上它 → 多轮记忆
        es.addEventListener('session', (e) => {
            try {
                memoryId = JSON.parse(e.data);
                localStorage.setItem(STORAGE_KEY, memoryId);
                updateChip();
            } catch (err) {
                /* 忽略，没有 sessionId 也只是丢掉上下文 */
            }
        });

        // 增量 token
        es.addEventListener('message', (e) => {
            try {
                session.bubble.raw += JSON.parse(e.data);
            } catch (err) {
                session.bubble.raw += e.data;
            }
            schedulePaint();
        });

        // 收尾
        es.addEventListener('done', () => finish());

        // 注意：EventSource 的 error 事件有两个来源，靠 e.data 区分
        //   1) 后端主动发的 event: error  → 带 data
        //   2) 连接层断开（服务没起、进程被杀）→ 不带 data
        es.addEventListener('error', (e) => {
            if (e.data) {
                finish(parseData(e.data));
            } else {
                finish('连接已断开，请确认后端服务是否正常运行。');
            }
        });
    }

    function finish(errorText) {
        if (!session || session.settled) return;
        session.settled = true;

        if (renderTimer) {
            clearTimeout(renderTimer);
            renderTimer = null;
        }

        if (session.es) {
            session.es.close();
            session.es = null;
        }

        const bubble = session.bubble;
        if (errorText) {
            bubble.raw += (bubble.raw ? '\n\n' : '') + '> ⚠️ ' + errorText;
        }
        if (!bubble.raw.trim()) {
            bubble.raw = '（模型没有返回任何内容）';
        }

        paint(bubble, false);
        session = null;
        setStreaming(false);
        el.input.focus();
    }

    /** 用户点「停止」 */
    function stopStreaming() {
        if (!session) return;
        const bubble = session.bubble;
        const es = session.es;
        if (es) es.close();

        if (renderTimer) {
            clearTimeout(renderTimer);
            renderTimer = null;
        }

        session = null;
        if (!bubble.raw.trim()) {
            bubble.raw = '_（已停止，模型还没有输出内容）_';
        } else {
            bubble.raw += '\n\n_（已手动停止）_';
        }
        paint(bubble, false);
        setStreaming(false);
    }

    function parseData(data) {
        try {
            return JSON.parse(data);
        } catch (e) {
            return data;
        }
    }

    /* ==================== 页面状态 ==================== */

    function setStreaming(active) {
        el.send.textContent = active ? '停止' : '发送';
        el.send.classList.toggle('stop', active);
        el.newChat.disabled = active;
        el.newChat.style.opacity = active ? .5 : 1;
        el.newChat.style.cursor = active ? 'not-allowed' : 'pointer';
    }

    function updateChip() {
        el.chip.textContent = memoryId ? memoryId.slice(0, 8) + '…' : '新会话';
        el.chip.title = memoryId ? '当前会话 ID：' + memoryId : '还没建立会话';
    }

    function hideEmpty() {
        if (el.empty) el.empty.style.display = 'none';
    }

    function autoGrow() {
        el.input.style.height = 'auto';
        el.input.style.height = Math.min(el.input.scrollHeight, 180) + 'px';
    }

    let toastTimer = null;

    function toast(message) {
        el.toast.textContent = message;
        el.toast.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => el.toast.classList.remove('show'), 1800);
    }

    /* ==================== 会话管理 ==================== */

    function newChat() {
        if (session) return;
        const old = memoryId;

        memoryId = '';
        localStorage.removeItem(STORAGE_KEY);
        updateChip();

        el.list.querySelectorAll('.msg').forEach((n) => n.remove());
        if (el.empty) el.empty.style.display = '';

        if (old) {
            // 顺手把服务端记忆也清掉，别在 Redis 里留垃圾
            fetch(`${API_BASE}/api/chat/memory/${encodeURIComponent(old)}`, { method: 'DELETE' })
                .catch(() => { /* 后端没起也无所谓 */ });
        }
        el.input.focus();
    }

    /** 刷新页面后按 memoryId 把历史消息拉回来（证明记忆真的存在服务端） */
    async function loadHistory() {
        if (!memoryId) return;
        try {
            const res = await fetch(`${API_BASE}/api/chat/memory/${encodeURIComponent(memoryId)}`);
            if (!res.ok) return;
            const list = await res.json();
            if (!Array.isArray(list) || list.length === 0) return;

            hideEmpty();
            list.forEach((m) => {
                if (m.role === 'user') {
                    appendUserBubble(m.content || '');
                } else {
                    const bubble = appendAssistantBubble();
                    bubble.raw = m.content || '';
                    paint(bubble, false);
                }
            });
            scrollToBottom();
        } catch (e) {
            /* 后端没启动，静默处理 */
        }
    }

    /* ==================== 事件绑定 ==================== */

    el.send.addEventListener('click', () => {
        if (session) {
            stopStreaming();
        } else {
            sendMessage(el.input.value);
        }
    });

    el.newChat.addEventListener('click', newChat);

    el.input.addEventListener('input', autoGrow);

    el.input.addEventListener('keydown', (e) => {
        // 中文输入法选词时的 Enter 不能当成发送
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            sendMessage(el.input.value);
        }
    });

    document.querySelectorAll('.sample').forEach((btn) => {
        btn.addEventListener('click', () => {
            if (session) return;
            sendMessage(btn.textContent);
        });
    });

    // 页面关闭前主动断开，避免后端为了一个已经没人听的连接继续调模型
    window.addEventListener('beforeunload', () => {
        if (session && session.es) session.es.close();
    });

    /* ==================== 启动 ==================== */

    updateChip();
    autoGrow();
    loadHistory();
    el.input.focus();
})();
