# 设计文档：RAG缺失文档补全

**日期**: 2026-03-31
**状态**: 已确认

---

## 背景

现有 `docs/RAG优化流程树与实现指南.md` 只覆盖了查询/检索流程（用户问 → 检索 → 回答），缺少：
1. 文档入库全流程
2. 知识库/意图树/分块等管理业务
3. 工程级优化模块的详细实现说明

---

## 目标

新增两个补充文档，写作风格与现有指南完全一致（ASCII流程树 + 表格 + 伪代码 + Java代码）：

- `docs/文档入库及缺少的业务.md`
- `docs/工程级优化.md`

---

## 文件一：docs/文档入库及缺少的业务.md

### 章节结构

| 章节 | 内容 | 写作深度 |
|------|------|---------|
| 一、整体业务流程树 | 入库流程树 + 管理/刷新流程树 | - |
| 二、文档入库流程 | Parser→Chunker→Embedding→Indexer | 详细（伪代码+Java） |
| 三、知识库管理 | 创建/更新/删除知识库 | 简写（表格+说明） |
| 四、意图树管理 | CRUD简写 + 缓存刷新详写 | 混合 |
| 五、文档定时刷新 | Cron调度+分布式锁+失败恢复 | 详细（伪代码+Java） |
| 六、分块管理 | CRUD简写 + rebuildByDocId详写 | 混合 |

### 核心类覆盖范围

- `KnowledgeDocumentServiceImpl` - 文档入库主服务
- `IngestionTaskServiceImpl` - 摄入任务服务
- `ParserNode` / `TikaDocumentParser` - 文档解析
- `ChunkerNode` / `FixedSizeTextChunker` / `StructureAwareTextChunker` - 文本分块
- `ChunkEmbeddingService` - 向量化
- `IndexerNode` / `MilvusVectorStoreService` - 向量存储
- `KnowledgeBaseService` - 知识库管理
- `IntentTreeService` / `DefaultIntentClassifier` - 意图树管理
- `KnowledgeDocumentScheduleJob` - 定时刷新
- `KnowledgeChunkService` - 分块管理

---

## 文件二：docs/工程级优化.md

### 章节结构

| 章节 | 内容 | 写作深度 |
|------|------|---------|
| 一、限流与并发 | Redis信号量排队、分布式限流、TTL跨线程 | 详细（伪代码+Java） |
| 二、全链路可观测性 | @RagTraceNode AOP、父子节点、持久化 | 详细（伪代码+Java） |
| 三、MCP工具生态 | 自动注册、LLM参数提取、工具扩展 | 详细（伪代码+Java） |

### 核心类覆盖范围

- `ChatRateLimit` / `ChatRateLimitAspect` / `ChatQueueLimiter` - 限流
- `RagTraceNode` / `RagTraceAspect` / `RagTraceContext` - 链路追踪
- `MCPToolRegistry` / `MCPToolExecutor` / `LLMMCPParameterExtractor` - MCP生态

---

## 写作规范

与现有指南完全一致：
1. 每个模块开头有业务流程 ASCII 树
2. 核心类用表格列出
3. 优化点用表格（优化项/说明/核心代码位置）
4. 代码示例包含：业务伪代码注释 → "这段代码做什么" → Java实现
5. 结尾有"使用场景"和"优化优点"总结
