/**
 * EchoMind v3.0 前端逻辑
 *
 * 三条链路：
 *   1) 对话：EventSource 连 /api/chat/stream
 *         session     事件 → 拿到会话 id
 *         context     事件 → 本次预检索命中的 TopK 片段（v2.0），渲染成「检索到的上下文」面板
 *         agent_start 事件 → v3.0：Agent 开始推理，建一条时间线，附 traceId 与可用工具
 *         agent_step  事件 → v3.0：中间某一步（思考 / 调工具 / 工具返回 / 出错），追加到时间线
 *         message     事件 → 增量 token，累加后节流整体重渲染 Markdown
 *         agent_done  事件 → v3.0：收尾统计（轮数 / 工具次数 / 耗时），刷新侧栏运行记录
 *         done        事件 → 收尾
 *   2) 知识库：/api/documents（增删查）、/api/rag/status（状态）、/api/rag/search（只检索自测）
 *   3) Agent ：/api/agent/status（配置 + 工具清单 + Python 服务健康）、
 *              /api/agent/traces/{memoryId}（最近的运行记录，存在 Redis 里）
 *
 * 为什么要「整体重渲染」而不是「往 DOM 里追加 token」？
 *   Markdown 是上下文相关的（比如 ``` 围栏、列表缩进、表格），
 *   只有把当前已收到的全文重新解析一遍，才能保证渲染结果始终正确。
 *   代价是每渲染一次都要重新解析一遍全文，所以用 50ms 节流控制频率。
 *
 * 安全约定：凡是来自后端的文本（模型输出、文档片段、工具入参出参）
 * 一律用 textContent 写入 DOM，绝不拼 innerHTML —— 只有 Markdown 渲染结果走 innerHTML，
 * 而且必须过 DOMPurify。
 */
(function () {
    'use strict';

    /* ==================== 配置 ==================== */

    // 直接双击打开 index.html（file://）时，需要显式指向后端；正常从后端访问则是同源
    const API_BASE = location.protocol === 'file:' ? 'http://localhost:8080' : '';
    const STORAGE_KEY = 'echomind.memoryId';
    const RAG_KEY = 'echomind.ragEnabled';
    const AGENT_KEY = 'echomind.agentEnabled';
    // Markdown 重渲染节流间隔：太小会白烧 CPU，太大打字机就不顺
    const RENDER_INTERVAL_MS = 50;
    // 工具返回内容短于这个长度就直接展开，长了才折叠（省得每次都要点一下）
    const STEP_FOLD_THRESHOLD = 220;

    /* ==================== DOM ==================== */

    const el = {
        app: document.querySelector('.app'),
        list: document.getElementById('messageList'),
        empty: document.getElementById('emptyState'),
        input: document.getElementById('input'),
        send: document.getElementById('sendBtn'),
        newChat: document.getElementById('newChatBtn'),
        chip: document.getElementById('sessionChip'),
        toast: document.getElementById('toast'),

        ragToggle: document.getElementById('ragToggle'),
        ragStatus: document.getElementById('ragStatus'),
        dropzone: document.getElementById('dropzone'),
        fileInput: document.getElementById('fileInput'),
        dzSub: document.getElementById('dzSub'),
        uploadLog: document.getElementById('uploadLog'),
        docList: document.getElementById('docList'),
        clearAllBtn: document.getElementById('clearAllBtn'),
        footDim: document.getElementById('footDim'),
        searchInput: document.getElementById('searchInput'),
        searchBtn: document.getElementById('searchBtn'),
        searchResult: document.getElementById('searchResult'),
        sideToggle: document.getElementById('sideToggle'),
        sideOpen: document.getElementById('sideOpen'),

        agentToggle: document.getElementById('agentToggle'),
        agentStatus: document.getElementById('agentStatus'),
        agentHint: document.getElementById('agentHint'),
        toolList: document.getElementById('toolList'),
        traceList: document.getElementById('traceList'),
        refreshTraceBtn: document.getElementById('refreshTraceBtn'),
    };

    /* ==================== 状态 ==================== */

    let memoryId = localStorage.getItem(STORAGE_KEY) || '';
    let ragEnabled = localStorage.getItem(RAG_KEY) !== 'false';
    let agentEnabled = localStorage.getItem(AGENT_KEY) !== 'false';
    let session = null;   // 当前流式会话：{ es, bubble, settled, ctxEl, agent, traceId }
    let renderTimer = null;
    let ragStatusCache = null;
    let agentStatusCache = null;

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
        return { el: bubbleEl, raw: '', item };
    }

    /* ==================== 检索上下文面板 ==================== */

    /**
     * 把后端推来的 TopK 片段渲染成一个可折叠面板。
     * 片段正文一律用 textContent 写入，不走 innerHTML —— 文档内容同样不可信。
     */
    function renderContextPanel(chunks, panelWidthHost) {
        const box = document.createElement('details');
        box.className = 'ctx';
        box.open = false;

        const summary = document.createElement('summary');
        const arrow = document.createElement('span');
        arrow.className = 'arrow';
        arrow.textContent = '▸';
        summary.appendChild(arrow);

        const title = document.createElement('span');
        if (!chunks || chunks.length === 0) {
            title.className = 'ctx-empty';
            title.textContent = '未检索到参考资料（知识库为空，或相似度都低于阈值）';
            summary.appendChild(title);
            box.appendChild(summary);
            return box;
        }

        title.textContent = '检索到的上下文';
        const badge = document.createElement('span');
        badge.className = 'ctx-badge';
        badge.textContent = chunks.length + ' 段';
        summary.appendChild(title);
        summary.appendChild(badge);

        const hint = document.createElement('span');
        hint.className = 'ctx-score';
        hint.textContent = '最高 ' + (chunks[0].score || 0).toFixed(3);
        summary.appendChild(hint);
        box.appendChild(summary);

        const list = document.createElement('div');
        list.className = 'ctx-list';
        chunks.forEach((c) => {
            const item = document.createElement('div');
            item.className = 'ctx-item'
                + (c.score >= 0.8 ? ' high' : (c.score >= 0.7 ? ' mid' : ''));

            const head = document.createElement('div');
            head.className = 'ctx-head';

            const src = document.createElement('span');
            src.className = 'ctx-src';
            src.textContent = '[' + c.index + '] ' + c.fileName + '  #片段' + c.chunkIndex;

            const score = document.createElement('span');
            score.className = 'ctx-score';
            score.textContent = Number(c.score).toFixed(3);

            head.appendChild(src);
            head.appendChild(score);

            const text = document.createElement('div');
            text.className = 'ctx-text';
            text.textContent = c.text;

            item.appendChild(head);
            item.appendChild(text);
            list.appendChild(item);
        });
        box.appendChild(list);

        if (panelWidthHost) {
            // 保持和气泡同样的宽度，视觉上对齐
        }
        return box;
    }

    /* ==================== v3.0 Agent 过程时间线 ==================== */

    /**
     * 建一条 Agent 时间线（可折叠），插在回答气泡上方。
     *
     * 为什么放在气泡<b>上面</b>：时间线记录的是"答案是怎么来的"，
     * 阅读顺序上应该先看过程再看结论。
     */
    function createAgentTimeline(info) {
        const root = document.createElement('details');
        root.className = 'agent';
        root.open = true;   // 默认展开：过程本来就是这一版要展示的重点

        const head = document.createElement('summary');
        head.className = 'agent-head';

        const badge = document.createElement('span');
        badge.className = 'agent-badge';
        badge.textContent = 'Agent';

        const meta = document.createElement('span');
        meta.className = 'agent-meta';
        meta.textContent = '正在推理…';

        head.appendChild(badge);
        head.appendChild(meta);
        root.appendChild(head);

        const steps = document.createElement('ol');
        steps.className = 'agent-steps';
        root.appendChild(steps);

        return { root, meta, steps, count: 0, info: info || {} };
    }

    /** 把一个步骤追加到时间线 */
    function appendAgentStep(timeline, step) {
        if (!timeline || !step) return;
        timeline.count += 1;

        const li = document.createElement('li');
        li.className = 'step ' + (step.type || 'thought') + (step.failed ? ' failed' : '');

        const dot = document.createElement('span');
        dot.className = 'step-dot';

        const body = document.createElement('div');
        body.className = 'step-body';

        const title = document.createElement('div');
        title.className = 'step-title';
        title.appendChild(document.createTextNode(step.title || step.type || '步骤'));
        if (step.costMs > 0) {
            const cost = document.createElement('em');
            cost.textContent = (step.costMs / 1000).toFixed(2) + 's';
            title.appendChild(cost);
        }
        body.appendChild(title);

        if (step.type === 'tool_call') {
            // 工具入参：模型决定传什么，是排查"它为什么查错了"的第一手材料
            const args = document.createElement('code');
            args.className = 'step-args';
            args.textContent = step.toolName + ' ' + (step.toolArguments || '{}');
            body.appendChild(args);
        } else if (step.type === 'tool_result') {
            const fold = document.createElement('details');
            fold.className = 'step-fold';
            const content = step.content || '(空)';
            fold.open = content.length <= STEP_FOLD_THRESHOLD;

            const summary = document.createElement('summary');
            summary.textContent = step.failed ? '查看失败信息' : '查看工具返回内容';

            const pre = document.createElement('pre');
            pre.className = 'step-pre';
            pre.textContent = content;

            fold.appendChild(summary);
            fold.appendChild(pre);
            body.appendChild(fold);
        } else if (step.content) {
            const text = document.createElement('div');
            text.className = 'step-text';
            text.textContent = step.content;
            body.appendChild(text);
        }

        li.appendChild(dot);
        li.appendChild(body);
        timeline.steps.appendChild(li);

        if (isNearBottom()) scrollToBottom();
    }

    /** traceId 太长，界面上只显示前 8 位 */
    function shortId(id) {
        return id ? String(id).slice(0, 8) : '--------';
    }

    function finishLabel(reason) {
        if (reason === 'answered') return '正常作答';
        if (reason === 'max_steps') return '达到上限，强制收尾';
        if (reason === 'error') return '执行出错';
        return reason || '-';
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

        session = { es: null, bubble, settled: false, ctxEl: null, agent: null, traceId: null };
        setStreaming(true);
        paint(bubble, true);

        const url = `${API_BASE}/api/chat/stream?message=${encodeURIComponent(content)}`
            + (memoryId ? `&memoryId=${encodeURIComponent(memoryId)}` : '')
            + `&rag=${ragEnabled ? 'true' : 'false'}`
            + `&agent=${agentEnabled ? 'true' : 'false'}`;

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

        // v2.0：检索结果先于回答到达，让用户一边看模型吐字一边核对参考了什么
        es.addEventListener('context', (e) => {
            let chunks = [];
            try {
                chunks = JSON.parse(e.data);
            } catch (err) {
                chunks = [];
            }
            if (!Array.isArray(chunks)) chunks = [];
            const panel = renderContextPanel(chunks);
            session.bubble.item.appendChild(panel);
            session.ctxEl = panel;
            if (isNearBottom()) scrollToBottom();
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

        // v3.0：Agent 开始推理 —— 建时间线，插在回答气泡上方
        es.addEventListener('agent_start', (e) => {
            const info = parseData(e.data) || {};
            session.agent = createAgentTimeline(info);
            session.traceId = info.traceId || null;
            session.bubble.item.insertBefore(session.agent.root, session.bubble.el);
            session.agent.meta.textContent = '#' + shortId(info.traceId)
                + ' · 最多 ' + info.maxSteps + ' 轮 · 可用工具 ' + info.toolCount + ' 个'
                + ' · 正在推理…';
            if (isNearBottom()) scrollToBottom();
        });

        // v3.0：Agent 中间某一步
        es.addEventListener('agent_step', (e) => {
            if (!session || !session.agent) return;
            appendAgentStep(session.agent, parseData(e.data) || {});
        });

        // v3.0：Agent 收尾，把统计信息补到时间线头部，并刷新侧栏的"最近运行"
        es.addEventListener('agent_done', (e) => {
            const info = parseData(e.data) || {};
            session.traceId = info.traceId || session.traceId;
            if (session && session.agent) {
                session.agent.meta.textContent = '#' + shortId(info.traceId)
                    + ' · ' + info.stepCount + ' 轮 · ' + info.toolCallCount + ' 次工具 · '
                    + (Number(info.costMs || 0) / 1000).toFixed(1) + 's · '
                    + finishLabel(info.finishReason)
                    + (info.truncated ? ' · 已截断' : '');
            }
            // trace 已经写进 Redis 了，顺手刷新侧栏列表 —— 这本身就是持久化的验证
            refreshTraces();
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

    /* ==================== 请求小工具 ==================== */

    /** 统一处理：非 2xx 时把后端返回的 {error: "..."} 抛出来，方便在界面上显示原因 */
    async function api(path, options) {
        const res = await fetch(API_BASE + path, options);
        const text = await res.text();
        let body = null;
        if (text) {
            try {
                body = JSON.parse(text);
            } catch (e) {
                body = text;
            }
        }
        if (!res.ok) {
            const message = (body && body.error) ? body.error : ('HTTP ' + res.status);
            throw new Error(message);
        }
        return body;
    }

    /* ==================== 知识库 ==================== */

    async function refreshStatus() {
        try {
            const s = await api('/api/rag/status');
            ragStatusCache = s;
            renderStatus(s);
            el.footDim.textContent = s.dimension;
        } catch (e) {
            el.ragStatus.innerHTML = '';
            el.ragStatus.appendChild(kvRow('状态', '后端未连接', 'off'));
        }
    }

    function renderStatus(s) {
        el.ragStatus.innerHTML = '';

        let stateText = '就绪';
        let stateCls = 'ok';
        if (!s.enabled) {
            stateText = '已关闭';
            stateCls = 'off';
        } else if (!s.ready) {
            stateText = '无内容';
            stateCls = 'warn';
        }

        el.ragStatus.appendChild(kvRow('状态', stateText, stateCls));
        el.ragStatus.appendChild(kvRow('向量库', s.vectorStore, ''));
        // 会话记忆落点：Redis 正常时是 "Redis"，Redis 挂了会变成 "进程内存（降级）"
        el.ragStatus.appendChild(kvRow('会话记忆', s.memoryStore || '-',
            (s.memoryStore || '').indexOf('内存') >= 0 ? 'warn' : ''));
        el.ragStatus.appendChild(kvRow('Embedding', s.embeddingModel || '-', ''));
        el.ragStatus.appendChild(kvRow('维度', String(s.dimension), ''));
        el.ragStatus.appendChild(kvRow('文档 / 切片', s.documentCount + ' / ' + s.chunkCount, ''));
        el.ragStatus.appendChild(kvRow('TopK / 阈值',
            s.topK + ' / ' + Number(s.minScore).toFixed(2), ''));

        // 降级到内存时，把原因摆出来 —— 否则用户会困惑"为什么重启后文档没了"
        if (s.vectorStore.indexOf('内存') >= 0) {
            const tip = document.createElement('p');
            tip.className = 'warn-text';
            tip.textContent = '向量库已降级为进程内存：' + (s.detail || '')
                + ' 重启应用后文档会丢失。';
            el.ragStatus.appendChild(tip);
        }

        // 会话记忆降级：多轮对话仍然能用，但历史不跨重启
        if ((s.memoryStore || '').indexOf('内存') >= 0) {
            const tip = document.createElement('p');
            tip.className = 'warn-text';
            tip.textContent = '会话记忆已降级为进程内存：Redis 不可达，多轮上下文仍可用，'
                + '但重启后历史对话会丢失。';
            el.ragStatus.appendChild(tip);
        }
    }

    function kvRow(label, value, cls) {
        const row = document.createElement('div');
        row.className = 'kv-row';
        const l = document.createElement('span');
        l.textContent = label;
        const v = document.createElement('b');
        if (cls) {
            v.className = 'badge ' + cls;
        }
        v.textContent = value;
        row.appendChild(l);
        row.appendChild(v);
        return row;
    }

    async function refreshDocuments() {
        try {
            const docs = await api('/api/documents');
            renderDocuments(Array.isArray(docs) ? docs : []);
        } catch (e) {
            renderDocuments([]);
        }
    }

    function renderDocuments(docs) {
        el.docList.innerHTML = '';
        if (docs.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'doc-empty';
            empty.textContent = '还没有文档';
            el.docList.appendChild(empty);
            return;
        }
        docs.forEach((doc) => {
            const item = document.createElement('div');
            item.className = 'doc-item';

            const icon = document.createElement('span');
            icon.className = 'doc-icon';
            icon.textContent = doc.fileType === 'pdf' ? '📕' : (doc.fileType === 'markdown' ? '📘' : '📄');

            const main = document.createElement('div');
            main.className = 'doc-main';

            const name = document.createElement('div');
            name.className = 'doc-name';
            name.textContent = doc.fileName;

            const meta = document.createElement('div');
            meta.className = 'doc-meta';
            meta.textContent = doc.chunkCount + ' 片 · ' + doc.charCount + ' 字 · '
                + formatSize(doc.size) + ' · ' + doc.dimension + ' 维';

            main.appendChild(name);
            main.appendChild(meta);

            const del = document.createElement('button');
            del.className = 'doc-del';
            del.type = 'button';
            del.title = '删除该文档';
            del.textContent = '✕';
            del.addEventListener('click', () => removeDocument(doc.fileName));

            item.appendChild(icon);
            item.appendChild(main);
            item.appendChild(del);
            el.docList.appendChild(item);
        });
    }

    function formatSize(bytes) {
        if (!bytes && bytes !== 0) return '-';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    }

    async function removeDocument(fileName) {
        if (!confirm('确定删除《' + fileName + '》及其全部切片？')) return;
        try {
            await api('/api/documents?fileName=' + encodeURIComponent(fileName), { method: 'DELETE' });
            toast('已删除：' + fileName);
            await Promise.all([refreshDocuments(), refreshStatus()]);
        } catch (e) {
            toast('删除失败：' + e.message);
        }
    }

    async function clearAll() {
        if (!confirm('确定清空整个知识库？所有文档切片都会被删除。')) return;
        try {
            await api('/api/documents/all', { method: 'DELETE' });
            toast('知识库已清空');
            await Promise.all([refreshDocuments(), refreshStatus()]);
        } catch (e) {
            toast('清空失败：' + e.message);
        }
    }

    async function uploadFiles(files) {
        const list = Array.from(files || []);
        if (list.length === 0) return;

        el.dropzone.classList.add('busy');
        el.dzSub.textContent = '处理中…（本地 BGE 向量化，首次会稍慢）';

        for (const file of list) {
            const form = new FormData();
            form.append('file', file);
            try {
                const r = await api('/api/documents', { method: 'POST', body: form });
                logUpload(true, file.name,
                    r.charCount + ' 字 → ' + r.chunkCount + ' 片 → ' + r.dimension + ' 维'
                    + '，写入 ' + r.vectorStore + '，耗时 ' + r.costMs + ' ms');
            } catch (e) {
                logUpload(false, file.name, e.message);
            }
        }

        el.dropzone.classList.remove('busy');
        el.dzSub.textContent = '解析 → 切片 → 向量化 → 写入向量库';
        await Promise.all([refreshDocuments(), refreshStatus()]);
    }

    function logUpload(ok, fileName, detail) {
        const item = document.createElement('div');
        item.className = 'log-item ' + (ok ? 'ok' : 'err');

        const title = document.createElement('div');
        title.textContent = (ok ? '✓ ' : '✗ ') + fileName;

        const sub = document.createElement('div');
        sub.className = 'muted';
        sub.textContent = detail;

        item.appendChild(title);
        item.appendChild(sub);
        el.uploadLog.prepend(item);

        // 只留最近 6 条，避免面板被撑爆
        while (el.uploadLog.children.length > 6) {
            el.uploadLog.removeChild(el.uploadLog.lastChild);
        }
    }

    async function runSearch() {
        const question = el.searchInput.value.trim();
        if (!question) {
            toast('先输入要检索的内容');
            return;
        }
        el.searchResult.innerHTML = '';
        const loading = document.createElement('div');
        loading.className = 'doc-empty';
        loading.textContent = '检索中…';
        el.searchResult.appendChild(loading);

        try {
            const chunks = await api('/api/rag/search?question=' + encodeURIComponent(question));
            el.searchResult.innerHTML = '';
            if (!chunks || chunks.length === 0) {
                const none = document.createElement('div');
                none.className = 'doc-empty';
                none.textContent = '没有命中任何片段';
                el.searchResult.appendChild(none);
                return;
            }
            el.searchResult.appendChild(renderContextPanel(chunks));
        } catch (e) {
            el.searchResult.innerHTML = '';
            const err = document.createElement('div');
            err.className = 'log-item err';
            err.textContent = '检索失败：' + e.message;
            el.searchResult.appendChild(err);
        }
    }

    /* ==================== v3.0 Agent 面板 ==================== */

    async function refreshAgentStatus() {
        try {
            const s = await api('/api/agent/status');
            agentStatusCache = s;
            renderAgentStatus(s);
            renderToolList(s.tools || []);
        } catch (e) {
            // 后端没起时不要留一片空白，明确写出来
            el.agentStatus.innerHTML = '';
            el.agentStatus.appendChild(kvRow('状态', '后端未连接', 'off'));
            el.agentHint.textContent = '';
            el.toolList.innerHTML = '';
        }
    }

    function renderAgentStatus(s) {
        el.agentStatus.innerHTML = '';
        el.agentHint.textContent = s.enabled ? '运行中' : '已关闭';

        el.agentStatus.appendChild(kvRow('状态', s.enabled ? '启用' : '已关闭', s.enabled ? 'ok' : 'off'));
        el.agentStatus.appendChild(kvRow('最大轮数 / 工具次数', s.maxSteps + ' / ' + s.maxToolCalls, ''));
        el.agentStatus.appendChild(kvRow('决策温度', Number(s.temperature).toFixed(2), ''));
        el.agentStatus.appendChild(kvRow('已注册工具', s.toolCount + ' 个', ''));
        // 过程记录落点：Redis 正常时是 "Redis"，Redis 挂了会变成 "进程内存（降级）"
        el.agentStatus.appendChild(kvRow('过程记录', s.traceStore || '-',
            (s.traceStore || '').indexOf('内存') >= 0 ? 'warn' : ''));

        // 跨语言链路：Python 工具服务通不通
        const py = s.python || {};
        const pyOff = py.enabled === false;
        el.agentStatus.appendChild(kvRow('Python 服务',
            pyOff ? '未启用' : (py.reachable ? '已连通' : '连不上'),
            pyOff ? 'off' : (py.reachable ? 'ok' : 'warn')));

        if (!pyOff && !py.reachable) {
            const tip = document.createElement('p');
            tip.className = 'warn-text';
            tip.textContent = '订单类工具会失败（天气等 Java 本地工具不受影响）：'
                + (py.detail || '');
            el.agentStatus.appendChild(tip);
        }
        if ((s.traceStore || '').indexOf('内存') >= 0) {
            const tip = document.createElement('p');
            tip.className = 'warn-text';
            tip.textContent = 'Agent 过程记录已降级为进程内存：Redis 不可达，重启后读不到历史运行。';
            el.agentStatus.appendChild(tip);
        }
    }

    function renderToolList(tools) {
        el.toolList.innerHTML = '';
        if (!tools.length) return;

        tools.forEach((t) => {
            const isPython = (t.source || '').indexOf('Python') >= 0;

            const item = document.createElement('div');
            item.className = 'tool-item';
            item.title = (t.group || '') + ' · ' + (t.description || '');

            const glyph = document.createElement('span');
            glyph.className = 'tool-glyph';
            glyph.textContent = isPython ? '🐍' : '⚙';

            const body = document.createElement('div');
            body.className = 'tool-body';

            const name = document.createElement('div');
            name.className = 'tool-name';
            name.textContent = t.label + ' ';
            const code = document.createElement('span');
            code.className = 'tool-code';
            code.textContent = t.name;
            name.appendChild(code);

            const desc = document.createElement('div');
            desc.className = 'tool-desc';
            desc.textContent = firstSentence(t.description);

            const src = document.createElement('span');
            src.className = 'tool-src' + (isPython ? ' python' : '');
            src.textContent = t.source || 'Java 本地';

            body.appendChild(name);
            body.appendChild(desc);
            body.appendChild(src);
            item.appendChild(glyph);
            item.appendChild(body);
            el.toolList.appendChild(item);
        });
    }

    /** 工具说明往往是一段长文，列表里只取第一句 */
    function firstSentence(text) {
        if (!text) return '';
        const one = String(text).replace(/\s+/g, ' ').trim();
        const cut = one.split(/[。；]/)[0] || one;
        return cut.length > 58 ? cut.slice(0, 58) + '…' : cut;
    }

    /**
     * 拉取本会话最近的 Agent 运行记录。
     *
     * 这些记录存在 Redis 里（不是浏览器内存），所以<b>刷新页面甚至重启后端之后仍然在</b> ——
     * 这一点是 v3.0「Redis 完善多轮状态管理」最直接的验证方式。
     */
    async function refreshTraces() {
        if (!memoryId) {
            renderTraces([]);
            return;
        }
        try {
            const list = await api('/api/agent/traces/' + encodeURIComponent(memoryId) + '?limit=5');
            renderTraces(Array.isArray(list) ? list : []);
        } catch (e) {
            renderTraces([]);
        }
    }

    function renderTraces(list) {
        el.traceList.innerHTML = '';
        if (!list.length) {
            const empty = document.createElement('div');
            empty.className = 'doc-empty';
            empty.textContent = memoryId ? '这个会话还没有运行记录' : '先发一条消息试试';
            el.traceList.appendChild(empty);
            return;
        }

        list.forEach((t) => {
            const item = document.createElement('div');
            item.className = 'trace-item';
            item.title = 'traceId：' + t.traceId + '\n问题：' + (t.question || '');

            const question = document.createElement('div');
            question.className = 'trace-q';
            question.textContent = t.question || '(无)';

            const meta = document.createElement('div');
            meta.className = 'trace-meta';
            meta.textContent = '#' + shortId(t.traceId) + ' · ' + t.stepCount + ' 轮 · '
                + t.toolCallCount + ' 次工具 · '
                + (Number(t.costMs || 0) / 1000).toFixed(1) + 's · '
                + finishLabel(t.finishReason);

            item.appendChild(question);
            item.appendChild(meta);
            el.traceList.appendChild(item);
        });
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
        toastTimer = setTimeout(() => el.toast.classList.remove('show'), 2400);
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
            // 顺手把服务端的两份状态也清掉，别在 Redis 里留垃圾：
            // 一份是会话记忆（多轮上下文），一份是 Agent 运行记录（中间过程）
            fetch(`${API_BASE}/api/chat/memory/${encodeURIComponent(old)}`, { method: 'DELETE' })
                .catch(() => { /* 后端没起也无所谓 */ });
            fetch(`${API_BASE}/api/agent/traces/${encodeURIComponent(old)}`, { method: 'DELETE' })
                .catch(() => { /* 同上 */ });
        }
        renderTraces([]);
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

    /* ==================== 拖拽上传 ==================== */

    ['dragenter', 'dragover'].forEach((type) => {
        el.dropzone.addEventListener(type, (e) => {
            e.preventDefault();
            el.dropzone.classList.add('dragover');
        });
    });

    ['dragleave', 'drop'].forEach((type) => {
        el.dropzone.addEventListener(type, (e) => {
            e.preventDefault();
            el.dropzone.classList.remove('dragover');
        });
    });

    el.dropzone.addEventListener('drop', (e) => {
        if (e.dataTransfer && e.dataTransfer.files) {
            uploadFiles(e.dataTransfer.files);
        }
    });

    el.dropzone.addEventListener('click', () => el.fileInput.click());
    el.fileInput.addEventListener('change', () => {
        uploadFiles(el.fileInput.files);
        el.fileInput.value = '';
    });

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

    el.ragToggle.checked = ragEnabled;
    el.ragToggle.addEventListener('change', () => {
        ragEnabled = el.ragToggle.checked;
        localStorage.setItem(RAG_KEY, String(ragEnabled));
        toast(ragEnabled ? '已启用检索增强' : '已关闭检索，退化为纯对话');
        refreshStatus();
    });

    el.agentToggle.checked = agentEnabled;
    el.agentToggle.addEventListener('change', () => {
        agentEnabled = el.agentToggle.checked;
        localStorage.setItem(AGENT_KEY, String(agentEnabled));
        toast(agentEnabled
            ? '已启用 Agent：模型会自己决定调哪个工具'
            : '已关闭 Agent：退化为 v2.0 的单轮回答');
        refreshAgentStatus();
    });

    el.refreshTraceBtn.addEventListener('click', () => {
        refreshTraces().then(() => toast('已刷新运行记录'));
    });

    el.clearAllBtn.addEventListener('click', clearAll);
    el.searchBtn.addEventListener('click', runSearch);
    el.searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            runSearch();
        }
    });

    el.sideToggle.addEventListener('click', () => el.app.classList.remove('narrow-open'));
    el.sideOpen.addEventListener('click', () => el.app.classList.add('narrow-open'));

    // 页面关闭前主动断开，避免后端为了一个已经没人听的连接继续调模型
    window.addEventListener('beforeunload', () => {
        if (session && session.es) session.es.close();
    });

    /* ==================== 启动 ==================== */

    updateChip();
    autoGrow();
    loadHistory();
    refreshStatus();
    refreshDocuments();
    refreshAgentStatus();
    refreshTraces();
    el.input.focus();
})();
