# RAG 缺失文档补全实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 RAG 指南中缺失的文档入库、知识库/意图树/分块管理、定时刷新、工程级优化三大模块，写作风格与现有指南完全一致（ASCII 流程树 + 表格 + 伪代码 + Java 代码）

**Architecture:** 生成两个独立 Markdown 文件：`docs/文档入库及缺少的业务.md` 覆盖入库流程与管理业务，`docs/工程级优化.md` 覆盖限流、链路追踪、MCP 三大工程优化模块。每个章节都包含业务伪代码、"这段代码做什么"解释和真实 Java 代码。

**Tech Stack:** Markdown 文档、真实代码引用自项目 Java 源文件

---

## 文件清单

| 目标文件 | 内容 |
|---------|------|
| `docs/文档入库及缺少的业务.md` | 入库流程、知识库管理、意图树管理、定时刷新、分块管理 |
| `docs/工程级优化.md` | 限流与并发、全链路可观测性、MCP工具生态 |

**关键源码参考路径（执行时直接引用）：**
- 入库引擎：`bootstrap/.../ingestion/engine/IngestionEngine.java`
- 定时刷新：`bootstrap/.../knowledge/schedule/KnowledgeDocumentScheduleJob.java`
- 限流切面：`bootstrap/.../rag/aop/ChatRateLimitAspect.java`
- 限流队列：`bootstrap/.../rag/aop/ChatQueueLimiter.java`
- 链路切面：`bootstrap/.../rag/aop/RagTraceAspect.java`
- MCP参数提取：`bootstrap/.../rag/core/mcp/LLMMCPParameterExtractor.java`

---

## Task 1：写「文档入库及缺少的业务.md」— 整体流程树 + 文档入库

**Files:**
- Create: `docs/文档入库及缺少的业务.md`

- [ ] **Step 1：写文件头 + 一、整体业务流程树**

写入以下内容到 `docs/文档入库及缺少的业务.md`（从头开始）：

```markdown
# Ragent 文档入库与业务管理流程树与实现指南

> 本文档作为 RAG 优化流程树与实现指南的配套篇，覆盖文档入库、知识库管理、意图树管理、文档定时刷新和分块管理五大业务模块，帮助开发者理解"数据如何进入 RAG 系统"的完整链路。

---

## 一、整体业务流程树

**主线一：文档入库（用户主动上传）**

\```
用户上传文件
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. 文件上传与元数据存储 (KnowledgeDocumentServiceImpl.upload)               │
│    ├── 文件存储到 OSS/NFS                                                    │
│    ├── 写入文档元数据记录 (状态=PENDING)                                     │
│    └── 发送 MQ 消息触发分块任务                                              │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. 摄入引擎执行 (IngestionEngine.execute)                                   │
│    ├── FetcherNode - 获取文件内容                                            │
│    ├── ParserNode - 解析文档为结构化文本                                     │
│    ├── ChunkerNode - 文本分块                                                │
│    ├── EnricherNode - LLM 增强（可选）                                       │
│    └── IndexerNode - 向量化 + 存储到 Milvus                                 │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. 落库与状态更新 (KnowledgeDocumentServiceImpl.chunkDocument)              │
│    ├── 批量写入分块数据 (t_knowledge_chunk)                                  │
│    ├── 记录分块日志 (t_knowledge_document_chunk_log)                         │
│    └── 更新文档状态 (RUNNING → SUCCESS / FAILED)                             │
└─────────────────────────────────────────────────────────────────────────────┘
\```

**主线二：文档定时刷新（系统自动拉取）**

\```
定时扫描任务（每10秒）
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ KnowledgeDocumentScheduleJob.scan()                                         │
│    ├── 查询 enabled=1 且 nextRunTime <= now 的调度记录                       │
│    ├── 数据库乐观锁抢占（多实例部署防重复执行）                              │
│    └── 提交 executeSchedule() 到异步线程池                                   │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ KnowledgeDocumentScheduleJob.executeSchedule()                              │
│    ├── 拉取远程文件（HTTP / 飞书 / S3）                                      │
│    ├── 内容未变化（ETag/Hash 对比）→ SKIPPED                                 │
│    ├── 上传新文件到 OSS                                                      │
│    ├── 调用 chunkDocument() 重新入库                                         │
│    └── 清理旧文件，更新执行记录                                              │
└─────────────────────────────────────────────────────────────────────────────┘
\```

---
```

- [ ] **Step 2：写二、文档入库流程（核心类 + 优化点表格）**

追加到文件：

```markdown
## 二、文档入库流程 (IngestionEngine)

### 核心类：

| 类名 | 职责 |
|------|------|
| `KnowledgeDocumentServiceImpl` | 上传入口，负责文件存储、元数据写入、触发分块 |
| `IngestionEngine` | 流水线执行引擎，按节点链式执行 |
| `FetcherNode` | 从 OSS/本地/HTTP 获取文件内容 |
| `ParserNode` / `TikaDocumentParser` | 解析 PDF/Word/Excel/Markdown 为纯文本 |
| `ChunkerNode` | 文本分块，支持固定大小和结构感知两种策略 |
| `EnricherNode` | 调用 LLM 对分块进行语义增强（可选节点） |
| `IndexerNode` | 向量化（调用 Embedding 模型）并写入 Milvus |
| `KnowledgeChunkService` | 分块数据落库（MySQL）及向量索引管理 |

### 优化点：

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| MQ 解耦 | 上传返回后异步执行分块，不阻塞用户 | `KnowledgeDocumentChunkConsumer` |
| 流水线引擎 | 节点链式可配置，新增节点无需改主流程 | `IngestionEngine.executeChain()` |
| 节点条件跳过 | 每个节点支持条件表达式，满足条件才执行 | `ConditionEvaluator.evaluate()` |
| 分块策略可选 | FixedSize（按字数）/ StructureAware（按标题/段落） | `ChunkingStrategy` 实现类 |
| 内容哈希去重 | 分块写入前比对 contentHash，相同内容不重复入库 | `KnowledgeChunkDO.contentHash` |
| 崩溃恢复 | 每分钟检查卡在 RUNNING 超时的文档，重置为 FAILED | `recoverStuckRunningDocuments()` |
```

- [ ] **Step 3：写入库流程业务伪代码 + 这段代码做什么 + Java代码**

追加到文件：

```markdown
**代码示例：**

> **业务例子（伪代码）**
>
> \```
> 输入：
>   kbId = "kb_001"
>   文件：《员工手册2024版.pdf》，大小 2MB
>
> 处理过程：
>
>   第1步：文件上传（KnowledgeDocumentServiceImpl.upload）
>   - 文件存到 OSS：/kb_001/docs/员工手册2024版.pdf
>   - 写入 t_knowledge_document：
>     id="doc_001", kbId="kb_001", status=PENDING, fileUrl="..."
>   - 发送 MQ 消息：KnowledgeDocumentChunkEvent{docId="doc_001"}
>
>   第2步：Consumer 收到消息，开始分块（IngestionEngine）
>   流水线节点链：FetcherNode → ParserNode → ChunkerNode → IndexerNode
>
>   FetcherNode：
>   - 从 OSS 下载 PDF 文件 → InputStream
>
>   ParserNode：
>   - 检测 MIME 类型：application/pdf
>   - 选择解析器：TikaDocumentParser
>   - 解析结果：纯文本 12000 字
>
>   ChunkerNode：
>   - 分块策略：FixedSize，chunkSize=500字，overlap=50字
>   - 分块结果：25个 VectorChunk
>
>   IndexerNode：
>   - 调用 Embedding 模型，批量生成 25个向量（1536维）
>   - 写入 Milvus Collection："kb_001"
>
>   第3步：落库
>   - 批量写入 t_knowledge_chunk：25条记录
>   - 更新 t_knowledge_document：status=SUCCESS, chunkCount=25
> \```
>
> **这段代码做什么？**
>
> 这是"流水线执行引擎"的核心逻辑，将文档处理抽象为可配置的节点链：
> 1. **节点发现**：启动时扫描所有 `IngestionNode` Bean，按 nodeType 注册
> 2. **起始节点定位**：找出没有被其他节点引用的节点（即链头）
> 3. **链式执行**：从起始节点出发，执行完后按 `nextNodeId` 移动到下一节点
> 4. **条件判断**：每个节点可配置条件表达式，条件不满足则跳过该节点继续往后走
> 5. **失败中断**：任意节点失败时，标记 `context.status = FAILED` 并停止链式执行

```java
// IngestionEngine.java:60-87 - 流水线执行核心
public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
    context.setStatus(IngestionStatus.RUNNING);

    // 构建节点配置映射
    Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());

    // 验证流水线（检测环、检测悬空引用）
    validatePipeline(nodeConfigMap);

    // 找到起始节点（没有被任何 nextNodeId 引用的节点）
    String startNodeId = findStartNode(nodeConfigMap);

    // 从起始节点开始链式执行
    executeChain(startNodeId, nodeConfigMap, context);

    if (context.getStatus() == IngestionStatus.RUNNING) {
        context.setStatus(IngestionStatus.COMPLETED);
    }
    return context;
}

// 链式执行 - IngestionEngine.java:158-200
private void executeChain(String nodeId, Map<String, NodeConfig> nodeConfigMap, IngestionContext context) {
    String currentNodeId = nodeId;
    while (currentNodeId != null) {
        NodeConfig config = nodeConfigMap.get(currentNodeId);

        // 执行当前节点（含条件判断）
        NodeResult result = executeNode(context, config);

        if (!result.isSuccess()) {
            context.setStatus(IngestionStatus.FAILED);
            break;  // 节点失败，终止链式执行
        }

        if (!result.isShouldContinue()) {
            break;  // 节点主动要求停止（如内容为空）
        }

        // 移动到下一个节点
        currentNodeId = config.getNextNodeId();
    }
}

// 执行单个节点 - IngestionEngine.java:205-261
private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
    String nodeType = nodeConfig.getNodeType();
    IngestionNode node = nodeMap.get(nodeType);

    // 条件判断：满足条件才执行，否则 skip
    if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
        if (!conditionEvaluator.evaluate(context, nodeConfig.getCondition())) {
            return NodeResult.skip("条件未满足");  // 跳过但不中断链
        }
    }

    long start = System.currentTimeMillis();
    try {
        NodeResult result = node.execute(context, nodeConfig);
        // 记录节点日志（耗时、状态、输出摘要）
        context.getLogs().add(NodeLog.builder()
                .nodeId(nodeConfig.getNodeId())
                .nodeType(nodeType)
                .durationMs(System.currentTimeMillis() - start)
                .success(result.isSuccess())
                .build());
        return result;
    } catch (Exception e) {
        return NodeResult.fail(e);  // 异常 = 失败，不抛出
    }
}
```

**使用场景：** 将 PDF、Word、Markdown 等格式文件解析分块后存入 Milvus，供 RAG 检索使用。

**优化优点：**
- 流水线设计让每个节点职责单一，新增格式只需新增 Parser，无需改主流程
- 条件跳过让同一流水线支持多种处理模式（有/无 LLM 增强）

---
```

- [ ] **Step 4：写三、知识库管理（简写）**

追加：

```markdown
## 三、知识库管理 (KnowledgeBaseService)

知识库是 RAG 系统的顶层容器，每个知识库对应 Milvus 中的一个 Collection。创建时需指定嵌入模型，后续所有入库文档都用该模型生成向量。

**API 接口：**

| 操作 | 方法 | 说明 |
|------|------|------|
| 创建 | `create(KnowledgeBaseCreateRequest)` | 创建知识库，同步创建 Milvus Collection |
| 更新 | `update(KnowledgeBaseUpdateRequest)` | 更新名称、描述等元数据 |
| 删除 | `delete(kbId)` | 删除知识库及其所有文档、分块、向量 |
| 查询 | `queryById(kbId)` / `pageQuery(...)` | 详情查询与分页列表 |

**数据模型：**

```java
// KnowledgeBaseDO - 知识库主表
private String id;              // 知识库ID
private String name;            // 名称，如"企业内部规范"
private String embeddingModel;  // 嵌入模型ID，决定向量维度
private String collectionName;  // Milvus Collection 名，全局唯一
```

> **注意：** `embeddingModel` 一旦设定不可更改，因为 Milvus Collection 的向量维度在创建时固定。若需更换模型，必须重建知识库并重新入库所有文档。

---
```

- [ ] **Step 5：写四、意图树管理（CRUD简写 + 缓存刷新详写）**

追加：

```markdown
## 四、意图树管理 (IntentTreeService)

意图树定义了"用户问题 → 对应知识库"的映射关系。树形结构分三级：领域(DOMAIN) → 类目(CATEGORY) → 话题(TOPIC)，叶子节点关联具体知识库或 MCP 工具。

**CRUD 接口：**

| 操作 | 方法 | 说明 |
|------|------|------|
| 创建节点 | `createNode(IntentNodeCreateRequest)` | 新增意图节点 |
| 更新节点 | `updateNode(id, request)` | 修改名称、描述、示例等 |
| 删除节点 | `deleteNode(id)` | 逻辑删除，子节点自动失效 |
| 查询全树 | `getFullTree()` | 返回完整树形结构（含子节点） |
| 批量操作 | `batchEnableNodes()` / `batchDisableNodes()` | 批量启用/禁用 |
| 初始化 | `initFromFactory()` | 从 IntentTreeFactory 初始化默认树 |

**意图树 Redis 缓存刷新策略：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| Redis 缓存 | 意图树加载到 Redis，避免每次请求查 MySQL | `DefaultIntentClassifier.loadIntentTreeData()` |
| 懒加载 | 缓存不存在时自动从数据库加载并缓存 | `loadIntentTreeFromDB()` |
| 主动失效 | 修改节点后主动删除 Redis 缓存 | `IntentTreeServiceImpl` 中的 `evictCache()` |
| TTL 保底 | 缓存有 TTL 自动过期，防止内存泄漏 | 配置项 `rag.intent.cache-ttl-minutes` |

**代码示例：**

> **业务例子（伪代码）**
>
> \```
> 场景：运维添加了新的"IT支持 > 设备 > 打印机"节点
>
> 第1步：调用 createNode()
> - 写入 t_intent_node 记录
> - 调用 evictCache()：删除 Redis Key "rag:intent:tree"
>
> 第2步：下一次意图分类请求到来
> - DefaultIntentClassifier.loadIntentTreeData()
> - 查 Redis → 缓存已失效，miss
> - 查 MySQL → 加载全量意图树（含新节点）
> - 写入 Redis 缓存，TTL=30分钟
>
> 输出：新节点"打印机"生效，下次分类时 LLM 能识别到
> \```
>
> **这段代码做什么？**
>
> 意图树缓存采用"写时失效 + 读时重建"策略：
> 1. **写时失效**：每次增删改意图节点，主动清除 Redis 中的意图树缓存
> 2. **读时重建**：意图分类器检测到缓存 Miss 时，从 MySQL 重新加载完整意图树并缓存
> 3. **TTL 保底**：即使主动失效遗漏（如直连数据库修改），TTL 到期后也会自动更新

```java
// DefaultIntentClassifier.java - 意图树懒加载
private IntentTreeData loadIntentTreeData() {
    // 1. 尝试从 Redis 读取缓存
    IntentTreeData cached = intentTreeCache.get();
    if (cached != null) {
        return cached;
    }

    // 2. 缓存 Miss，从数据库加载
    List<IntentNode> allNodes = loadIntentTreeFromDB();

    // 3. 构建树形结构（id2Node 映射 + leafNodes 列表）
    IntentTreeData data = buildTreeData(allNodes);

    // 4. 写入 Redis 缓存（TTL=30分钟）
    intentTreeCache.set(data);
    return data;
}
```

> 完整意图树结构参见：[RAG优化流程树与实现指南.md](./RAG优化流程树与实现指南.md) 第3节「意图识别」

---
```

- [ ] **Step 6：写五、文档定时刷新（详细）**

追加：

```markdown
## 五、文档定时刷新 (KnowledgeDocumentScheduleJob)

**核心类：**
- `KnowledgeDocumentScheduleJob` - 定时任务主体
- `RemoteFileFetcher` - 多源文件拉取（HTTP URL / 飞书 / S3）
- `KnowledgeDocumentScheduleDO` - 调度配置记录
- `KnowledgeDocumentScheduleExecDO` - 每次执行的记录

**优化点：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 数据库乐观锁防重 | 多实例部署时用 UPDATE + 条件 CAS 抢占，只有一个实例执行 | `tryAcquireLock()` |
| 内容变化检测 | 对比 ETag / Last-Modified / ContentHash，内容未变直接 SKIP | `RemoteFileFetcher.fetchIfChanged()` |
| 锁续期 | 长耗时分块过程中定期续期分布式锁，防止锁超时被抢 | `renewLock()` |
| 崩溃恢复 | 每分钟扫描 RUNNING 超时的文档重置为 FAILED | `recoverStuckRunningDocuments()` |
| 文件原子替换 | 新文件上传成功且分块成功后才替换旧文件URL，失败则回滚 | `switchedToNewFile` 标志位 |
| 执行历史记录 | 每次调度在 t_knowledge_document_schedule_exec 写入执行记录 | `execMapper.insert()` |

**代码示例：**

> **业务例子（伪代码）**
>
> \```
> 场景：《员工手册》文档配置了定时刷新，Cron="0 2 * * *"（每天凌晨2点）
> 部署了2个服务实例：节点A（192.168.1.10），节点B（192.168.1.11）
>
> 凌晨2:00，两个实例同时触发 scan()：
>
>   节点A：
>   - 查询到 schedule 记录：id="sch_001", docId="doc_001"
>   - 执行 tryAcquireLock("sch_001")
>   - UPDATE ... SET lockOwner="kb-schedule-node-a", lockUntil=2:05
>   - 受影响行数=1 → 抢锁成功
>   - 提交 executeSchedule("sch_001") 到线程池
>
>   节点B：
>   - 查询到同一条 schedule 记录
>   - 执行 tryAcquireLock("sch_001")
>   - UPDATE ... SET lockOwner=..., lockUntil=2:05
>     WHERE lockUntil IS NULL OR lockUntil < now
>   - 受影响行数=0（lockUntil=2:05 > now=2:00）→ 抢锁失败，跳过
>
>   节点A 执行 executeSchedule：
>   - 拉取远程文件：GET https://corp.com/handbook.pdf
>   - 对比 ETag：响应 ETag="v20240315" vs 上次存储="v20240301"
>   - ETag 不同 → 内容变化，继续执行
>   - tryMarkDocumentRunning("doc_001") → 文档标记为 RUNNING
>   - 上传新文件到 OSS
>   - 调用 chunkDocument() → 解析 + 分块 + 向量化
>   - 分块成功 → switchedToNewFile=true
>   - 更新执行记录：status=SUCCESS
>   - 计算下次执行时间：nextRunTime=明天凌晨2:00
>   - releaseLock() → lockOwner=null
>
> 输出：
>   t_knowledge_document_schedule_exec：
>     status=SUCCESS, durationMs=45000, contentHash="abc123"
> \```
>
> **这段代码做什么？**
>
> 这是"文档定时刷新"的完整逻辑，确保知识库内容保持最新：
> 1. **分布式锁抢占**：多实例环境下通过数据库 CAS（Compare-And-Swap）操作确保只有一个实例执行，等同于数据库行级锁
> 2. **内容变化检测**：通过 ETag、Last-Modified、ContentHash 三重校验，内容没变就直接跳过，避免无意义的重新入库
> 3. **锁续期机制**：分块过程可能很长（大文件数十秒），中途定期调用 `renewLock()` 延长锁有效期，防止被其他实例抢走
> 4. **文件原子替换**：只有在分块完全成功后才将文档的 `fileUrl` 切换到新文件，并删除旧文件；失败时回滚，保证不出现"新文件已覆盖但分块失败"的脏状态

```java
// KnowledgeDocumentScheduleJob.java:99-134 - 扫描 + 分布式锁抢占
@Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
public void scan() {
    Date now = new Date();
    // 查询到期且未被锁定的调度记录
    List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
            new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                    .eq(KnowledgeDocumentScheduleDO::getEnabled, 1)
                    .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getNextRunTime)
                            .or().le(KnowledgeDocumentScheduleDO::getNextRunTime, now))
                    .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                            .or().lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
    );

    Date lockUntil = new Date(System.currentTimeMillis() + scheduleProperties.getLockSeconds() * 1000);
    for (KnowledgeDocumentScheduleDO schedule : schedules) {
        // CAS 抢占分布式锁（多实例只有一个成功）
        if (!tryAcquireLock(schedule.getId(), now, lockUntil)) {
            continue;
        }
        // 提交异步执行
        knowledgeChunkExecutor.execute(() -> executeSchedule(schedule.getId()));
    }
}

// KnowledgeDocumentScheduleJob.java:196-281 - 执行核心（含文件原子替换）
// 使用 RemoteFileFetcher 检测内容是否变化
try (RemoteFileFetcher.RemoteFetchResult fetchResult = remoteFileFetcher.fetchIfChanged(
        document.getSourceLocation(),
        schedule.getLastEtag(),       // 上次的 ETag
        schedule.getLastModified(),   // 上次的 Last-Modified
        schedule.getLastContentHash() // 上次的内容哈希
)) {
    if (!fetchResult.changed()) {
        markScheduleSkipped(...);  // 内容未变，跳过
        return;
    }

    renewLock(scheduleId);  // 锁续期（后续操作耗时较长）
    tryMarkDocumentRunning(document.getId());  // 标记文档为 RUNNING

    // 上传新文件
    stored = fileStorageService.upload(...);

    // 重新分块（入库全流程）
    documentService.chunkDocument(runtimeDoc);

    // 分块成功后才切换文件URL
    applyRefreshedFileMetadata(document.getId(), stored);
    switchedToNewFile = true;  // 原子切换完成

} finally {
    if (switchedToNewFile) {
        deleteOldFileQuietly(oldFileUrl, ...);  // 成功才删旧文件
    }
    releaseLock(scheduleId);
}

// KnowledgeDocumentScheduleJob.java:414-424 - 数据库 CAS 分布式锁
private boolean tryAcquireLock(String scheduleId, Date now, Date lockUntil) {
    return scheduleMapper.update(
            Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                    .set(KnowledgeDocumentScheduleDO::getLockOwner, instanceId)
                    .set(KnowledgeDocumentScheduleDO::getLockUntil, lockUntil)
                    .eq(KnowledgeDocumentScheduleDO::getId, scheduleId)
                    // 核心条件：lockUntil 为空 或 已过期（CAS）
                    .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                            .or().lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
    ) > 0;  // 受影响行数 > 0 才算抢锁成功
}
```

**使用场景：** 文档来源是外部 HTTP URL 或飞书文档时，配置定时刷新让知识库内容自动保持最新，无需人工重新上传。

**优化优点：**
- 数据库 CAS 替代 Redis 分布式锁，依赖组件少，不引入额外中间件
- 内容哈希三重校验杜绝无意义的重复入库
- 文件原子替换确保任何时刻知识库都有可用的向量数据

---
```

- [ ] **Step 7：写六、分块管理（CRUD简写 + 重建向量详写）**

追加：

```markdown
## 六、分块管理 (KnowledgeChunkService)

分块是 RAG 系统中最小的检索单元。每个分块对应 MySQL 中一条 `t_knowledge_chunk` 记录，同时在 Milvus 中有一条对应的向量数据。

**CRUD 接口：**

| 操作 | 方法 | 说明 |
|------|------|------|
| 查看分块 | `pageQuery(docId, request)` | 分页查看文档的所有分块 |
| 新增分块 | `create(docId, request)` | 手动添加一个分块（同步写入 Milvus） |
| 编辑分块 | `update(docId, chunkId, request)` | 修改分块文本（同步更新 Milvus 向量） |
| 删除分块 | `delete(docId, chunkId)` | 删除分块（同步删除 Milvus 向量） |
| 批量启用/禁用 | `batchEnable()` / `batchDisable()` | 控制分块是否参与检索（不删向量） |
| 重建索引 | `rebuildByDocId(docId)` | 清空旧向量，对所有启用分块重新生成向量 |

**重建向量索引（关键优化点）：**

> **业务例子（伪代码）**
>
> \```
> 场景：更换了知识库的嵌入模型，旧向量维度不匹配，需要重建
>
> 调用 rebuildByDocId("doc_001")：
>
>   第1步：删除旧向量
>   - 调用 VectorStoreService.deleteDocumentVectors("kb_001", "doc_001")
>   - Milvus 删除 doc_001 的所有向量
>
>   第2步：查询所有启用的分块
>   - SELECT * FROM t_knowledge_chunk WHERE docId="doc_001" AND enabled=1
>   - 返回 25 条 KnowledgeChunkDO 记录
>
>   第3步：批量重新向量化并写入
>   - 25个分块文本 → 调用新 Embedding 模型 → 25个新向量
>   - 写入 Milvus：insertBatch()
>
>   输出：doc_001 的向量已用新模型重建
> \```
>
> **这段代码做什么？**
>
> `rebuildByDocId` 是"原地重建向量索引"的操作：
> 1. **删旧建新**：先清空 Milvus 中该文档的所有向量，再用当前启用分块重新生成
> 2. **不影响 MySQL**：只操作 Milvus 向量，MySQL 中的分块文本不变
> 3. **仅重建启用分块**：已禁用的分块（enabled=0）不参与重建，也不出现在检索结果中

**使用场景：**
- 更换嵌入模型后重建向量
- 手动编辑分块内容后刷新向量
- 批量禁用低质量分块降低检索噪声

---
```

---

## Task 2：写「工程级优化.md」— 限流与并发

**Files:**
- Create: `docs/工程级优化.md`

- [ ] **Step 1：写文件头 + 一、限流与并发（核心类 + 优化点表格）**

写入新文件 `docs/工程级优化.md`：

```markdown
# Ragent 工程级优化详解

> 本文档作为 RAG 优化流程树与实现指南的工程篇，覆盖限流与并发、全链路可观测性、MCP 工具生态三大企业级工程优化模块，配以代码示例和核心类说明。

---

## 一、限流与并发

### 整体流程：

\```
用户 SSE 请求进入
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ @ChatRateLimit 注解切面 (ChatRateLimitAspect)                               │
│    ├── 提取参数：question, conversationId, SseEmitter                        │
│    └── 调用 ChatQueueLimiter.enqueue()                                       │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 分布式信号量排队 (ChatQueueLimiter)                                          │
│    ├── 请求入队（Redis SortedSet 按序号排序）                                │
│    ├── 尝试获取信号量（tryAcquirePermit）                                    │
│    │   ├── 获取成功 → 立即提交业务逻辑到线程池                               │
│    │   └── 获取失败 → 定时轮询或等待发布-订阅通知                            │
│    └── SSE 连接关闭时自动释放信号量 + 发布通知                               │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 业务逻辑执行 (RAGChatServiceImpl.streamChat)                                 │
│    └── 完成后 emitter.onCompletion 触发信号量释放                            │
└─────────────────────────────────────────────────────────────────────────────┘
\```

### 核心类：

| 类名 | 职责 |
|------|------|
| `@ChatRateLimit` | 标注需要限流的 SSE 入口方法 |
| `ChatRateLimitAspect` | AOP 切面，拦截注解方法，提取参数，调用限流器 |
| `ChatQueueLimiter` | 核心限流器：Redis 信号量 + 全局队列 + 发布-订阅通知 |
| `RAGRateLimitProperties` | 限流配置（最大并发数、队列超时、租约时长） |

### 优化点：

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 全局信号量 | Redis PermitExpirableSemaphore，分布式部署下全局限流 | `SEMAPHORE_NAME = "rag:global:chat"` |
| FIFO 排队 | Redis SortedSet 按全局序号排序，先到先得 | `QUEUE_KEY = "rag:global:chat:queue"` |
| Lua 原子抢占 | 用 Lua 脚本原子判断队列位置 + 移除操作，防止并发竞争 | `queue_claim_atomic.lua` |
| 发布-订阅唤醒 | 信号量释放后发布通知，等待者无需空轮询 | `NOTIFY_TOPIC = "rag:global:chat:queue:notify"` |
| 自动 Lease 过期 | 信号量有 TTL（默认90秒），连接异常时自动释放，防死锁 | `globalLeaseSeconds` 配置项 |
| 超时拒绝 | 队列等待超时后发送 SSE 拒绝消息并记录对话历史 | `recordRejectedConversation()` |
| TTL 跨线程透传 | 使用 `TtlExecutors` 包装线程池，用户上下文自动传递 | `chatEntryExecutor` 配置 |
```

- [ ] **Step 2：写限流业务伪代码 + 这段代码做什么 + Java代码**

追加：

```markdown
**代码示例：**

> **业务例子（伪代码）**
>
> \```
> 配置：globalMaxConcurrent=3，globalMaxWaitSeconds=30
>
> 时间轴（并发10个用户同时发问）：
>
>   T=0s：用户A/B/C 同时到达
>   - 用户A：tryAcquirePermit() → 获得 permit-1 → 立即执行 → onAcquire()
>   - 用户B：tryAcquirePermit() → 获得 permit-2 → 立即执行 → onAcquire()
>   - 用户C：tryAcquirePermit() → 获得 permit-3 → 立即执行 → onAcquire()
>
>   T=0s：用户D/E/F/G 到达（信号量已满）
>   - 用户D：tryAcquirePermit() → 获取失败 → 入队 seq=4 → 轮询等待
>   - 用户E：入队 seq=5 → 等待
>   - 用户F：入队 seq=6 → 等待
>   - 用户G：入队 seq=7 → 等待
>
>   T=5s：用户A 回答完成，SSE 关闭
>   - emitter.onCompletion → releaseOnce() → 释放 permit-1
>   - 发布 NOTIFY_TOPIC："permit_released"
>
>   T=5s：PollNotifier 收到通知
>   - claimIfReady(seq=4, availablePermits=1) → Lua 脚本：D 在队列最前 → 成功
>   - 用户D：获得 permit → 立即执行 → onAcquire()
>
>   T=35s：用户G（等待超过30秒）
>   - deadline 超时 → 移出队列
>   - sendRejectEvents(emitter) → 发送 SSE 拒绝消息"系统繁忙，请稍后再试"
>   - 记录对话历史（用户消息 + 拒绝回复）
> \```
>
> **这段代码做什么？**
>
> 这是"全局 SSE 并发限流与排队"的核心实现，保证服务在高并发下不过载：
> 1. **全局信号量**：无论多少实例部署，Redis 信号量保证全局最多 N 个并发（如3个）
> 2. **FIFO 排队**：超出并发上限的请求按先后顺序排队，用全局递增序号保证公平性
> 3. **Lua 原子抢占**：用 Lua 脚本判断"你是否排在队列前面且有空闲信号量"，避免多个等待者同时抢占导致超发
> 4. **发布-订阅唤醒**：信号量释放时发 Redis 通知，等待者收到通知立即尝试，而不是傻等定时器（响应更快）
> 5. **超时兜底**：等待超过配置时间（默认30秒）时，优雅拒绝并记录对话，用户体验不会"无响应"

```java
// ChatRateLimitAspect.java:57-76 - AOP 拦截 + 限流入口
@Around("@annotation(com.nageoffer.ai.ragent.rag.aop.ChatRateLimit)")
public Object limitStreamChat(ProceedingJoinPoint joinPoint) throws Throwable {
    Object[] args = joinPoint.getArgs();
    // 参数约定：args[0]=question, args[1]=conversationId, args[3]=SseEmitter
    String question = args[0] instanceof String q ? q : "";
    String actualConversationId = StrUtil.isBlank((String) args[1])
            ? IdUtil.getSnowflakeNextIdStr() : (String) args[1];

    // 将真实业务逻辑封装成 Runnable 传入限流器
    chatQueueLimiter.enqueue(question, actualConversationId, emitter, () -> {
        invokeWithTrace(method, target, args, question, actualConversationId, emitter);
    });
    return null;  // SSE 是异步的，立即返回 null
}

// ChatQueueLimiter.java:111-143 - 限流排队核心
public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
    if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
        chatEntryExecutor.execute(onAcquire);  // 限流关闭，直接执行
        return;
    }

    String requestId = IdUtil.getSnowflakeNextIdStr();
    long seq = nextQueueSeq();  // 全局递增序号（Redis INCR）
    RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(QUEUE_KEY, StringCodec.INSTANCE);
    queue.add(seq, requestId);  // 入队

    // SSE 连接关闭时的释放逻辑（完成/超时/出错）
    Runnable releaseOnce = () -> {
        queue.remove(requestId);
        String permitId = permitRef.getAndSet(null);
        if (permitId != null) {
            redissonClient.getPermitExpirableSemaphore(SEMAPHORE_NAME).release(permitId);
            publishQueueNotify();  // 释放后通知等待者
        }
    };
    emitter.onCompletion(releaseOnce);
    emitter.onTimeout(releaseOnce);
    emitter.onError(e -> releaseOnce.run());

    // 立即尝试获取信号量（如果有空闲名额）
    if (tryAcquireIfReady(queue, requestId, permitRef, cancelled, onAcquire)) {
        return;
    }

    // 没有空闲名额，注册轮询 + 订阅通知
    scheduleQueuePoll(queue, requestId, ...);
}

// ChatQueueLimiter.java:260-281 - Redis PermitExpirableSemaphore
private String tryAcquirePermit() {
    RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(SEMAPHORE_NAME);
    semaphore.trySetPermits(rateLimitProperties.getGlobalMaxConcurrent());  // 初始化最大并发数
    try {
        // tryAcquire(waitTime=0, leaseTime=90s) - 不等待，立即返回
        // leaseTime 保证信号量最多持有90秒，连接异常时自动释放（防死锁）
        return semaphore.tryAcquire(0, rateLimitProperties.getGlobalLeaseSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return null;
    }
}
```

**使用场景：** 防止高并发下大量 LLM 调用压垮服务，让用户排队等待而不是直接报错。

**优化优点：**
- Redis 分布式信号量支持水平扩展，多实例统一限流
- 发布-订阅通知避免无谓的轮询，资源占用低
- 租约过期防止因网络中断导致的死锁

---
```

- [ ] **Step 3：写二、全链路可观测性**

追加：

```markdown
## 二、全链路可观测性 (@RagTraceNode AOP)

### 整体流程：

\```
RAGChatServiceImpl.streamChat() 方法被调用
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ChatRateLimitAspect.invokeWithTrace()                                       │
│    ├── 生成 traceId（雪花ID）                                                │
│    ├── 写入 t_rag_trace_run（状态=RUNNING）                                  │
│    └── RagTraceContext.setTraceId(traceId)  // 设置线程本地变量              │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 方法链路执行（被 @RagTraceNode 标注的方法自动被 RagTraceAspect 拦截）        │
│    ├── rewriteWithSplit() → 生成节点记录 node_1（REWRITE，父=null）          │
│    ├── resolve() → 生成节点记录 node_2（INTENT，父=null）                    │
│    ├── retrieveKnowledgeChannels() → 生成节点记录 node_3（RETRIEVE，父=null）│
│    └── 每个节点记录：nodeId, parentNodeId, depth, 耗时, 状态                │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ChatRateLimitAspect.invokeWithTrace() 完成                                  │
│    └── 更新 t_rag_trace_run（状态=SUCCESS/ERROR，总耗时）                    │
└─────────────────────────────────────────────────────────────────────────────┘
\```

### 核心类：

| 类名 | 职责 |
|------|------|
| `@RagTraceRoot` | 标注链路入口方法（每次请求创建一条 TraceRun 记录） |
| `@RagTraceNode` | 标注链路中的每个关键环节（自动创建 TraceNode 记录） |
| `RagTraceContext` | ThreadLocal，存储当前线程的 traceId 和节点栈 |
| `RagTraceAspect` | AOP 切面，拦截 @RagTraceRoot/@RagTraceNode，完成记录 |
| `RagTraceRecordService` | 异步写入追踪数据到 MySQL |
| `RagTraceRunDO` | 链路主记录（t_rag_trace_run） |
| `RagTraceNodeDO` | 节点记录（t_rag_trace_node） |

### 优化点：

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 零侵入 | 业务代码不需要写任何追踪逻辑，只加注解 | `@RagTraceNode` 注解 |
| 父子节点树 | 通过 ThreadLocal 栈维护当前节点，实现嵌套节点树 | `RagTraceContext.pushNode() / popNode()` |
| 深度记录 | 记录节点调用深度，可视化为树形调用链 | `RagTraceContext.depth()` |
| 异步写入 | 追踪数据写入不阻塞业务，后台异步落库 | `RagTraceRecordService` |
| 开关控制 | `rag.trace.enabled=false` 可全局关闭，不影响业务 | `RagTraceProperties.isEnabled()` |

**代码示例：**

> **业务例子（伪代码）**
>
> \```
> 用户问："员工请假流程是什么？"
>
> 一次请求产生的追踪树：
>
>   [TraceRun] traceId="trace_001", status=SUCCESS, duration=2340ms
>       │
>       ├── [Node] query-rewrite-and-split (REWRITE, depth=0, 320ms) ← rewriteWithSplit()
>       │
>       ├── [Node] intent-resolve (INTENT, depth=0, 540ms) ← resolve()
>       │       └── [Node] intent-classify-targets (INTENT, depth=1, 490ms) ← classifyTargets()
>       │
>       ├── [Node] retrieve-knowledge (RETRIEVE, depth=0, 890ms) ← retrieveKnowledgeChannels()
>       │       ├── [Node] intent-directed-search (SEARCH, depth=1, 430ms) ← IntentDirectedSearchChannel
>       │       └── [Node] rerank (RERANK, depth=1, 280ms) ← RerankPostProcessor
>       │
>       └── [Node] stream-generate (LLM, depth=0, 590ms) ← LLMService.streamChat()
>
> 总耗时：2340ms
> 最慢环节：检索(890ms) → 可优化 Rerank 或 TopK 配置
> \```
>
> **这段代码做什么？**
>
> 这是"注解式链路追踪"的核心实现，无侵入地记录每个环节的执行情况：
> 1. **入口创建链路**：`@RagTraceRoot` 标注的方法执行时，创建一条 `RagTraceRunDO` 记录，并将 traceId 存入 ThreadLocal
> 2. **节点自动记录**：每个 `@RagTraceNode` 方法在执行前后，切面自动创建 `RagTraceNodeDO`，记录耗时和状态
> 3. **父子关系维护**：通过 ThreadLocal 的节点栈（push/pop），实现嵌套调用的父子关系记录
> 4. **异常捕获**：节点抛出异常时，记录 status=ERROR 和截断的错误信息，再重新抛出

```java
// RagTraceAspect.java:117-173 - @RagTraceNode 拦截实现
@Around("@annotation(traceNode)")
public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
    if (!traceProperties.isEnabled()) {
        return joinPoint.proceed();  // 开关关闭，直接透传
    }
    String traceId = RagTraceContext.getTraceId();
    if (StrUtil.isBlank(traceId)) {
        return joinPoint.proceed();  // 不在链路上下文中，直接透传
    }

    String nodeId = IdUtil.getSnowflakeNextIdStr();
    String parentNodeId = RagTraceContext.currentNodeId();  // 当前栈顶为父节点
    int depth = RagTraceContext.depth();                      // 栈深度
    long startMillis = System.currentTimeMillis();

    // 创建节点记录（RUNNING 状态）
    traceRecordService.startNode(RagTraceNodeDO.builder()
            .traceId(traceId)
            .nodeId(nodeId)
            .parentNodeId(parentNodeId)  // 记录父子关系
            .depth(depth)
            .nodeType(StrUtil.blankToDefault(traceNode.type(), "METHOD"))
            .nodeName(StrUtil.blankToDefault(traceNode.name(), method.getName()))
            .status(STATUS_RUNNING)
            .build());

    RagTraceContext.pushNode(nodeId);  // 入栈，成为新的当前节点
    try {
        Object result = joinPoint.proceed();
        traceRecordService.finishNode(traceId, nodeId, STATUS_SUCCESS, null,
                new Date(), System.currentTimeMillis() - startMillis);
        return result;
    } catch (Throwable ex) {
        traceRecordService.finishNode(traceId, nodeId, STATUS_ERROR,
                truncateError(ex), new Date(), System.currentTimeMillis() - startMillis);
        throw ex;  // 重新抛出，不吞异常
    } finally {
        RagTraceContext.popNode();  // 出栈，恢复父节点为当前节点
    }
}
```

**使用场景：** 排查为什么某次问答很慢、找出哪个环节报错、统计各环节平均耗时做性能优化。

**优化优点：**
- 加一个注解就能被追踪，对业务代码零侵入
- 父子节点树结构可以直观展示"哪个子调用最慢"
- 开关控制保证高压力时可临时关闭追踪降低开销

---
```

- [ ] **Step 4：写三、MCP 工具生态**

追加：

```markdown
## 三、MCP 工具生态

### 整体流程：

\```
意图识别返回 kind=MCP 的节点
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ RetrievalEngine - MCP 分支                                                  │
│    ├── 从 MCPToolRegistry 找到对应的 MCPToolExecutor                         │
│    └── 调用 LLMMCPParameterExtractor.extractParameters()                    │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ LLMMCPParameterExtractor                                                    │
│    ├── 构建 Prompt：系统提示词 + 工具定义（参数名/类型/描述）               │
│    ├── 调用 LLMService.chat()                                                │
│    └── 解析 JSON 响应，填充默认值                                            │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ MCPToolExecutor.execute(MCPRequest)                                         │
│    ├── 远程调用 MCP Server HTTP 接口                                         │
│    └── 返回 MCPResponse{success, data, error}                                │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ContextFormatter.formatMcpContext()                                          │
│    └── 将 MCPResponse 格式化为 Prompt 上下文字符串                           │
└─────────────────────────────────────────────────────────────────────────────┘
\```

### 核心类：

| 类名 | 职责 |
|------|------|
| `MCPTool` | 工具元数据定义（toolId、描述、参数定义列表） |
| `MCPToolRegistry` | 工具注册表，管理所有 MCPToolExecutor 实例 |
| `MCPToolExecutor` | 工具执行器接口，一个实现类对应一类外部 API |
| `LLMMCPParameterExtractor` | 用 LLM 从用户问题中提取工具所需的参数 |
| `MCPParameterExtractor` | 参数提取器接口 |
| `MCPRequest` / `MCPResponse` | 工具调用的请求/响应数据结构 |

### 优化点：

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 工具自动注册 | Spring 容器启动时扫描所有 MCPToolExecutor，自动注册 | `MCPToolRegistry` |
| LLM 参数提取 | 不需要固定规则，LLM 理解自然语言后提取参数 | `LLMMCPParameterExtractor` |
| 自定义提示词 | 每个意图节点可配置 `paramPromptTemplate`，覆盖默认提取 Prompt | `extractParameters(..., customPromptTemplate)` |
| 默认值填充 | 参数未被 LLM 提取时自动填充工具定义的 defaultValue | `fillDefaults()` |
| JSON 解析兜底 | LLM 返回异常时回退到全默认值，保证工具能被调用 | `buildDefaultParameters()` |

**代码示例：**

> **业务例子（伪代码）**
>
> \```
> 场景：意图识别命中"考勤查询"MCP工具节点
>
> MCPTool 定义（考勤查询工具）：
>   toolId: "attendance_query"
>   description: "查询员工的考勤记录"
>   parameters:
>     - employeeId (string, 必填): 员工工号
>     - startDate  (string, 可选): 开始日期，格式yyyy-MM-dd，默认=本月1号
>     - endDate    (string, 可选): 结束日期，格式yyyy-MM-dd，默认=今天
>
> 用户问题："帮我查一下工号A001的3月考勤"
>
> LLMMCPParameterExtractor 处理：
>
>   第1步：构建 Prompt
>   System: "根据工具定义，从用户问题中提取参数，以JSON格式输出"
>   User 1: "工具定义如下：
>     工具ID: attendance_query
>     参数列表:
>       - employeeId (类型: string, 必填): 员工工号
>       - startDate  (类型: string, 可选): 开始日期 [默认值: 2024-03-01]
>       - endDate    (类型: string, 可选): 结束日期 [默认值: 2024-03-31]"
>   User 2: "请根据以上工具定义，从下面的问题中提取参数：
>     帮我查一下工号A001的3月考勤"
>
>   第2步：LLM 返回
>   {"employeeId": "A001", "startDate": "2024-03-01", "endDate": "2024-03-31"}
>
>   第3步：解析 + 填充默认值
>   extracted: {employeeId="A001", startDate="2024-03-01", endDate="2024-03-31"}
>   fillDefaults: 所有参数已提取，无需填充
>
>   第4步：调用 MCPToolExecutor.execute()
>   MCPRequest{toolId="attendance_query", parameters={...}}
>   → HTTP POST https://hr-system.corp.com/mcp/attendance/query
>   → 返回：{success=true, data={records: [...]}}
>
> 输出：MCPResponse 格式化为 Prompt 上下文，LLM 基于考勤数据生成回答
> \```
>
> **这段代码做什么？**
>
> 这是"LLM 驱动的工具参数提取"核心实现，让工具调用支持自然语言：
> 1. **工具定义描述化**：将 MCPTool 的参数定义转成 LLM 能理解的文字描述（参数名、类型、是否必填、描述、枚举值）
> 2. **LLM 语义提取**：让 LLM 理解用户自然语言，从中抽取符合工具参数规范的值
> 3. **安全解析**：只提取工具定义中声明的参数，防止 LLM 幻觉出额外字段
> 4. **默认值兜底**：可选参数 LLM 没有提取时，自动填充工具定义的默认值
> 5. **全局异常兜底**：JSON 解析失败或 LLM 异常时，返回全默认值，工具调用仍能继续

```java
// LLMMCPParameterExtractor.java:63-107 - 参数提取主逻辑
@Override
public Map<String, Object> extractParameters(String userQuestion, MCPTool tool, String customPromptTemplate) {
    List<ChatMessage> messages = new ArrayList<>(3);
    // 优先使用节点配置的自定义 Prompt，否则用默认 Prompt
    String systemPrompt = StrUtil.isNotBlank(customPromptTemplate)
            ? customPromptTemplate
            : promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH);

    messages.add(ChatMessage.system(systemPrompt));
    messages.add(ChatMessage.user("工具定义如下：\n" + buildToolDefinition(tool)));
    messages.add(ChatMessage.user("请根据以上工具定义，从下面的问题中提取参数：\n" + userQuestion));

    String raw = null;
    try {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)  // 低温度保证稳定性
                .topP(0.3D)
                .thinking(false)
                .build();
        raw = llmService.chat(request);

        Map<String, Object> extracted = parseJsonResponse(raw, tool);  // 安全解析
        fillDefaults(extracted, tool);  // 填充可选参数默认值
        return extracted;
    } catch (JsonSyntaxException e) {
        log.warn("MCP 参数提取-JSON解析失败, toolId: {}", tool.getToolId());
        return buildDefaultParameters(tool);  // 全默认值兜底
    } catch (Exception e) {
        log.error("MCP 参数提取异常, toolId: {}", tool.getToolId(), e);
        return buildDefaultParameters(tool);  // 全默认值兜底
    }
}

// LLMMCPParameterExtractor.java:148-169 - 安全解析（只取工具声明的参数）
private Map<String, Object> parseJsonResponse(String raw, MCPTool tool) {
    String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
    JsonObject obj = JsonParser.parseString(cleaned).getAsJsonObject();
    Map<String, Object> result = new HashMap<>();
    // 遍历工具定义的参数，而非 JSON 所有字段（防止 LLM 幻觉注入额外参数）
    for (String paramName : tool.getParameters().keySet()) {
        if (obj.has(paramName) && !obj.get(paramName).isJsonNull()) {
            result.put(paramName, convertJsonElement(obj.get(paramName)));
        }
    }
    return result;
}
```

**如何注册一个新的 MCP 工具：**

1. 实现 `MCPToolExecutor` 接口，声明为 Spring Bean
2. 在 `getToolDefinition()` 中定义工具的参数规范
3. 在 `execute(MCPRequest)` 中实现实际的 HTTP/DB 调用逻辑
4. 在意图树中添加一个 `kind=MCP` 的叶子节点，指定 `mcpToolId`
5. 启动后 `MCPToolRegistry` 自动发现并注册

**使用场景：** 用户询问需要查询实时数据的问题（考勤、订单、库存、天气等），知识库中没有，但可以通过工具 API 实时获取。

**优化优点：**
- LLM 驱动参数提取，无需为每个工具写专用的参数解析规则
- 自定义提示词让参数提取更精准（特别是复杂的日期/枚举参数）
- 全兜底机制保证工具调用不会因参数提取失败而中断

---
```

- [ ] **Step 5：自检 + 写附录配置项**

追加：

```markdown
## 四、附录：工程级优化关键配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `rag.rate-limit.global-enabled` | 开启全局限流 | true |
| `rag.rate-limit.global-max-concurrent` | 最大并发数 | 3 |
| `rag.rate-limit.global-max-wait-seconds` | 队列最大等待时间 | 30 |
| `rag.rate-limit.global-lease-seconds` | 信号量 TTL（防死锁） | 90 |
| `rag.rate-limit.global-poll-interval-ms` | 轮询间隔 | 200 |
| `rag.trace.enabled` | 开启链路追踪 | true |
| `rag.trace.max-error-length` | 错误信息截断长度 | 500 |
| `rag.knowledge.schedule.scan-delay-ms` | 定时刷新扫描间隔 | 10000 |
| `rag.knowledge.schedule.running-timeout-minutes` | RUNNING 超时恢复阈值 | 30 |
| `rag.knowledge.schedule.lock-seconds` | 分布式锁有效期 | 300 |
```

---

## 自检结果

**Spec 覆盖检查：**
- ✅ 限流：ChatQueueLimiter 全覆盖（信号量、排队、超时拒绝）
- ✅ 可观测性：RagTraceAspect 全覆盖（Root/Node 注解、父子节点树）
- ✅ MCP：LLMMCPParameterExtractor 全覆盖（参数提取、兜底、新工具注册指南）
- ✅ 文档入库：IngestionEngine 全覆盖（流水线、节点链、条件跳过）
- ✅ 定时刷新：KnowledgeDocumentScheduleJob 全覆盖（分布式锁、内容检测、原子替换）
- ✅ 所有 CRUD 模块简写完毕

**无占位符：** 无 TBD / TODO / "参考上一节" 等不完整内容

**类型一致性：** 所有代码引用的类名与实际文件路径一致
