# EchoMind

EchoMind 是一个基于 Spring Boot 和 LangChain4j 的流式 AI 对话应用。当前已跟踪的后端代码包含多轮会话记忆、文档检索增强（RAG）以及本地中文文本向量化；静态聊天页面位于 `src/main/resources/static`。

## 已实现功能

- 通过 Server-Sent Events（SSE）返回流式聊天响应，并按会话 ID 保存最多 20 条消息。
- 可选的 RAG 文档预检索；支持 PDF、Markdown、TXT 及常见纯文本格式（如 CSV、JSON、YAML、LOG）。
- 使用本地量化版 BGE-small-zh-v1.5 模型生成 512 维向量，不需要 Embedding API Key。
- 向量库支持 Milvus；默认 `auto` 模式连接失败时降级为进程内存存储。也可强制使用 Milvus 或仅用内存。
- 会话记忆优先使用 Redis；Redis 不可用时降级为进程内存。
- 提供文档上传、列表、删除、清空、RAG 状态和检索调试接口。

## 技术栈

- Java 17、Maven、Spring Boot 4.0.8
- LangChain4j 1.19.0-beta29、OpenAI 兼容的流式聊天模型
- 本地 ONNX BGE Embedding、Milvus（可选）、Redis（可选）
- 原生 HTML、CSS、JavaScript；Markdown 渲染及代码高亮依赖的前端库已随项目本地提供

## 环境准备

1. 安装 JDK 17 和 Maven。
2. 准备一个 LangChain4j OpenAI 兼容的聊天模型及其 API Key。
3. （可选）准备 Redis 和 Milvus。默认 Milvus 地址为 `192.168.77.140:19530`；若该地址不可用，默认配置会退回内存向量库。使用 `memory` 模式不需要 Milvus。

仓库没有提供 `application.yml`。启动前可创建 `src/main/resources/application.yml`，按实际服务填写聊天模型和中间件连接信息，例如：

```yaml
langchain4j:
  open-ai:
    streaming-chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: ${OPENAI_MODEL_NAME}
      # 使用兼容服务时按服务商要求设置：
      # base-url: ${OPENAI_BASE_URL}

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

echomind:
  rag:
    store: ${ECHOMIND_RAG_STORE:auto} # auto、milvus 或 memory
    milvus:
      host: ${MILVUS_HOST:192.168.77.140}
      port: ${MILVUS_PORT:19530}
```

聊天模型的 API Key 只用于聊天生成；当前 Embedding 模型在本地运行。不要将真实密钥提交到版本库。RAG 其他可配置项使用 `echomind.rag` 前缀，默认值见下表。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `echomind.rag.enabled` | `true` | RAG 总开关 |
| `echomind.rag.store` | `auto` | `auto`、`milvus` 或 `memory` |
| `echomind.rag.top-k` | `5` | 检索片段数 |
| `echomind.rag.min-score` | `0.6` | 检索相似度下限 |
| `echomind.rag.max-segment-size` | `400` | 文档切片最大 token 数 |
| `echomind.rag.max-overlap-size` | `80` | 相邻切片重叠 token 数 |
| `echomind.rag.max-file-size` | `20MB` | 单个上传文件大小上限 |
| `echomind.rag.registry-path` | `data/documents.json` | 文档登记信息文件 |
| `echomind.rag.milvus.database-name` | `default` | Milvus 数据库 |
| `echomind.rag.milvus.collection-name` | `echomind_vectors` | Milvus collection |

## 构建与启动

```bash
mvn spring-boot:run
```

应用默认使用 Spring Boot 的 HTTP 端口 `8080`。静态页面地址为 `http://localhost:8080/`。内存向量库和内存会话记忆都只在当前进程有效；使用内存向量库时，重启后需要重新上传文档。文档登记簿默认写入 `data/documents.json`。

## 已实现的主要 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/chat/stream?message=...` | SSE 流式对话；可选 `memoryId`、`rag`、`agent` 参数 |
| `GET` | `/api/chat/memory/{memoryId}` | 读取会话历史 |
| `DELETE` | `/api/chat/memory/{memoryId}` | 清空会话记忆 |
| `POST` | `/api/documents` | 以 multipart 字段 `file` 上传并索引文档 |
| `GET` | `/api/documents` | 列出已登记文档 |
| `DELETE` | `/api/documents?fileName=...` | 删除指定文档及其向量 |
| `DELETE` | `/api/documents/all` | 清空文档和向量 |
| `GET` | `/api/rag/status` | 查看 RAG、向量库和记忆存储状态 |
| `GET` | `/api/rag/search?question=...` | 检索调试，不调用聊天模型 |

## 项目结构

```text
src/
├── main/
│   ├── java/com/example/echomind/
│   │   ├── config/       # Spring、LangChain4j、RAG 配置
│   │   ├── controller/   # 聊天与文档 API
│   │   ├── dto/          # API 数据对象
│   │   ├── memory/       # Redis 会话记忆及回退实现
│   │   ├── rag/          # 文档解析、向量化、检索与登记
│   │   └── service/      # 聊天服务与提示词
│   └── resources/static/ # 单页前端、样式、脚本和本地 vendor 资源
└── test/java/            # Spring 上下文测试
pom.xml                   # Maven 依赖与 Java 版本
```
