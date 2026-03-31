# Ragent 企业级 RAG 优化流程树与实现指南

> 本文档以 RAG 核心业务流程为主干，系统梳理 Ragent 项目中每个环节的企业级优化点，配以代码示例和核心类说明，帮助快速理解业务逻辑及工程实践。

---

## 一、整体业务流程树

```
用户请求
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. 记忆加载 (ConversationMemoryService)                                     │
│    ├── 滑动窗口管理                                                          │
│    ├── 自动摘要压缩                                                          │
│    └── 持久化存储                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. 问题改写 (QueryRewriteService)                                           │
│    ├── 查询归一化 (QueryTermMappingService)                                   │
│    ├── 多问句拆分                                                             │
│    └── LLM 增强改写                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. 意图识别 (IntentResolver)                                                │
│    ├── 树形多级分类                                                          │
│    ├── 置信度阈值过滤                                                        │
│    ├── 歧义引导 (IntentGuidanceService)                                      │
│    └── 意图数量上限控制                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. 检索引擎 (RetrievalEngine)                                               │
│    ├── 多通道并行检索 (MultiChannelRetrievalEngine)                         │
│    │   ├── 意图定向检索 (IntentDirectedSearchChannel)                       │
│    │   └── 全局向量检索 (VectorGlobalSearchChannel)                         │
│    ├── 后处理器链 (SearchResultPostProcessor)                               │
│    │   ├── 去重 (DeduplicationPostProcessor)                                │
│    │   └── 重排序 (RerankPostProcessor)                                     │
│    └── MCP 工具调用                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. Prompt 组装 (RAGPromptService)                                            │
│    ├── 场景化 Prompt                                                         │
│    ├── 上下文格式化                                                          │
│    └── 子问题注入                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 6. 流式生成 (LLMService)                                                    │
│    ├── 首包探测                                                              │
│    ├── 模型路由与容错                                                        │
│    ├── 熔断降级                                                             │
│    └── SSE 输出                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、每个环节详细说明

### 1. 记忆加载 (ConversationMemoryService)

**核心类：**
- `ConversationMemoryService` - 接口定义
- `DefaultConversationMemoryService` - 默认实现
- `JdbcConversationMemoryStore` - JDBC 存储
- `JdbcConversationMemorySummaryService` - 摘要服务

**优化点：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 滑动窗口 | 保留最近 N 轮对话 | `DefaultConversationMemoryService` |
| 自动摘要 | 超过阈值时调用 LLM 摘要 | `ConversationMemorySummaryService` |
| 持久化 | 摘要持久化到 MySQL | `JdbcConversationMemoryStore` |
| TTL 过期 | 记忆自动过期释放资源 | 配置项控制 |

**代码示例：**

> **这段代码做什么？**
> 
> 这是对话记忆的加载和追加逻辑。当用户发起对话时：
> 1. **并行加载**：同时加载"摘要"（历史对话的压缩版本）和"历史消息"（原始对话记录），避免串行加载耗时
> 2. **自动压缩**：每次追加新消息时，检查是否需要生成摘要（防止历史记录过长导致 Token 爆炸）
> 3. **合并返回**：将摘要放在最前面，历史消息跟在后面，返回给下游使用

```java
// DefaultConversationMemoryService.java:44-74
@Override
public List<ChatMessage> load(String conversationId, String userId) {
    // 并行加载摘要和历史记录 - 提升响应速度
    CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
            () -> loadSummaryWithFallback(conversationId, userId)
    );
    CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
            () -> loadHistoryWithFallback(conversationId, userId)
    );

    return CompletableFuture.allOf(summaryFuture, historyFuture)
            .thenApply(v -> {
                ChatMessage summary = summaryFuture.join();
                List<ChatMessage> history = historyFuture.join();
                return attachSummary(summary, history);
            })
            .join();
}

@Override
public String append(String conversationId, String userId, ChatMessage message) {
    String messageId = memoryStore.append(conversationId, userId, message);
    summaryService.compressIfNeeded(conversationId, userId, message);  // 自动触发压缩
    return messageId;
}

// 合并摘要和历史记录
private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
    if (CollUtil.isEmpty(messages)) {
        return List.of();
    }
    if (summary == null) {
        return messages;
    }
    List<ChatMessage> result = new ArrayList<>();
    result.add(summaryService.decorateIfNeeded(summary));  // 摘要放最前面
    result.addAll(messages);
    return result;
}
```

**使用场景：** 多轮对话时补全上下文，解决"用户说'它'系统不知道指什么"的问题。

---

### 2. 问题改写 (QueryRewriteService)

**核心类：**
- `QueryRewriteService` - 接口
- `MultiQuestionRewriteService` - LLM 改写实现
- `QueryTermMappingService` - 术语归一化

**优化点：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 术语归一化 | "报销"→"费用报销申请" | `QueryTermMappingService.normalize()` |
| 多问拆分 | "如何请假？工资多少？"拆成2个 | `MultiQuestionRewriteService.rewriteWithSplit()` |
| LLM 增强 | 语义级别的改写提升检索效果 | 调用 LLM 服务 |
| 兜底策略 | LLM 失败时回退规则方法 | `ruleBasedSplit()` |

**代码示例：**

> **这段代码做什么？**
> 
> 这是将用户的问题进行"改写+拆分"的核心逻辑：
> 1. **开关控制**：如果关闭了 LLM 改写功能，直接用规则方法（兜底方案）
> 2. **术语归一化**：把用户输入的简称、缩写转成标准说法（如"报销"→"费用报销申请"）
> 3. **LLM 改写**：调用大模型语义理解能力，将问题改写成更适合检索的形式
> 4. **多问拆分**：如果用户一个问题包含多个问句（如"如何请假？工资多少？"），拆成多个子问题分别检索

```java
// MultiQuestionRewriteService.java:69-129
@Override
@RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
    // 开关关闭：使用规则方法（兜底）
    if (!ragConfigProperties.getQueryRewriteEnabled()) {
        String normalized = queryTermMappingService.normalize(userQuestion);
        List<String> subs = ruleBasedSplit(normalized);
        return new RewriteResult(normalized, subs);
    }

    // 1. 术语归一化
    String normalizedQuestion = queryTermMappingService.normalize(userQuestion);
    
    // 2. 调用 LLM 进行改写 + 拆分
    return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
}

// LLM 改写 + 拆分核心逻辑
private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                             String originalQuestion,
                                             List<ChatMessage> history) {
    String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
    ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);

    try {
        String raw = llmService.chat(req);
        RewriteResult parsed = parseRewriteAndSplit(raw);

        if (parsed != null) {
            log.info("原始问题：{}，归一化后：{}，改写结果：{}，子问题：{}",
                    originalQuestion, normalizedQuestion, 
                    parsed.rewrittenQuestion(), parsed.subQuestions());
            return parsed;
        }
    } catch (Exception e) {
        log.warn("LLM 改写失败，使用归一化问题兜底", e);
    }

    // 兜底：使用归一化结果 + 规则拆分
    return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
}

// 构建请求 - 只保留最近 2 轮对话，避免 Token 浪费
private ChatRequest buildRewriteRequest(String systemPrompt,
                                         String question,
                                         List<ChatMessage> history) {
    List<ChatMessage> messages = new ArrayList<>();
    if (StrUtil.isNotBlank(systemPrompt)) {
        messages.add(ChatMessage.system(systemPrompt));
    }

    if (CollUtil.isNotEmpty(history)) {
        // 只保留最近 2 轮（4 条消息）
        List<ChatMessage> recentHistory = history.stream()
                .filter(msg -> msg.getRole() == ChatMessage.Role.USER
                        || msg.getRole() == ChatMessage.Role.ASSISTANT)
                .skip(Math.max(0, history.size() - 4))
                .toList();
        messages.addAll(recentHistory);
    }
    messages.add(ChatMessage.user(question));

    return ChatRequest.builder()
            .messages(messages)
            .temperature(0.1D)  // 低温度保证稳定性
            .topP(0.3D)
            .thinking(false)
            .build();
}

// 解析 LLM 返回的 JSON
private RewriteResult parseRewriteAndSplit(String raw) {
    String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
    JsonElement root = JsonParser.parseString(cleaned);
    
    JsonObject obj = root.getAsJsonObject();
    String rewrite = obj.get("rewrite").getAsString().trim();
    
    List<String> subs = new ArrayList<>();
    JsonArray arr = obj.getAsJsonArray("sub_questions");
    for (JsonElement el : arr) {
        subs.add(el.getAsString().trim());
    }
    
    return new RewriteResult(rewrite, subs);
}

// 兜底：规则拆分
private List<String> ruleBasedSplit(String question) {
    List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toList());

    return parts.isEmpty() 
            ? List.of(question) 
            : parts.stream()
                    .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                    .toList();
}
```

**使用场景：**
 解决"用户问'报销咋整'向量检索效果差"的问题。

**优化优点：**
- 术语归一化让模糊查询变精确
- 多问拆分让复杂问题并行检索
- 兜底策略保证服务可用性

---

### 3. 意图识别 (IntentResolver)

**核心类：**
- `IntentResolver` - 意图解析器
- `IntentClassifier` - 分类器接口
- `DefaultIntentClassifier` - 默认实现（树形分类）
- `IntentNode` - 意图节点

**优化点：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 树形多级分类 | 领域→类目→话题逐级筛选 | `IntentTreeFactory` |
| 置信度阈值 | score >= 0.7 才命中 | `INTENT_MIN_SCORE` |
| 歧义引导 | 置信度不足时追问用户 | `IntentGuidanceService.detectAmbiguity()` |
| 意图数量上限 | 每个子问题最多3个 | `MAX_INTENT_COUNT` |

**代码示例：**

> **这段代码做什么？**
> 
> 这是意图识别的核心逻辑，将用户问题匹配到知识库：
> 1. **提取子问题**：从改写结果中获取需要检索的子问题列表
> 2. **并行分类**：对每个子问题并行调用 LLM 进行意图分类（提升响应速度）
> 3. **过滤阈值**：只保留置信度 >= 0.7 的意图，过滤掉不靠谱的匹配
> 4. **数量限制**：限制每个子问题最多返回 3 个意图，防止检索结果过多
> 5. **意图树**：所有意图以树形结构存储，包含"领域→类目→话题"层级，LLM 只需要对叶子节点打分

```java
// IntentResolver.java:52-144
@RagTraceNode(name = "intent-resolve", type = "INTENT")
public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
    // 1. 提取子问题列表
    List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
            ? rewriteResult.subQuestions()
            : List.of(rewriteResult.rewrittenQuestion());
    
    // 2. 并行对每个子问题进行意图分类 - 提升响应速度
    List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
            .map(q -> CompletableFuture.supplyAsync(
                    () -> new SubQuestionIntent(q, classifyIntents(q)),
                    intentClassifyExecutor
            ))
            .toList();
    
    List<SubQuestionIntent> subIntents = tasks.stream()
            .map(CompletableFuture::join)
            .toList();
    
    // 3. 限制总意图数量（保底 + 配额策略）
    return capTotalIntents(subIntents);
}

// 过滤 + 排序
private List<NodeScore> classifyIntents(String question) {
    List<NodeScore> scores = intentClassifier.classifyTargets(question);
    return scores.stream()
            .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)  // 置信度阈值
            .limit(MAX_INTENT_COUNT)  // 最多返回 3 个
            .toList();
}

// DefaultIntentClassifier.java:136-207 - LLM 树形分类核心实现
@Override
public List<NodeScore> classifyTargets(String question) {
    IntentTreeData data = loadIntentTreeData();  // 从 Redis 加载意图树
    
    // 构建 Prompt：列出所有叶子节点信息
    String systemPrompt = buildPrompt(data.leafNodes);
    
    ChatRequest request = ChatRequest.builder()
            .messages(List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(question)
            ))
            .temperature(0.1D)
            .topP(0.3D)
            .thinking(false)
            .build();

    String raw = llmService.chat(request);

    // 解析 LLM 返回的 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
    List<NodeScore> scores = new ArrayList<>();
    JsonArray arr = root.getAsJsonArray();
    for (JsonElement el : arr) {
        JsonObject obj = el.getAsJsonObject();
        String id = obj.get("id").getAsString();
        double score = obj.get("score").getAsDouble();
        
        IntentNode node = data.id2Node.get(id);  // 映射回节点
        if (node != null) {
            scores.add(new NodeScore(node, score));
        }
    }

    // 降序排序返回
    scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());
    return scores;
}

// 构建发送给 LLM 的 Prompt
private String buildPrompt(List<IntentNode> leafNodes) {
    StringBuilder sb = new StringBuilder();
    for (IntentNode node : leafNodes) {
        sb.append("- id=").append(node.getId()).append("\n");
        sb.append("  path=").append(node.getFullPath()).append("\n");
        sb.append("  description=").append(node.getDescription()).append("\n");
        
        // 标注节点类型
        if (node.isMCP()) {
            sb.append("  type=MCP\n");
            sb.append("  toolId=").append(node.getMcpToolId()).append("\n");
        } else if (node.isSystem()) {
            sb.append("  type=SYSTEM\n");
        } else {
            sb.append("  type=KB\n");
        }
        
        if (node.getExamples() != null) {
            sb.append("  examples=").append(String.join(" / ", node.getExamples())).append("\n");
        }
    }
    return promptTemplateLoader.render(INTENT_CLASSIFIER_PROMPT_PATH, Map.of("intent_list", sb.toString()));
}
```

**使用场景：**
 解决"用户想问A但系统理解成B"的问题。

**优化优点：**
- 树形结构让意图粒度可控
- 歧义引导避免乱猜答案
- 意图分流到不同知识库，提升精度

---

### 4. 检索引擎 (RetrievalEngine)

#### 4.1 多通道并行检索

**核心类：**
- `MultiChannelRetrievalEngine` - 多通道引擎
- `SearchChannel` - 通道接口
- `IntentDirectedSearchChannel` - 意图定向通道
- `VectorGlobalSearchChannel` - 全局向量通道

**代码示例：**

> **这段代码做什么？**
> 
> 这是"多通道检索引擎"的核心逻辑，实现多种检索方式并行：
> 1. **多通道设计**：支持多检索通道（如意图定向检索、全局向量检索），每个通道独立执行
> 2. **并行执行**：所有通道同时运行，互不影响，耗时 = 最慢的那个通道
> 3. **通道隔离**：单个通道失败不会影响其他通道，通过 try-catch 隔离
> 4. **后处理链**：检索结果需要经过"去重→重排序"等后处理器处理
> 5. **优先级排序**：通道按 priority 排序，优先级高的结果优先保留

```java
// MultiChannelRetrievalEngine.java:66-162
public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
    // 1. 构建检索上下文
    SearchContext context = buildSearchContext(subIntents, topK);
    
    // 【阶段1：多通道并行检索】
    List<SearchChannelResult> channelResults = executeSearchChannels(context);
    if (CollUtil.isEmpty(channelResults)) {
        return List.of();
    }

    // 【阶段2：后置处理器链处理】
    return executePostProcessors(channelResults, context);
}

// 并行执行所有检索通道
private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
    // 过滤启用的通道并按优先级排序
    List<SearchChannel> enabledChannels = searchChannels.stream()
            .filter(channel -> channel.isEnabled(context))
            .sorted(Comparator.comparingInt(SearchChannel::getPriority))
            .toList();

    log.info("启用的检索通道：{}", enabledChannels.stream().map(SearchChannel::getName).toList());

    // 并行执行 - 每个通道独立线程池
    List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
            .map(channel -> CompletableFuture.supplyAsync(() -> {
                try {
                    return channel.search(context);
                } catch (Exception e) {
                    log.error("检索通道 {} 执行失败", channel.getName(), e);
                    return SearchChannelResult.builder()
                            .channelType(channel.getType())
                            .channelName(channel.getName())
                            .chunks(List.of())
                            .confidence(0.0)
                            .build();
                }
            }, ragRetrievalExecutor))
            .toList();

    // 等待所有结果并统计
    return futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .toList();
}

// 执行后置处理器链
private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                    SearchContext context) {
    List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
            .filter(processor -> processor.isEnabled(context))
            .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
            .toList();

    // 初始：合并所有通道的 Chunk
    List<RetrievedChunk> chunks = results.stream()
            .flatMap(r -> r.getChunks().stream())
            .collect(Collectors.toList());

    // 依次执行每个处理器
    for (SearchResultPostProcessor processor : enabledProcessors) {
        try {
            chunks = processor.process(chunks, results, context);
        } catch (Exception e) {
            log.error("后置处理器 {} 执行失败，跳过", processor.getName(), e);
        }
    }

    return chunks;
}
```

#### 4.2 意图定向检索通道

> **这段代码做什么？**
> 
> 这是"意图定向检索"通道的实现，根据用户意图精准检索对应知识库：
> 1. **启用条件**：只有当意图识别出 KB（知识库）类型的意图时才启用
> 2. **精准检索**：根据意图 ID 找到对应的知识库，只在该知识库中检索
> 3. **并行检索**：每个意图对应的知识库并行检索，最后合并结果
> 4. **置信度计算**：检索结果的置信度 = 意图匹配的最高分数

```java
// IntentDirectedSearchChannel.java:54-141 - 意图定向检索实现
@Override
public boolean isEnabled(SearchContext context) {
    if (!properties.getChannels().getIntentDirected().isEnabled()) {
        return false;
    }
    // 只有存在 KB 意图时才启用
    List<NodeScore> kbIntents = extractKbIntents(context);
    return CollUtil.isNotEmpty(kbIntents);
}

@Override
public SearchChannelResult search(SearchContext context) {
    long startTime = System.currentTimeMillis();

    // 提取 KB 意图
    List<NodeScore> kbIntents = extractKbIntents(context);

    if (CollUtil.isEmpty(kbIntents)) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED)
                .chunks(List.of())
                .confidence(0.0)
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }

    // 并行检索所有意图对应的知识库
    List<RetrievedChunk> allChunks = retrieveByIntents(
            context.getMainQuestion(),
            kbIntents,
            context.getTopK(),
            topKMultiplier
    );

    // 置信度 = 最高意图分数
    double confidence = kbIntents.stream()
            .mapToDouble(NodeScore::getScore)
            .max()
            .orElse(0.0);

    return SearchChannelResult.builder()
            .channelType(SearchChannelType.INTENT_DIRECTED)
            .channelName(getName())
            .chunks(allChunks)
            .confidence(confidence)
            .latencyMs(System.currentTimeMillis() - startTime)
            .build();
}

// 提取 KB 类型意图
private List<NodeScore> extractKbIntents(SearchContext context) {
    double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
    return context.getIntents().stream()
            .flatMap(si -> si.nodeScores().stream())
            .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
            .filter(ns -> ns.getScore() >= minScore)
            .toList();
}
```

#### 4.3 全局向量检索通道

> **这段代码做什么？**
> 
> 这是"全局向量检索"通道，作为兜底方案，确保不漏检：
> 1. **兜底触发**：当没有识别出意图 OR 意图置信度都很低时启用
> 2. **全局检索**：在所有知识库中进行向量检索，召回率最高但精度可能较低
> 3. **阈值判断**：当意图最高分 < 0.5 时，判定置信度不足，启用全局检索
> 4. **并行检索**：并行在所有 collection 中检索，最后合并结果

```java
// VectorGlobalSearchChannel.java:73-154 - 全局兜底检索
@Override
public boolean isEnabled(SearchContext context) {
    // 条件1：没有识别出任何意图
    List<NodeScore> allScores = context.getIntents().stream()
            .flatMap(si -> si.nodeScores().stream())
            .toList();
    if (CollUtil.isEmpty(allScores)) {
        log.info("未识别出任何意图，启用全局检索");
        return true;
    }

    // 条件2：意图置信度低于阈值
    double maxScore = allScores.stream()
            .mapToDouble(NodeScore::getScore)
            .max()
            .orElse(0.0);

    double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
    if (maxScore < threshold) {
        log.info("意图置信度过低（{}），启用全局检索", maxScore);
        return true;
    }

    return false;
}

@Override
public SearchChannelResult search(SearchContext context) {
    // 获取所有 KB 类型的 collection
    List<String> collections = getAllKBCollections();

    // 并行在所有 collection 中检索
    List<RetrievedChunk> allChunks = retrieveFromAllCollections(
            context.getMainQuestion(),
            collections,
            context.getTopK() * topKMultiplier
    );

    return SearchChannelResult.builder()
            .channelType(SearchChannelType.VECTOR_GLOBAL)
            .chunks(allChunks)
            .confidence(0.7)  // 全局检索置信度中等
            .build();
}

// 从数据库获取所有知识库 collection
private List<String> getAllKBCollections() {
    List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                    .select(KnowledgeBaseDO::getCollectionName)
                    .eq(KnowledgeBaseDO::getDeleted, 0)
    );
    return kbList.stream()
            .map(KnowledgeBaseDO::getCollectionName)
            .filter(StrUtil::isNotBlank)
            .toList();
}
```

#### 4.4 后处理器链

**核心类：**
- `SearchResultPostProcessor` - 处理器接口
- `DeduplicationPostProcessor` - 去重
- `RerankPostProcessor` - 重排序

**代码示例：**

> **这段代码做什么？**
> 
> 这是"后处理器链"的核心实现，对检索结果进行精炼：
> 1. **去重**：当同一个 Chunk 在多个通道中出现时，根据通道优先级保留最好的那个
> 2. **优先级**：意图检索 > 关键词检索 > 全局检索（优先级低的通道结果会被覆盖）
> 3. **分数合并**：如果同一个 Chunk 被多次检索到，取分数最高的那个
> 4. **重排序**：最后调用 Rerank 模型对所有结果进行精排序，提升相关性

```java
// DeduplicationPostProcessor.java:57-111 - 去重处理器实现
@Override
public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                    List<SearchChannelResult> results,
                                    SearchContext context) {
    // 使用 LinkedHashMap 保持顺序并去重
    Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();

    // 按通道优先级排序（优先级高的通道结果优先保留）
    results.stream()
            .sorted((r1, r2) -> Integer.compare(
                    getChannelPriority(r1.getChannelType()),
                    getChannelPriority(r2.getChannelType())
            ))
            .forEach(result -> {
                for (RetrievedChunk chunk : result.getChunks()) {
                    String key = generateChunkKey(chunk);

                    if (!chunkMap.containsKey(key)) {
                        chunkMap.put(key, chunk);
                    } else {
                        // 已存在，合并分数（取最高分）
                        RetrievedChunk existing = chunkMap.get(key);
                        if (chunk.getScore() > existing.getScore()) {
                            chunkMap.put(key, chunk);
                        }
                    }
                }
            });

    return new ArrayList<>(chunkMap.values());
}

// 生成唯一键
private String generateChunkKey(RetrievedChunk chunk) {
    return chunk.getId() != null
            ? chunk.getId()
            : String.valueOf(chunk.getText().hashCode());
}

// 通道优先级：意图检索 > 关键词检索 > 全局检索
private int getChannelPriority(SearchChannelType type) {
    return switch (type) {
        case INTENT_DIRECTED -> 1;   // 优先级最高
        case KEYWORD_ES -> 2;
        case VECTOR_GLOBAL -> 3;     // 优先级最低
        default -> 99;
    };
}

// RerankPostProcessor.java:58-72 - 重排序处理器
@Override
public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                     List<SearchChannelResult> results,
                                     SearchContext context) {
    if (chunks.isEmpty()) {
        return chunks;
    }

    // 调用 Rerank 模型进行精排
    return rerankService.rerank(
            context.getMainQuestion(),
            chunks,
            context.getTopK()
    );
}
```

**优化点：**

#### 4.3 MCP 工具调用

**核心类：**
- `MCPParameterExtractor` - 参数提取
- `MCPToolRegistry` - 工具注册
- `RemoteMCPToolExecutor` - 远程执行

**代码示例：**
```java
// RetrievalEngine.java:185-196
private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
    List<MCPResponse> responses = executeMcpTools(question, mcpIntents);
    return contextFormatter.formatMcpContext(responses, mcpIntents);
}
```

**优化优点：**
- 多通道互补：意图定向精准，全局检索召回
- 后处理器链式处理，逐步精炼
- MCP 扩展：非知识问题走工具调用

---

### 5. Prompt 组装 (RAGPromptService)

**核心类：**
- `RAGPromptService` - Prompt 服务
- `PromptTemplateLoader` - 模板加载
- `PromptScene` - 场景枚举
- `ContextFormatter` - 上下文格式化

**优化点：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 场景化 | 不同场景不用 Prompt | `PromptScene` 枚举 |
| 子问题注入 | 检索子问题分别处理 | `buildStructuredMessages()` |
| 上下文压缩 | 格式化长文本 | `ContextFormatter` |
| 动态温度 | MCP 场景放宽温度 | `streamLLMResponse()` |

**代码示例：**
```java
// RAGChatServiceImpl.java:181-195
List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
        promptContext,
        history,
        rewriteResult.rewrittenQuestion(),
        rewriteResult.subQuestions()
);
ChatRequest chatRequest = ChatRequest.builder()
        .messages(messages)
        .thinking(deepThinking)
        .temperature(ctx.hasMcp() ? 0.3D : 0D)
        .topP(ctx.hasMcp() ? 0.8D : 1D)
        .build();
```

**优化优点：**
- 场景化让回答更专业
- 子问题注入让复杂问题拆解回答

---

### 6. 流式生成 (LLMService)

#### 6.1 模型路由与容错

**核心类：**
- `ModelRoutingExecutor` - 路由执行器
- `ModelHealthStore` - 健康状态存储
- `ModelSelector` - 模型选择器

**代码示例：**

> **这段代码做什么？**
> 
> 这是"模型路由与自动降级"的核心实现，保证服务高可用：
> 1. **多候选**：配置多个模型（如阿里云→SiliconFlow→Ollama），按优先级依次尝试
> 2. **健康检查**：调用前检查模型是否被熔断（已经失败太多次，拒绝调用）
> 3. **自动降级**：当前模型失败时，自动切换到下一个模型，业务层无感知
> 4. **失败标记**：每次失败都记录，失败次数达到阈值后触发熔断

```java
// ModelRoutingExecutor.java:41-78 - 模型路由与自动降级核心实现
public <C, T> T executeWithFallback(
        ModelCapability capability,
        List<ModelTarget> targets,
        Function<ModelTarget, C> clientResolver,
        ModelCaller<C, T> caller) {
    
    // 遍历模型候选列表，按优先级依次尝试
    for (ModelTarget target : targets) {
        C client = clientResolver.apply(target);
        if (client == null) {
            log.warn("client missing: provider={}, modelId={}", 
                    target.candidate().getProvider(), target.id());
            continue;
        }
        
        // 检查健康状态（是否被熔断）
        if (!healthStore.allowCall(target.id())) {
            log.info("model {} is circuit broken, skip", target.id());
            continue;
        }

        try {
            T response = caller.call(client, target);
            healthStore.markSuccess(target.id());  // 成功，标记健康
            return response;
        } catch (Exception e) {
            healthStore.markFailure(target.id());  // 失败，标记失败次数
            log.warn("model {} failed, fallback to next. error: {}", 
                    target.id(), e.getMessage());
        }
    }
    
    throw new RemoteException("All model candidates failed");
}

// ============================================================
// 【熔断器 - 防止单模型故障导致服务不可用】
// ============================================================

> **这段代码做什么？**
> 
> 这是"三态熔断器"的完整实现，防止单模型故障导致服务不可用：
> 1. **CLOSED（正常）**：允许所有请求通过，失败次数累加
> 2. **OPEN（熔断）**：失败次数达到阈值（如 5 次）后触发熔断，拒绝所有请求，等待冷却时间（如 60 秒）
> 3. **HALF_OPEN（半开）**：冷却期结束后，允许一个探测请求，如果成功则恢复 CLOSED，如果失败则重新 OPEN
> 4. **状态存储**：使用 ConcurrentHashMap 存储每个模型的状态，线程安全

// ModelHealthStore.java:39-141 - 三态熔断器实现
public boolean allowCall(String id) {
    long now = System.currentTimeMillis();
    final boolean[] allowed = {false};
    
    healthById.compute(id, (k, v) -> {
        if (v == null) {
            v = new ModelHealth();
        }
        
        if (v.state == State.OPEN) {
            if (v.openUntil > now) {
                return v;  // 还在熔断期，拒绝调用
            }
            // 冷却期结束，进入半开状态
            v.state = State.HALF_OPEN;
            v.halfOpenInFlight = true;
            allowed[0] = true;
            return v;
        }
        
        if (v.state == State.HALF_OPEN) {
            if (v.halfOpenInFlight) {
                return v;  // 已有探测请求在飞
            }
            v.halfOpenInFlight = true;
            allowed[0] = true;
            return v;
        }
        
        // CLOSED 状态，正常调用
        allowed[0] = true;
        return v;
    });
    return allowed[0];
}

public void markFailure(String id) {
    healthById.compute(id, (k, v) -> {
        if (v == null) {
            v = new ModelHealth();
        }
        
        // 半开状态探测失败，重新熔断
        if (v.state == State.HALF_OPEN) {
            v.state = State.OPEN;
            v.openUntil = now + properties.getSelection().getOpenDurationMs();
            return v;
        }
        
        // 统计失败次数，达到阈值则熔断
        v.consecutiveFailures++;
        if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
            v.state = State.OPEN;
            v.openUntil = now + properties.getSelection().getOpenDurationMs();
        }
        return v;
    });
}

public void markSuccess(String id) {
    healthById.compute(id, (k, v) -> {
        if (v == null) {
            return new ModelHealth();
        }
        // 成功，恢复正常状态
        v.state = State.CLOSED;
        v.consecutiveFailures = 0;
        v.halfOpenInFlight = false;
        return v;
    });
}

// 熔断状态机
private enum State {
    CLOSED,    // 正常：允许所有请求
    OPEN,      // 熔断：拒绝请求，等待冷却
    HALF_OPEN  // 半开：允许一个探测请求
}
```

#### 6.2 熔断降级

**核心类：**
- `ModelHealthStore` - 三态熔断器

**熔断状态机：**

```
CLOSED（正常） ──失败次数≥阈值──► OPEN（熔断）
    ▲                                │
    │                                │
    └──── 冷却期后放行探测 ──────────┘ HALF_OPEN（半开）
    ▲                                │
    │                                ▼
    └────── 探测成功 ◄────────── 探测失败
```

**优化点：**

| 优化项 | 说明 | 核心代码位置 |
|--------|------|--------------|
| 多模型候选 | 配置多个模型按优先级 | `ModelTarget` 列表 |
| 首包探测 | 切换模型时用户无感知 | `ProbeBufferingCallback` |
| 三态熔断 | CLOSED→OPEN→HALF_OPEN | `ModelHealthStore` |
| 失败标记 | 失败后自动降级 | `markFailure()` |

#### 6.3 SSE 流式输出

**核心类：**
- `StreamCallback` - 流式回调
- `SseEmitterSender` - SSE 发送器

**优化优点：**
- 首包探测确保模型切换时输出不乱
- 熔断保证单模型故障不影响服务
- 流式输出提升用户体验

---

## 三、工程级优化汇总

### 限流与并发

| 优化项 | 说明 | 核心类 |
|--------|------|--------|
| 队列式限流 | Redis ZSET + 信号量 | `ChatRateLimit` |
| 多线程池 | 8 个专用线程池 | 按场景配置 |
| TTL 透传 | 用户上下文跨线程 | `TtlExecutors` |

### 可观测性

| 优化项 | 说明 | 核心类 |
|--------|------|--------|
| 全链路追踪 | AOP 记录每个环节 | `@RagTraceNode` |
| 链路查询 | TraceID 查询历史 | `RagTraceQueryService` |

### MCP 工具生态

| 优化项 | 说明 | 核心类 |
|--------|------|--------|
| 工具注册 | 自动发现注册 | `MCPToolRegistry` |
| 参数提取 | LLM 提取参数 | `LLMMCPParameterExtractor` |
| 远程调用 | HTTP 执行 | `HttpMCPClient` |

---

## 四、核心调用关系图

```
用户请求
    │
    ▼
RAGChatServiceImpl.streamChat()
    │
    ├─► MemoryService.loadAndAppend()      // 记忆加载
    │
    ├─► QueryRewriteService.rewriteWithSplit()  // 问题改写
    │       │
    │       ├─► QueryTermMappingService.normalize()  // 术语归一化
    │       └─► LLMService.chat()  // LLM 改写
    │
    ├─► IntentResolver.resolve()           // 意图识别
    │       │
    │       └─► IntentClassifier.classifyTargets()  // 树形分类
    │
    ├─► IntentGuidanceService.detectAmbiguity()  // 歧义引导
    │
    ├─► RetrievalEngine.retrieve()        // 检索引擎
    │       │
    │       ├─► MultiChannelRetrievalEngine  // 多通道
    │       │       │
    │       │       ├─► IntentDirectedSearchChannel  // 意图定向
    │       │       └─► VectorGlobalSearchChannel    // 全局向量
    │       │
    │       ├─► DeduplicationPostProcessor // 去重
    │       ├─► RerankPostProcessor         // 重排序
    │       └─► MCP工具调用
    │
    ├─► RAGPromptService.buildStructuredMessages()  // Prompt组装
    │
    └─► LLMService.streamChat()            // 流式生成
            │
            ├─► ModelRoutingExecutor      // 模型路由
            ├─► ModelHealthStore           // 熔断
            └─► StreamCallback             // SSE输出
```

---

## 五、学习建议

1. **先跑通主流程**：从 `RAGChatServiceImpl.streamChat()` 入口开始，跟随调用链走一遍
2. **理解核心接口**：每个环节的接口（如 `SearchChannel`、`SearchResultPostProcessor`）是扩展点
3. **关注优化细节**：每个优化点都有对应的"坑"，如去重防重复、重排序提升精度、熔断保证可用
4. **动手调试**：在关键方法加断点，观察输入输出变化

---

## 六、附录：关键配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `rag.query-rewrite-enabled` | 开启 LLM 改写 | true |
| `rag.intent-min-score` | 意图最小置信度 | 0.7 |
| `rag.max-intent-count` | 最大意图数 | 3 |
| `rag.default-top-k` | 默认 TopK | 10 |
| `model.candidates` | 模型候选列表 | 按优先级 |
| `chat.rate-limit.enabled` | 开启限流 | true |
| `model.selection.failure-threshold` | 熔断失败阈值 | 5 |
| `model.selection.open-duration-ms` | 熔断持续时间 | 60000 |

---

## 七、附录：核心文件索引

| 模块 | 类名 | 路径 |
|------|------|------|
| 对话服务 | `RAGChatServiceImpl` | `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` |
| 记忆服务 | `DefaultConversationMemoryService` | `bootstrap/.../rag/core/memory/DefaultConversationMemoryService.java` |
| 问题改写 | `MultiQuestionRewriteService` | `bootstrap/.../rag/core/rewrite/MultiQuestionRewriteService.java` |
| 意图解析 | `IntentResolver` | `bootstrap/.../rag/core/intent/IntentResolver.java` |
| 意图分类 | `DefaultIntentClassifier` | `bootstrap/.../rag/core/intent/DefaultIntentClassifier.java` |
| 检索引擎 | `RetrievalEngine` | `bootstrap/.../rag/core/retrieve/RetrievalEngine.java` |
| 多通道引擎 | `MultiChannelRetrievalEngine` | `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java` |
| 意图定向检索 | `IntentDirectedSearchChannel` | `bootstrap/.../rag/core/retrieve/channel/IntentDirectedSearchChannel.java` |
| 全局检索 | `VectorGlobalSearchChannel` | `bootstrap/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java` |
| 去重处理器 | `DeduplicationPostProcessor` | `bootstrap/.../rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java` |
| 重排处理器 | `RerankPostProcessor` | `bootstrap/.../rag/core/retrieve/postprocessor/RerankPostProcessor.java` |
| 模型路由 | `ModelRoutingExecutor` | `infra-ai/.../model/ModelRoutingExecutor.java` |
| 熔断器 | `ModelHealthStore` | `infra-ai/.../model/ModelHealthStore.java` |
