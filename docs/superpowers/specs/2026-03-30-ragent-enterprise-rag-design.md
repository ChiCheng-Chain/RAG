# Ragent 企业级 RAG 智能体系统架构设计文档

## 文档概述

本文档详细描述 Ragent 项目的整体架构设计、业务流程、数据模型、核心模块实现以及企业实践要点。本文档基于 Ragent 开源项目源码分析整理，旨在帮助开发者深入理解企业级 RAG 系统的设计与实现，为从零开始构建类似系统提供完整的指导。

**核心变更说明**：原始项目使用 Milvus 作为向量数据库、RocketMQ 作为消息队列。本设计文档将向量存储改为 PostgreSQL 配合 pgvector 扩展，消息队列改为 Spring Boot 内置的 Events/Integration 机制，以简化部署架构、降低运维复杂度。

---

## 第一章 项目概览与定位

### 1.1 项目定位

Ragent 是一个企业级 RAG（Retrieval-Augmented Generation，检索增强生成）智能体平台，基于 Java 17 + Spring Boot 3 + React 18 技术栈构建。该项目并非简单的 Demo 级实现，而是覆盖了 RAG 系统从文档入库到智能问答全链路的完整工程实现，具备生产级系统的所有核心特征。

Ragent 解决的问题域包括：企业知识库智能问答、文档自动入库与向量化、多路检索与意图识别、模型路由与容错、会话记忆管理、MCP 工具集成以及全链路追踪可观测性。

### 1.2 核心技术栈

Ragent 项目的技术选型体现了企业级系统的标准要求，具体技术栈如下：

| 技术层面 | 选型方案 | 说明 |
|---------|---------|------|
| 后端框架 | Java 17 + Spring Boot 3.5.7 | Java 17 提供_records_、模式匹配等新特性，Spring Boot 3 最低支持 Java 17 |
| 前端框架 | React 18 + Vite + TypeScript | 现代前端工程化实践，Vite 提供极速开发体验 |
| 关系数据库 | PostgreSQL 15+ | 本设计使用 PostgreSQL 替代 MySQL，统一存储业务数据与向量数据 |
| 向量存储 | PostgreSQL + pgvector | 使用 pgvector 扩展实现向量存储与相似度检索，替代原始 Milvus |
| 缓存与限流 | Redis + Redisson | 分布式缓存与限流控制 |
| 对象存储 | S3 兼容存储（MinIO/RustFS） | 文档文件存储 |
| 消息队列 | Spring Events / Spring Integration | 异步任务与事件通知，使用 Spring Boot 内置机制 |
| 文档解析 | Apache Tika 3.2 | 通用文档解析框架，支持 PDF、Word、PPT 等格式 |
| 模型供应商 | 百炼（阿里云）、SiliconFlow、Ollama | 多模型接入，支持后续扩展 vLLM |
| 认证鉴权 | Sa-Token | 轻量级权限认证框架 |
| 代码规范 | Spotless | 代码自动格式化 |

### 1.3 核心能力矩阵

Ragent 项目的核心能力可以从六个维度进行理解：

**多路检索引擎**：Ragent 采用意图定向检索与全局向量检索并行执行的架构，检索结果经过去重、重排序等后处理步骤，兼顾精准度与召回率。这种设计解决了单一检索方式无法覆盖所有查询类型的问题。

**意图识别与引导**：系统实现了树形多级意图分类体系，包含领域（Domain）、类目（Category）、话题（Topic）三个层级。当意图识别置信度不足时，系统会主动引导用户澄清问题，而非硬猜答案，显著提升了用户体验。

**问题重写与拆分**：在多轮对话场景中，系统会自动补全上下文，将复杂问题拆分为多个子问题分别检索，解决用户表达不完整导致的检索偏差问题。

**会话记忆管理**：系统保留最近 N 轮对话历史，超过限制后自动进行摘要压缩，有效控制 Token 成本的同时不丢失关键上下文信息。

**模型路由与容错**：Ragent 实现了一套完整的多模型优先级调度机制，包含首包探测、健康检查、自动降级等功能。即使单个模型出现故障，系统也能自动切换到候选模型，保证服务可用性。

**MCP 工具集成**：当用户意图不属于知识库检索范畴时，系统可以自动识别并调用 MCP（Model Context Protocol）协议定义的业务工具，实现检索与工具调用的无缝融合。

---

## 第二章 企业级架构设计

### 2.1 整体架构分层

Ragent 采用前后端分离的单体架构，后端按照职责分为四个 Maven 模块，形成清晰的分层结构。这种分层设计不是为了炫技，而是解决实际的工程问题：framework 层提供与业务无关的通用能力，infra-ai 层屏蔽不同模型供应商的差异，bootstrap 层专注业务逻辑。换模型供应商不用改业务代码，换业务逻辑不用动基础设施。

```
┌─────────────────────────────────────────────────────────────┐
│                      接入层 (Frontend)                        │
│         React + Vite + TS (用户问答 + 管理后台)               │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     应用编排层 (Bootstrap)                    │
│     RAG 主链路 | 文档入库流水线 | 知识库管理 | 对话服务        │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────────────┐
│   基础设施层 (Framework) │     │    AI 基础设施层 (Infra-AI)      │
│ 统一响应 | 异常体系 |    │     │ Chat | Embedding | Rerank |    │
│ 链路追踪 | 限流 | 幂等   │     │        模型路由                  │
└─────────────────────────┘     └─────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      资源层 (Resources)                       │
│         Docker Compose | PostgreSQL | Redis | MinIO          │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 模块职责详解

#### 2.2.1 接入层（Frontend）

接入层包含两个核心入口：面向普通用户的前端应用和面向开发者的 MCP Server。前端应用基于 React 18 + Vite + TypeScript 构建，提供用户问答界面和管理后台。用户问答界面支持自然语言输入、Markdown 渲染、代码高亮、回答评价等功能；管理后台覆盖仪表板、知识库管理、意图树编辑、入库监控、链路追踪、用户管理、系统设置等 22 个页面。

MCP Server 是系统的扩展模块，通过 MCP 协议与外部工具集成，支持自定义工具的注册与调用。

#### 2.2.2 应用层（Bootstrap）

Bootstrap 模块是整个系统的业务逻辑核心，所有与 RAG 相关的业务逻辑都封装在这里。该模块包含以下核心功能域：

RAG 主链路实现了完整的七步对话流程，从会话初始化到流式输出，每个环节都有清晰的职责划分。文档入库流水线实现了节点编排引擎，支持Fetcher、Parser、Enhancer、Chunker、Enricher、Indexer 六个标准节点的可配置执行。知识库管理提供知识库创建、文档上传、意图树配置、入库任务监控等完整的管理能力。

会话管理负责对话状态的维护，包括历史消息加载、记忆压缩、摘要生成等。会话记忆采用滑动窗口机制，保留最近 N 轮对话，超出限制后自动调用 LLM 生成摘要，实现上下文的长距离保持。

#### 2.2.3 基础设施层（Framework）

Framework 模块封装了与业务无关的通用能力，是整个系统的技术基础设施。该模块包含十大横切关注点：

三级异常体系将异常分为业务异常（BusinessException）、参数异常（ParamException）、系统异常（SystemException），配合统一的异常拦截器实现全局异常处理。双维度幂等基于 Redis + 数据库实现接口调用的幂等性保证。Snowflake 分布式 ID 算法生成全局唯一 ID。用户上下文与 Trace 上下文通过 ThreadLocal 存储，并在异步线程中通过 TransmittableThreadLocal 实现跨线程透传。SseEmitterSender 提供线程安全的 SSE 推送封装。

统一响应体与错误码规范定义了 RestResponse 响应包装类和错误码枚举。队列式并发限流基于 Redis 信号量实现分布式限流，支持全局限流和用户级限流。8 个专用线程池分别处理不同类型的工作负载，包括 MCP 批量调用、RAG 上下文组装、多路检索等。

#### 2.2.4 AI 基础设施层（Infra-AI）

Infra-AI 模块封装了所有与 AI 模型交互的能力，包括：

Chat 模块提供统一的对话接口，支持流式输出。Embedding 模块提供文本向量化能力，支持多模型路由。Rerank 模块提供结果重排序能力。Model Routing 模块实现模型选择、优先级调度、健康检查、熔断降级等能力。

这种设计使得业务层无需关心底层模型供应商的差异，换模型只需修改配置，业务代码无需改动。

### 2.3 部署拓扑架构

Ragent 项目的部署架构采用 Docker Compose 或 Kubernetes 方式，以下是基于 Docker Compose 的部署拓扑：

```
┌──────────────────────────────────────────────────────────────────┐
│                        用户浏览器 / API 客户端                     │
└──────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Ragent Backend (Spring Boot)                  │
│                      端口: 8080                                   │
└──────────────────────────────────────────────────────────────────┘
           │                    │                    │
           ▼                    ▼                    ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│   PostgreSQL      │  │      Redis       │  │      MinIO       │
│   端口: 5432      │  │    端口: 6379    │  │   端口: 9000     │
│   (数据+向量)      │  │  (缓存+限流)     │  │  (文件存储)      │
└──────────────────┘  └──────────────────┘  └──────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────────┐
│                  模型服务 (外部供应商 / 本地部署)                  │
│         百炼 API | SiliconFlow API | Ollama | vLLM              │
└──────────────────────────────────────────────────────────────────┘
```

---

## 第三章 核心业务流程

### 3.1 RAG 七步对话流程

Ragent 的核心对话流程包含七个关键步骤，每个步骤都有明确的职责和扩展点：

**第一步：加载会话记忆并补入当前问题**

当用户发起对话请求时，系统首先根据会话 ID 加载历史对话记录。会话记忆服务会检索最近 N 轮对话内容，并将其与当前问题一起传递给后续处理流程。如果历史对话超出窗口限制，系统会自动调用 LLM 生成会话摘要，将压缩后的摘要作为上下文保留。

**第二步：查询改写与子问题拆分**

QueryRewriteService 负责对用户问题进行预处理。改写操作包括：补全多轮对话中的省略部分（如用户说"怎么申请"，系统补充为"XXX怎么申请"）、同义词归一化、拼写纠错等。拆分操作将复杂问题分解为多个简单的子问题，例如"请介绍一下 Ragent 的架构设计和技术选型"会被拆分为"请介绍一下 Ragent 的架构设计"和"Ragent 的技术选型是什么"两个子问题。

**第三步：意图识别与歧义引导**

IntentResolver 负责识别用户问题的意图类别。系统维护一棵意图树，包含领域（Domain）、类目（Category）、话题（Topic）三个层级。每个意图节点关联一个或多个知识库 Collection，并可配置 TopK、Prompt 模板等参数。

意图识别过程使用向量相似度计算，将用户问题转换为向量后与各意图节点的示例问题向量进行比对，得到置信度分数。如果最高置信度低于预设阈值（默认 0.5），系统会返回引导性问题，要求用户澄清。例如用户问"怎么处理"，系统可能返回"您是想处理文档还是处理订单？"。

**第四步：检索引擎并行执行**

RetrievalEngine 是 RAG 系统的核心，负责执行多通道检索。系统支持两种检索通道：

IntentDirectedSearchChannel（意图定向检索）：根据意图识别结果，定向检索对应知识库 Collection。这种方式检索结果精准度高，适合明确意图的查询。

VectorGlobalSearchChannel（全局向量检索）：不依赖意图识别，直接在所有知识库中进行向量相似度检索。这种方式召回率高，作为置信度不足时的兜底方案。

两种通道通过线程池并行执行，互不阻塞。检索结果经过 DeduplicationPostProcessor（去重）和 RerankPostProcessor（重排序）后处理后，返回最相关的 TopK 结果。

**第五步：组装提示词上下文**

RAGPromptService 负责将系统设定、对话历史、用户问题、检索结果组装成完整的 Prompt。Prompt 模板支持变量替换，包括 $context$（检索上下文）、$history$（对话历史）、$question$（当前问题）等占位符。

**第六步：模型路由与流式返回**

RoutingLLMService 负责选择合适的模型并发起流式请求。模型选择遵循以下策略：优先选择优先级最高的模型；如果首包探测超时（默认 60 秒），自动切换到下一个候选模型；如果调用失败（如 Token 不够），记录失败并尝试下一个模型。

系统为每个模型维护独立的健康状态，使用三态熔断器（CLOSED → OPEN → HALF_OPEN）进行故障隔离。失败次数达到阈值后自动熔断，冷却期后进入半开状态放行探测请求。

**第七步：写入会话、反馈与追踪**

对话完成后，系统执行以下异步操作：SSE 流式推送确保前端实时接收数据；消息持久化将对话记录保存到数据库；标题生成为本次对话生成简洁标题；Trace 记录保存全链路追踪日志。

### 3.2 RAG 链路时序图

以下是用户发起一次对话请求的完整时序图：

```
用户 → RAGChatService → MemoryService → QueryRewriteService → IntentResolver
                                                                      │
                                                                      ▼
                                                           [意图识别 + 歧义引导判断]
                                                              │           │
                                                              ▼           ▼
                                                       [引导用户]    [继续流程]
                                                              │           │
                                                              ▼           ▼
                                                      RetrievalEngine
                                                              │
                                    ┌─────────────────────────┼─────────────────────────┐
                                    ▼                         ▼                         ▼
                         IntentDirectedSearch        VectorGlobalSearch           MCP工具
                                    │                         │                         │
                                    └─────────────────────────┼─────────────────────────┘
                                                              ▼
                                                    [去重 + 重排序后处理]
                                                              │
                                                              ▼
                                                    RAGPromptService
                                                              │
                                                              ▼
                                                    RoutingLLMService
                                                              │
                                    ┌─────────────────────────┼─────────────────────────┐
                                    ▼                         ▼                         ▼
                               模型候选1                  模型候选2                 模型候选3
                                    │                         │                         │
                                    └─────────────────────────┼─────────────────────────┘
                                                              │
                                                              ▼
                                                    [流式输出 + 异步持久化]
```

### 3.3 核心服务职责

**RAGChatService**：对话服务入口，协调各组件完成完整对话流程。核心方法 streamChat 接收用户问题、会话ID、深度思考开关等参数，返回 SseEmitter 实现流式输出。

**ConversationMemoryService**：会话记忆管理。loadAndAppend 方法加载历史对话并追加当前消息；压缩机制在超过窗口大小时调用 LLM 生成摘要。

**QueryRewriteService**：问题改写与拆分。rewriteWithSplit 方法返回 RewriteResult，包含改写后的问题和拆分的子问题列表。

**IntentResolver**：意图识别与分类。resolve 方法接收 RewriteResult，返回 SubQuestionIntent 列表，每个子问题对应一组可能的意图节点及置信度分数。

**RetrievalEngine**：检索引擎核心。retrieve 方法接收子问题意图列表和 TopK，返回 RetrievalContext 包含知识库上下文、MCP 上下文和分组的检索块。

**RoutingLLMService**：模型路由服务。streamChat 方法接收 Prompt 和回调函数，自动选择可用模型并执行流式调用。

---

## 第四章 文档入库流水线

### 4.1 流水线整体设计

文档入库流水线是 RAG 系统的重要组成部分，负责将各种来源的文档（文件、URL、飞书、对象存储）转换为可检索的向量数据。整个流水线基于节点编排模式实现，包含六个标准节点：Fetcher（抓取）、Parser（解析）、Enhancer（增强）、Chunker（分块）、Enricher（丰富）、Indexer（索引）。

流水线设计遵循以下原则：每个节点的配置存储在数据库中，支持运行时动态调整；节点支持条件执行，可以根据文档类型、来源等条件跳过或替换某些节点；节点间通过输出链式传递，下游节点可以访问上游节点的输出；每个任务和节点都有独立的执行日志，出了问题能精确定位到哪一步。

### 4.2 节点详解

#### 4.2.1 FetcherNode（抓取节点）

FetcherNode 负责从各种来源获取原始文档内容。当前支持以下抓取器：

LocalFileFetcher：读取本地文件系统中的文档。 HttpUrlFetcher：通过 HTTP/HTTPS 协议抓取网页内容。 FeishuFetcher：抓取飞书文档内容。 S3Fetcher：从 S3 兼容对象存储读取文档。

每种抓取器实现统一的 DocumentFetcher 接口，返回 FetchResult 包含原始内容、媒体类型、元数据等信息。抓取器的选择由文档来源类型自动决定，也可以通过配置指定。

#### 4.2.2 ParserNode（解析节点）

ParserNode 负责将各种格式的文档解析为纯文本。系统使用 Apache Tika 作为解析引擎，支持 PDF、Word、Excel、PPT、HTML、Markdown、TXT 等常见格式。

解析过程包括：格式检测（根据文件扩展名或魔数判断类型）、内容提取（使用 Tika 提取文本和元数据）、结构保留（尽可能保留文档的章节结构，便于后续分块）。

TikaDocumentParser 是主要的解析实现类，MarkdownDocumentParser 处理 Markdown 格式的特化处理。解析结果通过 ParseResult 对象返回，包含文本内容、标题、作者、创建时间等元信息。

#### 4.2.3 EnhancerNode（增强节点）

EnhancerNode 负责对解析后的文本进行清洗和格式化处理。处理内容包括：去除 HTML 标签、特殊字符、空行多余空格；统一标点符号格式；修复常见的编码问题；去除文档中的噪声内容（如页眉页脚、水印）。

增强处理使用 LLM 进行语义增强，根据预设的 Prompt 模板对文本进行润色和补充。EnhancerPromptManager 管理各类增强 Prompt，支持针对不同文档类型的个性化配置。

#### 4.2.4 ChunkerNode（分块节点）

ChunkerNode 是流水线中最关键的节点之一，负责将长文本分割成适合检索的片段。分块策略直接影响检索效果，Ragent 支持多种分块策略：

固定大小分块：按固定字符数或 Token 数切分，简单直接但可能破坏语义完整性。 语义分块：基于文本的语义结构（如段落、章节）进行切分，保持语义完整性。 递归分块：先按大块切分，不满足条件时递归细分。

分块配置通过 ChunkerSettings 指定，包括块大小、重叠大小、分块策略等参数。系统使用 VectorChunk 对象表示每个分块，包含块ID、内容、向量、元数据等属性。

#### 4.2.5 EnricherNode（丰富节点）

EnricherNode 负责对分块后的文本片段进行进一步增强。处理内容包括：关键词提取；实体识别；摘要生成；元数据注入（添加标题、来源、创建时间等上下文信息）。

EnricherPromptManager 管理丰富处理的 Prompt 模板，支持为每个知识库配置不同的丰富策略。EnricherSettings 包含模型选择、输出格式等配置。

#### 4.2.6 IndexerNode（索引节点）

IndexerNode 是流水线的最后一个节点，负责将处理好的分块数据写入向量数据库。在本设计中，向量存储使用 PostgreSQL + pgvector。

索引过程包括：调用 Embedding 服务将文本转换为向量；构建包含内容、元数据、向量的记录；批量写入向量数据库。

PgVectorStoreService 是 PostgreSQL 向量存储的实现类，使用 JDBC 进行数据库操作。向量数据写入时使用 `INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)` 语句。

### 4.3 流水线引擎

IngestionEngine 是流水线执行的核心引擎，负责解析流水线配置、调度节点执行、处理节点间数据传递、处理异常和重试。

引擎执行流程如下：根据 PipelineDefinition 加载节点配置；按拓扑顺序依次执行各节点；每个节点执行前检查前置条件是否满足；节点执行完成后将输出传递给下一个节点；记录每个节点的执行状态、耗时、输出；发生异常时根据配置决定重试或中止。

ConditionEvaluator 负责评估节点执行条件，支持基于文档属性、系统状态、时间条件等多种条件判断。NodeOutputExtractor 从节点输出中提取需要传递给下游的数据。

---

## 第五章 多路检索架构

### 5.1 检索通道层设计

Ragent 的检索引擎采用插件化设计，通过 SearchChannel 接口定义统一的检索能力。所有检索通道实现该接口，系统自动发现并组合使用。

SearchChannel 接口定义如下：

```java
public interface SearchChannel {
    List<SearchChannelResult> search(SearchContext context);
}
```

当前系统实现了两种检索通道：

**IntentDirectedSearchChannel（意图定向检索）**：根据意图识别结果，定向检索对应的知识库 Collection。每个意图节点关联一个 Collection，检索时只在该 Collection 内进行向量搜索。这种方式的优势是检索范围精确、噪声少，适合明确意图的查询。

**VectorGlobalSearchChannel（全局向量检索）**：不依赖意图识别，在所有知识库 Collection 中进行全局向量搜索。这种方式作为兜底方案，当意图识别置信度不足时启用，特点是召回率高。

SearchChannel 的设计遵循策略模式，新增检索通道只需实现接口并注册为 Spring Bean，无需修改现有代码。

### 5.2 后处理层设计

检索结果的后处理通过 SearchResultPostProcessor 接口实现，支持可插拔的处理器组合。

```java
public interface SearchResultPostProcessor {
    List<RetrievedChunk> process(List<RetrievedChunk> chunks);
}
```

当前实现的后处理器包括：

**DeduplicationPostProcessor（去重处理器）**：基于内容相似度或文档ID去除重复的检索结果。使用编辑距离或余弦相似度判断重复，阈值可配置。

**RerankPostProcessor（重排序处理器）**：使用重排序模型对检索结果进行二次排序。RerankClient 接口定义了重排序能力，当前支持 BaiLianRerankClient 实现。

后处理器的执行顺序通过配置指定，支持责任链模式串联多个处理器。

### 5.3 并行检索与故障隔离

多通道检索通过线程池实现并行执行。AbstractParallelRetriever 提供并行检索的基础能力，每个检索通道独立执行、互不阻塞。

检索异常处理采用故障隔离策略，单个通道的异常不影响其他通道的结果返回。检索结果按通道分别返回，最终由 RetrievalEngine 合并。

### 5.4 向量检索实现

当使用 PostgreSQL + pgvector 时，向量检索通过 PgRetrieverService 实现。

检索过程包括以下步骤：

调用 Embedding 服务将查询问题转换为向量。使用 pgvector 的 `<=>` 操作符计算余弦相似度。通过 HNSW 索引加速近似最近邻搜索。设置 ef_search 参数提升召回率。限制返回 TopK 结果。

```sql
SELECT id, content, 1 - (embedding <=> ?::vector) AS score 
FROM t_knowledge_vector 
WHERE metadata->>'collection_name' = ? 
ORDER BY embedding <=> ?::vector 
LIMIT ?
```

---

## 第六章 模型路由与容错

### 6.1 模型路由架构

Ragent 的模型路由机制解决生产环境中多模型供应商切换的问题。架构设计遵循以下原则：

多供应商支持：统一的 ChatClient 接口屏蔽各供应商 API 差异，当前支持百炼、SiliconFlow、Ollama 三种供应商。优先级调度：ModelSelector 按优先级排序候选模型列表，优先使用优先级高的模型。健康检查：ModelHealthStore 维护每个模型的健康状态，失败次数达到阈值触发熔断。

### 6.2 首包探测机制

首包探测是 Ragent 的关键设计，用于在流式输出场景下实现模型的无感知切换。

工作原理如下：发起流式请求后，系统启动 60 秒的首包探测计时器；首包到达前，所有事件缓存在 ProbeBufferingCallback 中；如果首包在超时时间内到达，回放缓冲事件，正常输出；如果首包超时或出现异常，丢弃缓冲事件，尝试下一个候选模型。

这种设计确保了模型切换时用户端不会收到半截的脏数据，用户无感知。

### 6.3 三态熔断器

系统为每个模型维护独立的健康状态，实现经典的三态熔断器：

**CLOSED（关闭状态）**：正常运行，接受请求。失败次数累计达到阈值（默认 5 次）时切换到 OPEN 状态。

**OPEN（打开状态）**：拒绝请求，直接切换到下一个候选模型。冷却期（默认 60 秒）后进入 HALF_OPEN 状态。

**HALF_OPEN（半开状态）**：放行探测请求探测该模型是否恢复。如果探测成功，切换到 CLOSED 状态；如果探测失败，继续保持在 OPEN 状态。

熔断器的实现代码位于 infra-ai 模块的 model 包中，配合优先级降级链实现高可用。

### 6.4 模型选择器

ModelSelector 负责维护候选模型列表和选择逻辑。选择策略如下：

从配置中加载候选模型列表，按优先级排序。遍历候选列表，尝试调用可用模型。如果模型健康状态为熔断，跳过该模型。如果调用失败，记录失败次数并触发熔断检查。

---

## 第七章 MCP 工具集成

### 7.1 MCP 协议概述

MCP（Model Context Protocol）是 AI 模型与外部工具交互的标准协议。Ragent 实现了一套简化版的 MCP 协议，支持工具注册、参数提取、执行调用。

MCP 工具的核心概念包括：Tool（工具定义）：描述工具的名称、描述、参数模式。Request（调用请求）：包含用户问题、工具ID、参数值。Response（执行结果）：包含执行状态、输出内容、错误信息。

### 7.2 工具注册机制

MCPToolRegistry 是工具注册中心，采用注册表模式实现自动发现。DefaultMCPToolRegistry 实现类通过 Spring Bean 扫描自动发现所有 MCPToolExecutor 实现。

新增 MCP 工具只需：实现 MCPToolExecutor 接口；使用 @Component 注解注册为 Spring Bean；实现 execute 方法处理业务逻辑。

系统会自动将工具注册到注册表中，无需额外配置。

### 7.3 参数提取

MCPParameterExtractor 负责从用户问题中提取工具调用所需的参数。LLMMCPParameterExtractor 使用 LLM 进行参数抽取，根据工具的参数模式生成 Prompt，从 LLM 响应中解析参数值。

参数提取支持自定义 Prompt 模板，每个意图节点可以配置独立的 paramPromptTemplate，实现个性化的参数抽取逻辑。

### 7.4 工具执行

MCPToolExecutor 是工具执行的接口，每个工具实现类实现 execute 方法。RemoteMCPToolExecutor 支持远程 HTTP 调用的工具，可以通过配置指定服务端点。

工具执行结果通过 MCPResponse 返回，包含执行状态、输出内容、错误信息。RetrievalEngine 在检索过程中并行执行所有匹配的工具调用，将结果格式化后合并到上下文中。

---

## 第八章 数据模型设计

### 8.1 数据库表结构总览

Ragent 项目设计了 20+ 张业务表，涵盖完整的业务领域。以下是核心表结构分类：

用户与会话表：t_user（系统用户）、t_conversation（会话列表）、t_conversation_summary（会话摘要）、t_message（消息记录）、t_message_feedback（消息反馈）。

知识库表：t_knowledge_base（知识库）、t_knowledge_document（文档）、t_knowledge_chunk（分块）、t_knowledge_document_chunk_log（分块日志）。

意图与查询表：t_intent_node（意图树节点）、t_query_term_mapping（关键词归一化）。

RAG 追踪表：t_rag_trace_run（追踪运行记录）、t_rag_trace_node（追踪节点记录）。

入库流水线表：t_ingestion_pipeline（流水线定义）、t_ingestion_pipeline_node（流水线节点）、t_ingestion_task（入库任务）、t_ingestion_task_node（任务节点执行记录）。

向量存储表：t_knowledge_vector（向量存储）。

### 8.2 向量存储表设计

本设计使用 PostgreSQL + pgvector 替代原始 Milvus 向量数据库。向量存储表设计如下：

```sql
CREATE TABLE t_knowledge_vector (
    id          VARCHAR(20) PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   vector(1536)
);

CREATE INDEX idx_kv_metadata ON t_knowledge_vector USING gin(metadata);
CREATE INDEX idx_kv_embedding ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops);
```

字段说明：id 是分块ID，作为主键用于唯一标识每个向量记录。content 是分块文本内容，用于检索返回和上下文组装。metadata 是 JSONB 类型的元数据，包含 collection_name、doc_id、chunk_index 等信息。embedding 是 vector(1536) 类型的向量，1536 维度适配主流 Embedding 模型。

索引设计：GIN 索引支持 JSONB 字段的高效查询，用于按 collection_name、doc_id 等条件过滤。HNSW 索引用于向量相似度检索，提供近似最近邻搜索的高性能。

### 8.3 核心表关系图

以下是核心表之间的关系：

```
t_knowledge_base (1) ─────< (N) t_knowledge_document
                                    │
                                    ▼ (N)
                              t_knowledge_chunk
                                    │
                                    ▼ (1)
                              t_knowledge_vector

t_intent_node (1) ─────< (N) t_query_term_mapping

t_conversation (1) ─────< (N) t_message
                              │
                              ▼ (N)
                        t_message_feedback
```

### 8.4 意图树节点表设计

意图识别是 RAG 系统的核心能力，t_intent_node 表存储意图树的结构：

```sql
CREATE TABLE t_intent_node (
    id                    VARCHAR(20) NOT NULL PRIMARY KEY,
    kb_id                 VARCHAR(20),
    intent_code           VARCHAR(64) NOT NULL,
    name                  VARCHAR(64) NOT NULL,
    level                 SMALLINT    NOT NULL,
    parent_code           VARCHAR(64),
    description           VARCHAR(512),
    examples              TEXT,
    collection_name       VARCHAR(128),
    top_k                 INTEGER,
    mcp_tool_id           VARCHAR(128),
    kind                  SMALLINT    NOT NULL DEFAULT 0,
    prompt_snippet        TEXT,
    prompt_template       TEXT,
    param_prompt_template TEXT,
    sort_order            INTEGER     NOT NULL DEFAULT 0,
    enabled               SMALLINT    NOT NULL DEFAULT 1,
    ...
);
```

关键字段：level 表示层级，0 为 DOMAIN（领域），1 为 CATEGORY（类目），2 为 TOPIC（话题）。kind 表示类型，0 为 KB（知识库类），1 为 SYSTEM（系统交互类）。collection_name 关联知识库的 Collection 名称。mcp_tool_id 关联 MCP 工具 ID，用于非知识库类意图。

---

## 第九章 向量存储改造方案

### 9.1 改造背景

原始项目使用 Milvus 作为向量数据库，部署架构需要额外维护一个 Milvus 集群。为了简化部署架构、降低运维复杂度，本设计将向量存储迁移到 PostgreSQL + pgvector。

pgvector 是 PostgreSQL 的开源扩展，支持向量数据类型和向量相似度搜索。对于中小规模（百万级向量）的知识库场景，pgvector 完全可以满足需求，且具有以下优势：无需额外维护向量数据库，降低运维成本；向量数据与业务数据在同一个数据库中，方便事务处理和联合查询；支持 PostgreSQL 的完整生态（备份、监控、HA 等）。

### 9.2 核心代码实现

#### 9.2.1 PgVectorStoreService

PgVectorStoreService 是向量存储的核心服务，负责向量数据的增删改查：

```java
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        jdbcTemplate.batchUpdate(
            "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
            chunks, chunks.size(), (ps, chunk) -> {
                ps.setString(1, chunk.getChunkId());
                ps.setString(2, chunk.getContent());
                ps.setString(3, buildMetadataJson(collectionName, docId, chunk));
                ps.setString(4, toVectorLiteral(chunk.getEmbedding()));
            });
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        jdbcTemplate.update(
            "DELETE FROM t_knowledge_vector WHERE metadata->>'collection_name' = ? AND metadata->>'doc_id' = ?",
            collectionName, docId);
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE id = ?", chunkId);
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        jdbcTemplate.update(
            "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector) " +
                "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding",
            chunk.getChunkId(), chunk.getContent(),
            buildMetadataJson(collectionName, docId, chunk),
            toVectorLiteral(chunk.getEmbedding()));
    }
}
```

#### 9.2.2 PgVectorStoreAdmin

PgVectorStoreAdmin 负责向量存储的管理操作，包括创建 Collection（对应 PostgreSQL 表）、创建索引等：

```java
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void createVectorSpace(VectorSpaceSpec spec) {
        // PostgreSQL 使用共享的 t_knowledge_vector 表，无需创建新表
        // 确保 HNSW 索引存在
        String indexName = "idx_kv_embedding_hnsw";
        jdbcTemplate.execute(
            String.format("CREATE INDEX IF NOT EXISTS %s ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops)", indexName));
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        // 检查表中是否有数据
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM t_knowledge_vector LIMIT 1", Integer.class);
        return count != null && count > 0;
    }
}
```

#### 9.2.3 PgRetrieverService

PgRetrieverService 实现向量检索功能：

```java
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        List<Float> embedding = embeddingService.embed(request.getQuery());
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        // 设置 ef_search 提升召回率
        jdbcTemplate.execute("SET hnsw.ef_search = 200");

        String vectorLiteral = toVectorLiteral(vector);
        return jdbcTemplate.query(
            "SELECT id, content, 1 - (embedding <=> ?::vector) AS score FROM t_knowledge_vector " +
            "WHERE metadata->>'collection_name' = ? ORDER BY embedding <=> ?::vector LIMIT ?",
            (rs, rowNum) -> RetrievedChunk.builder()
                .id(rs.getString("id"))
                .text(rs.getString("content"))
                .score(rs.getFloat("score"))
                .build(),
            vectorLiteral, request.getCollectionName(), vectorLiteral, request.getTopK());
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
```

### 9.3 配置切换

通过 Spring Boot 配置项控制向量存储类型：

```yaml
rag:
  vector:
    type: pg  # 使用 PostgreSQL; 改为 milvus 则使用 Milvus
```

配置变更后，系统自动加载对应的实现类：type=pg 时加载 PgVectorStoreService、PgRetrieverService；type=milvus 时加载 MilvusVectorStoreService、MilvusRetrieverService。

### 9.4 迁移注意事项

向量维度：确保使用的 Embedding 模型维度与 PostgreSQL 表定义一致（默认 1536）。如需调整，修改 schema_pg.sql 中的 vector(1536) 为实际维度。

索引类型选择：pgvector 支持 IVFFlat 和 HNSW 两种索引。IVFFlat 适合数据量较小的场景；HNSW 提供更高的搜索质量但占用更多内存。本设计使用 HNSW 索引。

性能优化：批量写入时使用 JDBC batch update；检索时设置 hnsw.ef_search 参数提升召回率；定期执行 ANALYZE 更新统计信息。

---

## 第十章 部署与运维

### 10.1 Docker Compose 部署

Ragent 项目提供 Docker Compose 部署方式，核心服务包括：

PostgreSQL：主数据库，存储业务数据和向量数据。 Redis：缓存和限流。 MinIO：对象存储，存储上传的文档文件。 Ragent Backend：Spring Boot 应用。 Nginx：反向代理（可选）。

### 10.2 环境配置

核心配置文件 application.yml 包含以下关键配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/ragent
    username: ragent
    password: ragent_password
  data:
    redis:
      host: redis
      port: 6379

rag:
  vector:
    type: pg
  model:
    chat:
      providers:
        - name: siliconflow
          api-key: ${SILICONFLOW_API_KEY}
          base-url: https://api.siliconflow.cn/v1
          priority: 1
        - name: ollama
          base-url: http://localhost:11434
          priority: 2
    embedding:
      default-model: BAAI/bge-large-zh-v1.5
```

### 10.3 运维监控

**链路追踪**：基于 AOP 的全链路 Trace，记录每个环节的耗时、输入输出、异常信息。追踪数据存储在 t_rag_trace_run 和 t_rag_trace_node 表中，可通过管理后台查看。

**健康检查**：Spring Boot Actuator 提供 /actuator/health 端点，支持 K8s 健康探测。

**日志管理**：使用 SLF4J + Logback 日志框架，支持结构化日志输出。

---

## 第十一章 企业实践要点

### 11.1 性能优化

**向量检索优化**：选择合适的索引类型（IVFFlat vs HNSW）；调整 ef_search 参数平衡召回率和延迟；使用向量量化技术减少存储和计算开销。

**模型调用优化**：启用首包探测减少等待时间；实现请求缓存避免重复调用；使用流式输出减少首包延迟。

**数据库优化**：为常用查询字段创建索引；使用连接池管理数据库连接；定期分析表统计信息优化执行计划。

### 11.2 高并发场景

**限流策略**：实现用户级和全局级限流；使用 Redis 信号量实现分布式限流；队列式限流避免请求瞬时冲击。

**异步处理**：非核心流程使用异步处理；SSE 流式输出减少阻塞；消息队列解耦耗时操作。

**资源隔离**：不同业务使用独立线程池；模型调用使用独立连接池；关键服务预留资源。

### 11.3 容错设计

**模型容错**：多模型优先级路由；熔断器隔离故障模型；降级策略返回兜底答案。

**服务容错**：重试机制处理临时故障；超时控制避免长等待；降级策略保证核心功能可用。

**数据容错**：向量数据定期备份；关键操作幂等设计；异常状态可回滚。

### 11.4 安全考虑

**认证鉴权**：基于 Sa-Token 的用户认证；API 级别权限控制；敏感操作日志审计。

**数据安全**：向量数据加密存储；API 密钥安全存储；敏感信息脱敏。

**模型安全**：输入内容安全审核；输出内容合规检查；防止 Prompt 注入。

---

## 附录

### 附录 A：数据库表清单

| 表名 | 说明 |
|------|------|
| t_user | 系统用户表 |
| t_conversation | 会话列表 |
| t_conversation_summary | 会话摘要 |
| t_message | 消息记录 |
| t_message_feedback | 消息反馈 |
| t_knowledge_base | 知识库 |
| t_knowledge_document | 文档 |
| t_knowledge_chunk | 分块 |
| t_knowledge_vector | 向量存储 |
| t_intent_node | 意图树节点 |
| t_query_term_mapping | 关键词归一化 |
| t_rag_trace_run | 追踪运行记录 |
| t_rag_trace_node | 追踪节点 |
| t_ingestion_pipeline | 流水线定义 |
| t_ingestion_pipeline_node | 流水线节点 |
| t_ingestion_task | 入库任务 |
| t_ingestion_task_node | 任务节点执行 |

### 附录 B：核心接口清单

| 接口 | 位置 | 说明 |
|------|------|------|
| VectorStoreService | bootstrap | 向量存储服务 |
| RetrieverService | bootstrap | 向量检索服务 |
| EmbeddingService | infra-ai | 向量化服务 |
| LLMService | infra-ai | 对话服务 |
| SearchChannel | bootstrap | 检索通道 |
| SearchResultPostProcessor | bootstrap | 检索结果后处理 |
| MCPToolExecutor | bootstrap | MCP 工具执行 |
| IngestionNode | bootstrap | 入库流水线节点 |

### 附录 C：配置项清单

| 配置项 | 说明 | 示例值 |
|--------|------|--------|
| rag.vector.type | 向量存储类型 | pg / milvus |
| rag.model.chat.default-provider | 默认对话模型供应商 | siliconflow |
| rag.model.embedding.default-model | 默认 Embedding 模型 | BAAI/bge-large-zh-v1.5 |
| rag.retrieval.top-k | 默认 TopK | 5 |
| rag.memory.window-size | 记忆窗口大小 | 10 |

---

## 文档版本

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-03-30 | 初始版本，基于 Ragent 开源项目分析整理 |

---

**声明**：本文档基于 Ragent 开源项目源码分析整理，仅供学习交流使用。原始项目采用 Apache License 2.0 开源协议，详细源码请访问 https://github.com/nageoffer/ragent。
