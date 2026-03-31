# 基础设施重构实施计划（Milvus 移除 + MinIO + Spring Events）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 Milvus SDK、将 RustFS 配置切换为 MinIO、用 Spring Application Events 替换 RocketMQ，实现完全本机部署零中间件依赖。

**Architecture:** 三个独立重构方向在同一次变更中完成。Milvus 直接删除（pgvector 已实现）；MinIO 仅改配置键名（AWS S3 SDK 代码不变）；RocketMQ 替换为 `SpringEventPublisher` + `@TransactionalEventListener`，事务语义等价。

**Tech Stack:** Spring Boot 3.5.7、Spring `ApplicationEventPublisher`、`@TransactionalEventListener`、AWS S3 SDK v2（指向 MinIO）、PostgreSQL + pgvector。

---

## 文件变更总览

### 删除（12 个）
| 文件路径 | 原因 |
|---------|------|
| `bootstrap/.../rag/config/MilvusConfig.java` | Milvus 移除 |
| `bootstrap/.../rag/core/vector/MilvusVectorStoreService.java` | Milvus 移除 |
| `bootstrap/.../rag/core/vector/MilvusVectorStoreAdmin.java` | Milvus 移除 |
| `bootstrap/.../rag/core/retrieve/MilvusRetrieverService.java` | Milvus 移除 |
| `framework/.../framework/mq/MessageWrapper.java` | RocketMQ 移除 |
| `framework/.../framework/mq/producer/MessageQueueProducer.java` | RocketMQ 移除 |
| `framework/.../framework/mq/producer/RocketMQProducerAdapter.java` | RocketMQ 移除 |
| `framework/.../framework/mq/producer/DelegatingTransactionListener.java` | RocketMQ 移除 |
| `framework/.../framework/mq/producer/TransactionChecker.java` | RocketMQ 移除 |
| `framework/.../framework/config/RocketMQAutoConfiguration.java` | RocketMQ 移除 |
| `bootstrap/.../knowledge/mq/KnowledgeDocumentChunkConsumer.java` | RocketMQ 移除 |
| `bootstrap/.../rag/mq/MessageFeedbackConsumer.java` | RocketMQ 移除 |

### 新增（4 个）
| 文件路径 | 说明 |
|---------|------|
| `framework/.../framework/mq/producer/EventPublisher.java` | Spring Events 接口 |
| `framework/.../framework/mq/producer/SpringEventPublisher.java` | EventPublisher 实现 |
| `bootstrap/.../knowledge/event/KnowledgeDocumentChunkEventListener.java` | 替代 KnowledgeDocumentChunkConsumer |
| `bootstrap/.../rag/event/MessageFeedbackEventListener.java` | 替代 MessageFeedbackConsumer |

### 重命名（1 个）
| 旧路径 | 新路径 |
|--------|--------|
| `bootstrap/.../rag/config/RestFSS3Config.java` | `bootstrap/.../rag/config/MinIOConfig.java` |

### 修改（6 个）
| 文件 | 变更内容 |
|------|---------|
| `pom.xml`（根） | 删除 milvus-sdk.version、rocketmq 版本属性和 dependencyManagement 条目 |
| `bootstrap/pom.xml` | 删除 io.milvus:milvus-sdk-java 依赖 |
| `framework/pom.xml` | 删除 rocketmq-spring-boot-starter 依赖 |
| `bootstrap/.../resources/application.yaml` | 删除 milvus/rocketmq 块，rustfs 改为 minio |
| `bootstrap/.../knowledge/service/impl/KnowledgeDocumentServiceImpl.java` | 替换 MessageQueueProducer → EventPublisher，重构 startChunk() |
| `bootstrap/.../rag/service/impl/MessageFeedbackServiceImpl.java` | 替换 MessageQueueProducer → EventPublisher，删 feedbackTopic 字段 |

---

## Task 1：移除 Milvus（文件 + 依赖 + 配置）

**Files:**
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/MilvusConfig.java`
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/MilvusVectorStoreService.java`
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/MilvusVectorStoreAdmin.java`
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java`
- Modify: `pom.xml`
- Modify: `bootstrap/pom.xml`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 删除 4 个 Milvus Java 文件**

```bash
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/MilvusConfig.java
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/MilvusVectorStoreService.java
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/MilvusVectorStoreAdmin.java
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java
```

- [ ] **Step 2: 修改根 pom.xml — 删除 Milvus 版本属性和 dependencyManagement 条目**

在 `pom.xml` `<properties>` 块中，删除这一行：
```xml
<milvus-sdk.version>2.6.6</milvus-sdk.version>
```

在 `pom.xml` `<dependencyManagement><dependencies>` 块中，删除：
```xml
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
    <version>${milvus-sdk.version}</version>
</dependency>
```

- [ ] **Step 3: 修改 bootstrap/pom.xml — 删除 Milvus 依赖**

在 `bootstrap/pom.xml` `<dependencies>` 块中，删除：
```xml
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
</dependency>
```

- [ ] **Step 4: 修改 application.yaml — 删除 milvus 配置块**

在 `bootstrap/src/main/resources/application.yaml` 中，删除：
```yaml
milvus:
  uri: http://localhost:19530
```

- [ ] **Step 5: 验证编译**

```bash
mvn compile -pl bootstrap -am -q
```

预期：编译成功，无 `io.milvus` 相关错误。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor: 移除 Milvus SDK 依赖及相关代码"
```

---

## Task 2：RustFS 配置切换为 MinIO

**Files:**
- Rename: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RestFSS3Config.java` → `MinIOConfig.java`
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 重命名文件并更新类名和 @Value 注入**

将 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RestFSS3Config.java` 内容替换为（保留 license header 不变）：

```java
package com.nageoffer.ai.ragent.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * MinIO S3 客户端配置类
 */
@Configuration
public class MinIOConfig {

    @Bean
    public S3Client s3Client(@Value("${minio.url}") String minioUrl,
                             @Value("${minio.access-key-id}") String accessKeyId,
                             @Value("${minio.secret-access-key}") String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(minioUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                )
                .forcePathStyle(true)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${minio.url}") String minioUrl,
                                   @Value("${minio.access-key-id}") String accessKeyId,
                                   @Value("${minio.secret-access-key}") String secretAccessKey) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(minioUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                )
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
```

- [ ] **Step 2: 删除旧文件，将新文件保存为 MinIOConfig.java**

```bash
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RestFSS3Config.java
```

将上一步内容写入：
`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/MinIOConfig.java`

- [ ] **Step 3: 修改 application.yaml — rustfs 改为 minio**

删除：
```yaml
rustfs:
  url: http://localhost:9000
  access-key-id: rustfsadmin
  secret-access-key: rustfsadmin
```

新增（按本机 MinIO 实际配置调整凭证）：
```yaml
minio:
  url: http://localhost:9000
  access-key-id: minioadmin
  secret-access-key: minioadmin
```

- [ ] **Step 4: 验证编译**

```bash
mvn compile -pl bootstrap -am -q
```

预期：编译成功，无 `rustfs` 相关配置绑定错误。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor: RustFS 配置切换为 MinIO"
```

---

## Task 3：删除 framework 层 RocketMQ 代码和依赖

**Files:**
- Delete: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/MessageWrapper.java`
- Delete: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/MessageQueueProducer.java`
- Delete: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/RocketMQProducerAdapter.java`
- Delete: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/DelegatingTransactionListener.java`
- Delete: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/TransactionChecker.java`
- Delete: `framework/src/main/java/com/nageoffer/ai/ragent/framework/config/RocketMQAutoConfiguration.java`
- Modify: `pom.xml`（根）
- Modify: `framework/pom.xml`

> 注意：此步骤删除后 bootstrap 中引用 `MessageQueueProducer` 的类会编译失败，这是正常的——Task 5、6 会修复它们。暂时跳过编译验证。

- [ ] **Step 1: 删除 6 个 framework 层 RocketMQ 文件**

```bash
rm framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/MessageWrapper.java
rm framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/MessageQueueProducer.java
rm framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/RocketMQProducerAdapter.java
rm framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/DelegatingTransactionListener.java
rm framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/TransactionChecker.java
rm framework/src/main/java/com/nageoffer/ai/ragent/framework/config/RocketMQAutoConfiguration.java
```

- [ ] **Step 2: 修改根 pom.xml — 删除 RocketMQ 版本属性和 dependencyManagement 条目**

在 `pom.xml` `<properties>` 块中，删除：
```xml
<rocketmq-spring-boot-starter.version>2.3.5</rocketmq-spring-boot-starter.version>
```

在 `pom.xml` `<dependencyManagement><dependencies>` 块中，删除：
```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>${rocketmq-spring-boot-starter.version}</version>
</dependency>
```

- [ ] **Step 3: 修改 framework/pom.xml — 删除 RocketMQ 依赖**

在 `framework/pom.xml` `<dependencies>` 块中，删除：
```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
</dependency>
```

- [ ] **Step 4: 修改 application.yaml — 删除 rocketmq 配置块**

在 `bootstrap/src/main/resources/application.yaml` 中，删除：
```yaml
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: ragent-producer${unique-name:}_pg
    send-message-timeout: 2000
```

- [ ] **Step 5: 仅验证 framework 模块编译**

```bash
mvn compile -pl framework -q
```

预期：framework 模块编译成功（bootstrap 此时仍有编译错误，属正常）。

---

## Task 4：新增 EventPublisher（framework 层）

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/EventPublisher.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/SpringEventPublisher.java`

- [ ] **Step 1: 创建 EventPublisher 接口**

创建文件 `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/EventPublisher.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.mq.producer;

/**
 * 事件发布接口，替代 RocketMQ 消息队列生产者
 */
public interface EventPublisher {

    /**
     * 发布应用事件
     *
     * @param event 事件对象
     */
    void publishEvent(Object event);
}
```

- [ ] **Step 2: 创建 SpringEventPublisher 实现**

创建文件 `framework/src/main/java/com/nageoffer/ai/ragent/framework/mq/producer/SpringEventPublisher.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.mq.producer;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring ApplicationEventPublisher 的事件发布实现
 */
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

- [ ] **Step 3: 验证 framework 模块编译**

```bash
mvn compile -pl framework -q
```

预期：编译成功。

---

## Task 5：重构 KnowledgeDocumentServiceImpl（发送方）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

当前 `startChunk()` 调用 `messageQueueProducer.sendInTransaction()`，lambda 内执行本地事务。
重构后：`startChunk()` 加 `@Transactional`，lambda 内逻辑移入方法体，最后发布事件。

- [ ] **Step 1: 修改 import 区域**

在 `KnowledgeDocumentServiceImpl.java` 中：

删除 import：
```java
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
```

新增 import：
```java
import com.nageoffer.ai.ragent.framework.mq.producer.EventPublisher;
import org.springframework.transaction.annotation.Transactional;
```

- [ ] **Step 2: 替换字段声明**

将：
```java
private final MessageQueueProducer messageQueueProducer;
```

替换为：
```java
private final EventPublisher eventPublisher;
```

- [ ] **Step 3: 删除 chunkTopic 字段**

删除：
```java
@Value("knowledge-document-chunk_topic${unique-name:}")
private String chunkTopic;
```

- [ ] **Step 4: 重构 startChunk() 方法**

将原 `startChunk()` 方法：
```java
@Override
public void startChunk(String docId) {
    KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
            .docId(docId)
            .operator(UserContext.getUsername())
            .build();

    messageQueueProducer.sendInTransaction(
            chunkTopic,
            docId,
            "文档分块",
            event,
            arg -> {
                int updated = documentMapper.update(
                        new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                                .set(KnowledgeDocumentDO::getUpdatedBy, event.getOperator())
                                .eq(KnowledgeDocumentDO::getId, docId)
                                .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                );
                if (updated == 0) {
                    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                    Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
                    throw new ClientException("文档分块操作正在进行中，请稍后再试");
                }
                KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                event.setKbId(documentDO.getKbId());
                scheduleService.upsertSchedule(documentDO);
            }
    );
}
```

替换为：
```java
@Override
@Transactional
public void startChunk(String docId) {
    KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
            .docId(docId)
            .operator(UserContext.getUsername())
            .build();

    int updated = documentMapper.update(
            new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                    .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                    .set(KnowledgeDocumentDO::getUpdatedBy, event.getOperator())
                    .eq(KnowledgeDocumentDO::getId, docId)
                    .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
    );
    if (updated == 0) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        throw new ClientException("文档分块操作正在进行中，请稍后再试");
    }
    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
    event.setKbId(documentDO.getKbId());
    scheduleService.upsertSchedule(documentDO);

    eventPublisher.publishEvent(event);
}
```

- [ ] **Step 5: 删除 PlatformTransactionManager 字段（如仅 startChunk 使用）**

检查 `KnowledgeDocumentServiceImpl` 中 `transactionManager` 是否还有其他用途：
```bash
grep -n "transactionManager" bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
```

若只有字段声明和 `startChunk` 相关引用（现在已移除），则删除：
```java
private final PlatformTransactionManager transactionManager;
```
以及对应 import `org.springframework.transaction.PlatformTransactionManager`。

- [ ] **Step 6: 验证 bootstrap 模块编译（仍会有 MessageFeedbackServiceImpl 错误，属正常）**

```bash
mvn compile -pl bootstrap -am 2>&1 | grep -E "ERROR|error:" | grep -v "MessageFeedbackServiceImpl"
```

预期：只有 `MessageFeedbackServiceImpl` 相关错误，`KnowledgeDocumentServiceImpl` 无错误。

---

## Task 6：重构 MessageFeedbackServiceImpl（发送方）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/MessageFeedbackServiceImpl.java`

- [ ] **Step 1: 修改 import 区域**

删除 import：
```java
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
```

新增 import：
```java
import com.nageoffer.ai.ragent.framework.mq.producer.EventPublisher;
```

- [ ] **Step 2: 替换字段声明，删除 feedbackTopic 字段**

将：
```java
private final MessageQueueProducer messageQueueProducer;

@Value("message-feedback_topic${unique-name:}")
private String feedbackTopic;
```

替换为：
```java
private final EventPublisher eventPublisher;
```

- [ ] **Step 3: 重构 submitFeedbackAsync() 方法**

将：
```java
@Override
public void submitFeedbackAsync(String messageId, MessageFeedbackRequest request) {
    String userId = UserContext.getUserId();
    Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
    Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
    Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));
    Integer vote = request.getVote();
    Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
    Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

    MessageFeedbackEvent event = MessageFeedbackEvent.builder()
            .messageId(messageId)
            .userId(userId)
            .vote(vote)
            .reason(request.getReason())
            .comment(request.getComment())
            .submitTime(System.currentTimeMillis())
            .build();
    messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "消息反馈", event);
}
```

替换为：
```java
@Override
public void submitFeedbackAsync(String messageId, MessageFeedbackRequest request) {
    String userId = UserContext.getUserId();
    Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
    Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
    Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));
    Integer vote = request.getVote();
    Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
    Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

    MessageFeedbackEvent event = MessageFeedbackEvent.builder()
            .messageId(messageId)
            .userId(userId)
            .vote(vote)
            .reason(request.getReason())
            .comment(request.getComment())
            .submitTime(System.currentTimeMillis())
            .build();
    eventPublisher.publishEvent(event);
}
```

- [ ] **Step 4: 验证 bootstrap 模块整体编译**

```bash
mvn compile -pl bootstrap -am -q
```

预期：编译成功，无任何错误。

---

## Task 7：新增事件监听器，删除旧 RocketMQ 消费者

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/event/KnowledgeDocumentChunkEventListener.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/event/MessageFeedbackEventListener.java`
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java`
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/mq/MessageFeedbackConsumer.java`

- [ ] **Step 1: 创建 KnowledgeDocumentChunkEventListener**

创建目录（如不存在）：`bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/event/`

创建文件 `KnowledgeDocumentChunkEventListener.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.event;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档分块事件监听器，替代原 RocketMQ 消费者
 * 使用 AFTER_COMMIT 保证事务提交后才执行，与 RocketMQ 事务消息语义等价
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkEventListener {

    private final KnowledgeDocumentService documentService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(KnowledgeDocumentChunkEvent event) {
        log.info("[事件监听器] 开始执行文档分块任务，docId={}", event.getDocId());
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId());
        } finally {
            UserContext.clear();
        }
    }
}
```

- [ ] **Step 2: 创建 MessageFeedbackEventListener**

创建目录（如不存在）：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/event/`

创建文件 `MessageFeedbackEventListener.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.event;

import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 消息反馈事件监听器，替代原 RocketMQ 消费者
 * 异步持久化点赞/点踩事件到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageFeedbackEventListener {

    private final MessageFeedbackService feedbackService;

    @Async
    @EventListener
    public void onEvent(MessageFeedbackEvent event) {
        log.info("[事件监听器] 开始处理点赞/点踩事件，messageId: {}, userId: {}, vote: {}",
                event.getMessageId(), event.getUserId(), event.getVote());
        feedbackService.submitFeedbackByEvent(event);
    }
}
```

- [ ] **Step 3: 删除旧的 RocketMQ 消费者**

```bash
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java
rm bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/mq/MessageFeedbackConsumer.java
```

- [ ] **Step 4: 确保 @EnableAsync 已在启动类上配置**

检查 `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java` 是否有 `@EnableAsync`：

```bash
grep -n "EnableAsync" bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java
```

若未找到，在 `RagentApplication.java` 的 `@SpringBootApplication` 下方添加：
```java
@EnableAsync
```
并新增 import：
```java
import org.springframework.scheduling.annotation.EnableAsync;
```

- [ ] **Step 5: 最终全量编译验证**

```bash
mvn compile -pl bootstrap -am -q
```

预期：编译成功，无任何错误。

- [ ] **Step 6: 提交全部 RocketMQ → Spring Events 变更**

```bash
git add -A
git commit -m "refactor: 用 Spring Application Events 替换 RocketMQ"
```

---

## Task 8：整体验证

- [ ] **Step 1: 完整构建（含测试）**

```bash
mvn clean package -DskipTests -q
```

预期：`bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar` 生成成功。

- [ ] **Step 2: 验证没有残留 RocketMQ、Milvus、RustFS 引用**

```bash
grep -r "rocketmq\|milvus\|rustfs" --include="*.java" bootstrap/src/ framework/src/
```

预期：无任何输出。

```bash
grep -r "rocketmq\|milvus\|rustfs" --include="*.yaml" --include="*.yml" bootstrap/src/
```

预期：无任何输出。

- [ ] **Step 3: 验证应用能正常启动**

确保本机 PostgreSQL、Redis、MinIO 均已启动，然后：

```bash
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
```

预期日志中出现 `Started RagentApplication` 且无连接失败错误。

- [ ] **Step 4: 最终提交（如 Task 7 未单独提交）**

```bash
git add -A
git commit -m "refactor: 基础设施重构完成 - 移除 Milvus/RocketMQ，切换 MinIO"
```
