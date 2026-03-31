# 基础设施重构设计文档：Milvus 移除 + MinIO 替换 + RocketMQ 替换

## 文档概述

本文档描述将 Ragent 项目的基础设施组件进行以下三项重构的设计方案：

1. **移除 Milvus**：完全删除 Milvus SDK 依赖及相关代码，保留已实现的 pgvector 方案
2. **RustFS → MinIO**：将对象存储配置从 RustFS 切换到 MinIO（代码逻辑不变，仅配置层变更）
3. **RocketMQ → Spring Application Events**：用 Spring 内置事件机制替换 RocketMQ，消除中间件依赖

**执行策略**：三项重构在同一次提交中完成（策略 A），三个方向相互独立，一次性做完避免中间状态。

**前提条件**：PostgreSQL（含 pgvector 扩展）、Redis、MinIO 均已本机安装，无需 Docker。

---

## 背景与现状

| 组件 | 当前状态 | 重构目标 |
|------|----------|----------|
| 数据库 | 已使用 PostgreSQL，非 MySQL | 无需迁移 |
| 向量数据库 | pgvector 代码已实现，`rag.vector.type=pg`，但 Milvus SDK 依赖和代码仍存在 | 删除全部 Milvus 相关代码和依赖 |
| 对象存储 | RustFS（S3 兼容），底层 AWS SDK v2 | 改配置指向 MinIO，改类名，代码不动 |
| 消息队列 | RocketMQ，用于文档分块和反馈两个异步任务 | 替换为 Spring Application Events |

---

## 设计一：移除 Milvus

### 删除文件

以下 4 个 Java 文件完整删除：

- `bootstrap/.../rag/config/MilvusConfig.java`
- `bootstrap/.../rag/core/vector/MilvusVectorStoreService.java`
- `bootstrap/.../rag/core/vector/MilvusVectorStoreAdmin.java`
- `bootstrap/.../rag/core/retrieve/MilvusRetrieverService.java`

### pom.xml 变更

**�� `pom.xml`**：
- 删除 `<milvus-sdk.version>2.6.6</milvus-sdk.version>` 属性
- 删除 `dependencyManagement` 中的 `io.milvus:milvus-sdk-java` 依赖声明

**`bootstrap/pom.xml`**：
- 删除 `io.milvus:milvus-sdk-java` 依赖

### application.yaml 变更

- 删除 `milvus:` 配置块（`uri`、`token` 等）
- `rag.vector.type` 保留 `pg`（已是当前值，无需改动）

---

## 设计二：RustFS → MinIO

### 配置类变更

**重命名**：`RestFSS3Config.java` → `MinIOConfig.java`

类内 `@Value` 注入变更：

| 旧配置键 | 新配置键 |
|---------|---------|
| `${rustfs.url}` | `${minio.url}` |
| `${rustfs.access-key-id}` | `${minio.access-key-id}` |
| `${rustfs.secret-access-key}` | `${minio.secret-access-key}` |

`S3Client` 和 `S3Presigner` 的构建逻辑**完全不变**，MinIO 完全兼容 AWS S3 API。

### application.yaml 变更

删除：
```yaml
rustfs:
  url: http://localhost:9000
  access-key-id: rustfsadmin
  secret-access-key: rustfsadmin
```

新增：
```yaml
minio:
  url: http://localhost:9000
  access-key-id: minioadmin
  secret-access-key: minioadmin
```

> MinIO 默认端口 9000，默认凭证 minioadmin/minioadmin。实际部署时按本机 MinIO 配置调整。

---

## 设计三：RocketMQ → Spring Application Events

### 整体替换映射

| RocketMQ 概念 | Spring Events 替代 |
|---|---|
| `MessageQueueProducer.send()` | `EventPublisher.publishEvent()` → 内部调用 `ApplicationEventPublisher` |
| `MessageQueueProducer.sendInTransaction()` | 本地事务在 `@Transactional` 方法中执行 + `@TransactionalEventListener(phase = AFTER_COMMIT)` 接收 |
| `@RocketMQMessageListener` 消费者 | `@EventListener @Async` 监听器 |

### framework 层变更

**删除 4 个文件**：
- `framework/.../mq/producer/MessageQueueProducer.java`（接口，含 RocketMQ `SendResult` 返回类型）
- `framework/.../mq/producer/RocketMQProducerAdapter.java`
- `framework/.../mq/producer/DelegatingTransactionListener.java`
- `framework/.../config/RocketMQAutoConfiguration.java`

**新增 2 个文件**：

`EventPublisher.java`（接口）：
```java
public interface EventPublisher {
    void publishEvent(Object event);
}
```

`SpringEventPublisher.java`（实现）：
```java
@Component
@RequiredArgsConstructor
public class SpringEventPublisher implements EventPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishEvent(Object event) {
        applicationEventPublisher.publishEvent(event);
    }
}
```

**`framework/pom.xml`**：删除 `rocketmq-spring-boot-starter` 依赖

### bootstrap 层变更

**删除 2 个 RocketMQ 消费者**：
- `bootstrap/.../knowledge/mq/KnowledgeDocumentChunkConsumer.java`
- `bootstrap/.../rag/mq/MessageFeedbackConsumer.java`

**新增 2 个 Spring 事件监听器**：

`KnowledgeDocumentChunkEventListener.java`：
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkEventListener {
    private final KnowledgeDocumentService documentService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(KnowledgeDocumentChunkEvent event) {
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId());
        } finally {
            UserContext.clear();
        }
    }
}
```

`MessageFeedbackEventListener.java`：
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageFeedbackEventListener {

    @Async
    @EventListener
    public void onEvent(MessageFeedbackEvent event) {
        // 原 MessageFeedbackConsumer 中的处理逻辑迁移至此
    }
}
```

**修改事件发送方**（将所有调用 `MessageQueueProducer` 的地方改为 `EventPublisher`）：
- `sendInTransaction()` 调用方：本地 DB 操作保留在 `@Transactional` 方法中，将 `EventPublisher.publishEvent(event)` 放在方法内即可；监听器使用 `AFTER_COMMIT` 保证事务提交后才触发，语义等价于 RocketMQ 事务消息。
- `send()` 调用方：直接替换为 `eventPublisher.publishEvent(event)`。

### pom.xml 变更

**根 `pom.xml`**：
- 删除 `<rocketmq-spring-boot-starter.version>` 属性
- 删除 `dependencyManagement` 中的 `rocketmq-spring-boot-starter` 声明

### application.yaml 变更

删除：
```yaml
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: ragent-producer${unique-name:}_pg
    send-message-timeout: 2000
```

---

## 变更文件汇总

### 删除文件（9 个）

| 文件 | 所在模块 | 原因 |
|------|---------|------|
| `MilvusConfig.java` | bootstrap | Milvus 移除 |
| `MilvusVectorStoreService.java` | bootstrap | Milvus 移除 |
| `MilvusVectorStoreAdmin.java` | bootstrap | Milvus 移除 |
| `MilvusRetrieverService.java` | bootstrap | Milvus 移除 |
| `KnowledgeDocumentChunkConsumer.java` | bootstrap | RocketMQ → Events |
| `MessageFeedbackConsumer.java` | bootstrap | RocketMQ → Events |
| `MessageQueueProducer.java` | framework | RocketMQ → Events |
| `RocketMQProducerAdapter.java` | framework | RocketMQ → Events |
| `DelegatingTransactionListener.java` | framework | RocketMQ → Events |
| `RocketMQAutoConfiguration.java` | framework | RocketMQ → Events |

### 新增文件（4 个）

| 文件 | 所在模块 | 说明 |
|------|---------|------|
| `EventPublisher.java` | framework | 替代 MessageQueueProducer 的接口 |
| `SpringEventPublisher.java` | framework | EventPublisher 的 Spring 实现 |
| `KnowledgeDocumentChunkEventListener.java` | bootstrap | 替代 KnowledgeDocumentChunkConsumer |
| `MessageFeedbackEventListener.java` | bootstrap | 替代 MessageFeedbackConsumer |

### 重命名文件（1 个）

| 旧文件名 | 新文件名 | 说明 |
|---------|---------|------|
| `RestFSS3Config.java` | `MinIOConfig.java` | RustFS → MinIO |

### 修改文件

- 根 `pom.xml`：删除 Milvus、RocketMQ 依赖声明和版本属性
- `bootstrap/pom.xml`：删除 Milvus 依赖
- `framework/pom.xml`：删除 RocketMQ 依赖
- `bootstrap/.../application.yaml`：删除 milvus/rocketmq 配置块，rustfs 改为 minio
- 调用 `MessageQueueProducer` 的业务服务类（根据 grep 结果确定具体文件）

---

## 关键设计决策

### 事务消息语义等价性

RocketMQ 事务消息保证：数据库写入成功后，消息才会被消费者处理。

Spring Events 的等价实现：
- 发送方在 `@Transactional` 方法内调用 `eventPublisher.publishEvent(event)`
- 监听器使用 `@TransactionalEventListener(phase = AFTER_COMMIT)`
- Spring 保证：只有当前事务成功提交后，监听器才会被触发

两者语义完全等价，且 Spring Events 实现更简洁，无需 Half Message 和回查机制。

### 重启丢失任务的接受

Spring Application Events 是进程内事件，应用重启时正在处理的任务会丢失。这是选择方案 A 时已知并接受的权衡。文档入库任务在数据库中有状态记录（`IngestionTask` 表），若任务重启后状态为 RUNNING，可通过现有的定时扫描机制（`scan-delay-ms`）重新触发。

---

## 文档版本

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-03-31 | 初始版本 |
