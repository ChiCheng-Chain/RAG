# Ragent 企业级 RAG 系统从 0 到 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于 Ragent 开源项目源码，从零构建企业级 RAG 智能体系统，使用 PostgreSQL + pgvector 替代 Milvus 作为向量存储，使用 Spring Boot 内置事件机制替代 RocketMQ 作为消息队列。

**Architecture:** 项目采用前后端分离的单体架构，后端按职责分为 framework（基础设施层）、infra-ai（AI 基础设施层）、bootstrap（应用层）三个 Maven 模块。向量存储使用 PostgreSQL + pgvector，消息队列使用 Spring ApplicationEvent 事件驱动机制替代原始 RocketMQ。

**Tech Stack:** Java 17 + Spring Boot 3.5 + PostgreSQL 15 + pgvector + Redis + Spring Events + React 18

---

## 第一阶段：环境准备与基础设施

### Task 1: 项目骨架搭建

**Files:**
- Create: `pom.xml` - Maven 多模块项目配置
- Create: `bootstrap/pom.xml` - 应用层模块
- Create: `framework/pom.xml` - 基础设施层模块
- Create: `infra-ai/pom.xml` - AI 基础设施层模块

- [ ] **Step 1: 创建根 pom.xml**

参考现有源码中的 pom.xml 结构，创建包含以下模块的根 pom：
```xml
<modules>
    <module>bootstrap</module>
    <module>framework</module>
    <module>infra-ai</module>
</modules>
```

关键依赖版本：
- spring-boot.version: 3.5.7
- java.version: 17

- [ ] **Step 2: 创建 bootstrap 模块 pom.xml**

```xml
<artifactId>ragent-bootstrap</artifactId>
<dependencies>
    <dependency>
        <groupId>com.nageoffer.ai</groupId>
        <artifactId>ragent-framework</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>com.nageoffer.ai</groupId>
        <artifactId>ragent-infra-ai</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.redis</groupId>
        <artifactId>spring-redis-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.dev33</groupId>
        <artifactId>sa-token-spring-boot3-starter</artifactId>
    </dependency>
</dependencies>
```

- [ ] **Step 3: 创建 framework 模块 pom.xml**

```xml
<artifactId>ragent-framework</artifactId>
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>
        <artifactId>hutool-all</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba</groupId>
        <artifactId>transmittable-thread-local</artifactId>
    </dependency>
</dependencies>
```

- [ ] **Step 4: 创建 infra-ai 模块 pom.xml**

```xml
<artifactId>ragent-infra-ai</artifactId>
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
    </dependency>
</dependencies>
```

- [ ] **Step 5: Commit**

```bash
git add pom.xml bootstrap/pom.xml framework/pom.xml infra-ai/pom.xml
git commit -m "chore: 创建多模块 Maven 项目骨架"
```

---

### Task 2: 数据库环境搭建

**Files:**
- Create: `resources/database/schema_pg.sql` - PostgreSQL 表结构
- Create: `resources/database/init_data_pg.sql` - 初始化数据

- [ ] **Step 1: 创建 PostgreSQL schema 脚本**

参考现有 `resources/database/schema_pg.sql`，创建以下核心表：

```sql
-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 用户表
CREATE TABLE t_user (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    avatar VARCHAR(128),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);

-- 会话表
CREATE TABLE t_conversation (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id VARCHAR(20) NOT NULL,
    title VARCHAR(128) NOT NULL,
    last_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_conversation_user UNIQUE (conversation_id, user_id)
);

-- 消息表
CREATE TABLE t_message (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id VARCHAR(20) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

-- 知识库表
CREATE TABLE t_knowledge_base (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(64) NOT NULL,
    collection_name VARCHAR(64) NOT NULL,
    created_by VARCHAR(20) NOT NULL,
    updated_by VARCHAR(20),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_collection_name UNIQUE (collection_name)
);

-- 知识库文档表
CREATE TABLE t_knowledge_document (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    kb_id VARCHAR(20) NOT NULL,
    doc_name VARCHAR(256) NOT NULL,
    enabled SMALLINT NOT NULL DEFAULT 1,
    chunk_count INTEGER DEFAULT 0,
    file_url VARCHAR(1024) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    file_size BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    source_type VARCHAR(16),
    source_location VARCHAR(1024),
    created_by VARCHAR(20) NOT NULL,
    updated_by VARCHAR(20),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);

-- 知识库分块表
CREATE TABLE t_knowledge_chunk (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    kb_id VARCHAR(20) NOT NULL,
    doc_id VARCHAR(20) NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64),
    char_count INTEGER,
    token_count INTEGER,
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_by VARCHAR(20) NOT NULL,
    updated_by VARCHAR(20),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);

-- 向量存储表（pgvector）
CREATE TABLE t_knowledge_vector (
    id VARCHAR(20) PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1536)
);

CREATE INDEX idx_kv_metadata ON t_knowledge_vector USING gin(metadata);
CREATE INDEX idx_kv_embedding ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops);

-- 意图树节点表
CREATE TABLE t_intent_node (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    kb_id VARCHAR(20),
    intent_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    level SMALLINT NOT NULL,
    parent_code VARCHAR(64),
    description VARCHAR(512),
    examples TEXT,
    collection_name VARCHAR(128),
    top_k INTEGER,
    mcp_tool_id VARCHAR(128),
    kind SMALLINT NOT NULL DEFAULT 0,
    prompt_snippet TEXT,
    prompt_template TEXT,
    param_prompt_template TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled SMALLINT NOT NULL DEFAULT 1,
    create_by VARCHAR(20),
    update_by VARCHAR(20),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);

-- RAG 追踪表
CREATE TABLE t_rag_trace_run (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    trace_name VARCHAR(128),
    conversation_id VARCHAR(20),
    task_id VARCHAR(20),
    user_id VARCHAR(20),
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    error_message VARCHAR(1000),
    start_time TIMESTAMP(3),
    end_time TIMESTAMP(3),
    duration_ms BIGINT,
    extra_data TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_run_id UNIQUE (trace_id)
);

CREATE TABLE t_rag_trace_node (
    id VARCHAR(20) NOT NULL PRIMARY KEY,
    trace_id VARCHAR(20) NOT NULL,
    node_id VARCHAR(20) NOT NULL,
    parent_node_id VARCHAR(20),
    depth INTEGER DEFAULT 0,
    node_type VARCHAR(16),
    node_name VARCHAR(128),
    class_name VARCHAR(256),
    method_name VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    error_message VARCHAR(1000),
    start_time TIMESTAMP(3),
    end_time TIMESTAMP(3),
    duration_ms BIGINT,
    extra_data TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_run_node UNIQUE (trace_id, node_id)
);
```

- [ ] **Step 2: 创建 Docker Compose 配置**

```yaml
version: '3.8'

services:
  postgres:
    image: pgvector/pgvector:pg15
    container_name: ragent-postgres
    environment:
      POSTGRES_DB: ragent
      POSTGRES_USER: ragent
      POSTGRES_PASSWORD: ragent_password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./resources/database:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ragent"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: ragent-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  minio:
    image: minio/minio
    container_name: ragent-minio
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data
    command: server /data --console-address ":9001"

volumes:
  postgres_data:
  redis_data:
  minio_data:
```

- [ ] **Step 3: Commit**

```bash
git add resources/database/docker-compose.yml
git commit -chore: 添加数据库环境配置"
```

---

## 第二阶段：Framework 基础设施层

### Task 3: 统一响应与异常体系

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RestResponse.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/exception/BaseException.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/exception/BusinessException.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 创建统一响应类**

参考现有源码 `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RestResponse.java`：
```java
package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestResponse<T> {
    private String code;
    private String msg;
    private T data;
    private Long timestamp;

    public static <T> RestResponse<T> success(T data) {
        return new RestResponse<>("0", "success", data, System.currentTimeMillis());
    }

    public static <T> RestResponse<T> success() {
        return success(null);
    }

    public static <T> RestResponse<T> fail(String code, String msg) {
        return new RestResponse<>(code, msg, null, System.currentTimeMillis());
    }
}
```

- [ ] **Step 2: 创建异常基类**

```java
package com.nageoffer.ai.ragent.framework.exception;

import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {
    private final String code;
    private final String message;

    public BaseException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BaseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
    }
}
```

- [ ] **Step 3: 创建业务异常类**

```java
package com.nageoffer.ai.ragent.framework.exception;

public class BusinessException extends BaseException {
    public BusinessException(String code, String message) {
        super(code, message);
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
```

- [ ] **Step 4: 创建全局异常处理器**

```java
package com.nageoffer.ai.ragent.framework.exception;

import com.nageoffer.ai.ragent.framework.convention.RestResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public RestResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return RestResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public RestResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return RestResponse.fail("500", "系统内部错误");
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/
git commit -m "feat: 添加统一响应与异常体系"
```

---

### Task 4: 消息队列替换为 Spring Events

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/event/EventPublisher.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/event/ApplicationEventPublisher.java`
- Modify: `framework/pom.xml` - 移除 RocketMQ 依赖
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/event/KnowledgeDocumentChunkEvent.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/event/KnowledgeDocumentChunkListener.java`

- [ ] **Step 1: 创建事件发布接口**

```java
package com.nageoffer.ai.ragent.framework.event;

public interface EventPublisher {
    void publish(Object event);
}
```

- [ ] **Step 2: 创建 Spring Events 实现**

```java
package com.nageoffer.ai.ragent.framework.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationEventPublisher implements EventPublisher {

    private final ApplicationEventPublisher publisher;

    @Override
    public void publish(Object event) {
        log.info("发布事件: {}", event.getClass().getSimpleName());
        publisher.publishEvent(event);
    }
}
```

- [ ] **Step 3: 创建文档分块事件**

```java
package com.nageoffer.ai.ragent.knowledge.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkEvent {
    private String documentId;
    private String kbId;
    private String docName;
    private String fileUrl;
    private String fileType;
    private Integer chunkCount;
    private String status;
    private String errorMessage;
}
```

- [ ] **Step 4: 创建文档分块事件监听器**

```java
package com.nageoffer.ai.ragent.knowledge.event;

import com.nageoffer.ai.ragent.framework.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkListener {

    private final EventPublisher eventPublisher;

    @Async
    @EventListener
    public void handleKnowledgeDocumentChunkEvent(KnowledgeDocumentChunkEvent event) {
        log.info("收到文档分块事件: docId={}, status={}", event.getDocumentId(), event.getStatus());
        // 这里处理文档分块完成后的逻辑，如更新状态、发送通知等
    }
}
```

- [ ] **Step 5: 修改 bootstrap 模块中使用消息队列的代码**

将现有的 `MessageQueueProducer` 调用替换为 `EventPublisher`：

在 `KnowledgeDocumentServiceImpl.java` 中：
```java
// 原来的方式
// messageQueueProducer.send("topic", keys, bizDesc, event);

// 替换为
eventPublisher.publish(event);
```

- [ ] **Step 6: Commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/event/
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/event/
git commit -m "refactor: 使用 Spring Events 替代 RocketMQ 消息队列"
```

---

### Task 5: 分布式 ID 与上下文透传

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/id/SnowflakeIdGenerator.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/UserContext.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/UserContextInterceptor.java`

- [ ] **Step 1: 创建 Snowflake ID 生成器**

```java
package com.nageoffer.ai.ragent.framework.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {
    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    public String nextIdStr() {
        return snowflake.nextIdStr();
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
```

- [ ] **Step 2: 创建用户上下文**

```java
package com.nageoffer.ai.ragent.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import lombok.Data;

@Data
public class UserContext {
    private static final TransmittableThreadLocal<String> USER_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> USERNAME = new TransmittableThreadLocal<>();

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
    }
}
```

- [ ] **Step 3: 创建用户上下文拦截器**

```java
package com.nageoffer.ai.ragent.framework.context;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = StpUtil.getLoginIdAsString();
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/id/
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/context/
git commit -m "feat: 添加分布式 ID 与用户上下文透传"
```

---

## 第三阶段：AI 基础设施层（Infra-AI）

### Task 6: Embedding 服务实现

**Files:**
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/EmbeddingClient.java`
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/EmbeddingService.java`
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java`

- [ ] **Step 1: 创建 Embedding 客户端接口**

```java
package com.nageoffer.ai.ragent.infra.embedding;

import java.util.List;

public interface EmbeddingClient {
    List<Float> embed(String text);
    
    List<List<Float>> embedBatch(List<String> texts);
}
```

- [ ] **Step 2: 创建 Embedding 服务接口**

```java
package com.nageoffer.ai.ragent.infra.embedding;

import java.util.List;

public interface EmbeddingService {
    List<Float> embed(String text);
    
    List<List<Float>> embedBatch(List<String> texts);
}
```

- [ ] **Step 3: 创建 SiliconFlow Embedding 客户端实现**

参考现有源码 `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java`：
```java
package com.nageoffer.ai.ragent.infra.embedding;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "siliconflow")
public class SiliconFlowEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties properties;

    @Override
    public List<Float> embed(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("input", text);
        
        HttpResponse response = HttpRequest.post(properties.getBaseUrl() + "/v1/embeddings")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .body(JSON.toJSONString(body))
                .execute();
        
        JSONObject result = JSON.parseObject(response.body());
        JSONArray data = result.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("Embedding 响应为空");
        }
        
        JSONArray embedding = data.getJSONObject(0).getJSONArray("embedding");
        List<Float> resultList = new ArrayList<>();
        for (Object v : embedding) {
            resultList.add(((Number) v).floatValue());
        }
        return resultList;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }
}
```

- [ ] **Step 4: 创建 Embedding 配置属性**

```java
package com.nageoffer.ai.ragent.infra.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.embedding")
public class EmbeddingProperties {
    private String provider = "siliconflow";
    private String apiKey;
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String model = "BAAI/bge-large-zh-v1.5";
}
```

- [ ] **Step 5: Commit**

```bash
git add infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/
git commit -m "feat: 添加 Embedding 服务实现"
```

---

### Task 7: Chat 对话服务实现

**Files:**
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ChatClient.java`
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/ChatResponse.java`
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamCallback.java`
- Create: `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/StreamingChatClient.java`

- [ ] **Step 1: 创建对话客户端接口**

```java
package com.nageoffer.ai.ragent.infra.chat;

import java.util.List;
import java.util.Map;

public interface ChatClient {
    ChatResponse chat(List<Map<String, String>> messages);
    
    void streamChat(List<Map<String, String>> messages, StreamCallback callback);
}
```

- [ ] **Step 2: 创建对话响应类**

```java
package com.nageoffer.ai.ragent.infra.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String content;
    private String model;
    private Integer usage;
    private String finishReason;
}
```

- [ ] **Step 3: 创建流式回调接口**

```java
package com.nageoffer.ai.ragent.infra.chat;

public interface StreamCallback {
    void onContent(String content);
    
    void onComplete();
    
    void onError(Exception e);
}
```

- [ ] **Step 4: 创建 SiliconFlow 流式对话客户端**

参考现有源码 `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/SiliconFlowChatClient.java` 实现

- [ ] **Step 5: Commit**

```bash
git add infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/
git commit -m "feat: 添加 Chat 对话服务实现"
```

---

## 第四阶段：Bootstrap 应用层

### Task 8: 向量存储服务实现（PostgreSQL + pgvector）

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreAdmin.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreAdmin.java`

- [ ] **Step 1: 创建向量存储服务接口**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`：
```java
package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;

import java.util.List;

public interface VectorStoreService {
    void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks);
    
    void deleteDocumentVectors(String collectionName, String docId);
    
    void deleteChunkById(String collectionName, String chunkId);
    
    void updateChunk(String collectionName, String docId, VectorChunk chunk);
}
```

- [ ] **Step 2: 创建向量存储管理接口**

```java
package com.nageoffer.ai.ragent.rag.core.vector;

public interface VectorStoreAdmin {
    void createVectorSpace(VectorSpaceSpec spec);
    
    boolean vectorSpaceExists(VectorSpaceId spaceId);
    
    void deleteVectorSpace(VectorSpaceId spaceId);
}
```

- [ ] **Step 3: 创建 pgvector 实现**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorStoreService.java`：
```java
package com.nageoffer.ai.ragent.rag.core.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                chunks, chunks.size(), (ps, chunk) -> {
                    ps.setString(1, chunk.getChunkId());
                    ps.setString(2, chunk.getContent());
                    ps.setString(3, buildMetadataJson(collectionName, docId, chunk));
                    ps.setString(4, toVectorLiteral(chunk.getEmbedding()));
                });

        log.info("批量写入向量到 PostgreSQL，collectionName={}, docId={}, count={}", collectionName, docId, chunks.size());
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM t_knowledge_vector WHERE metadata->>'collection_name' = ? AND metadata->>'doc_id' = ?",
                collectionName, docId);
        log.info("删除文档向量，collectionName={}, docId={}, deleted={}", collectionName, docId, deleted);
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
                chunk.getChunkId(),
                chunk.getContent(),
                buildMetadataJson(collectionName, docId, chunk),
                toVectorLiteral(chunk.getEmbedding())
        );
    }

    private String buildMetadataJson(String collectionName, String docId, VectorChunk chunk) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (chunk.getMetadata() != null) {
            meta.putAll(chunk.getMetadata());
        }
        meta.put("collection_name", collectionName);
        meta.put("doc_id", docId);
        meta.put("chunk_index", chunk.getIndex());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException("元数据序列化失败", e);
        }
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/
git commit -m "feat: 添加 PostgreSQL + pgvector 向量存储实现"
```

---

### Task 9: 向量检索服务实现

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrieverService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrieveRequest.java`

- [ ] **Step 1: 创建检索请求类**

```java
package com.nageoffer.ai.ragent.rag.core.retrieve;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrieveRequest {
    private String query;
    private String collectionName;
    private Integer topK;
    private String filterExpr;
}
```

- [ ] **Step 2: 创建检索服务接口**

```java
package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

public interface RetrieverService {
    List<RetrievedChunk> retrieve(RetrieveRequest request);
    
    List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request);
}
```

- [ ] **Step 3: 创建 pgvector 检索实现**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java`：
```java
package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
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
                vectorLiteral, request.getCollectionName(), vectorLiteral, request.getTopK()
        );
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

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/
git commit -m "feat: 添加 PostgreSQL 向量检索服务实现"
```

---

### Task 10: RAG 核心流程实现

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RAGChatService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/rewrite/QueryRewriteService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentResolver.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`

- [ ] **Step 1: 创建 RAG 对话服务接口**

```java
package com.nageoffer.ai.ragent.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface RAGChatService {
    void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter);
    
    void stopTask(String taskId);
}
```

- [ ] **Step 2: 实现 RAG 核心七步流程**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`，实现完整流程：

```java
package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCancellationHandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // Step 1: 加载会话记忆并补入当前问题
        // Step 2: 查询改写与子问题拆分
        // Step 3: 意图识别与歧义引导
        // Step 4: 检索引擎并行执行
        // Step 5: 组装提示词上下文
        // Step 6: 路由模型并流式返回
        // Step 7: 写入会话、反馈与追踪
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
```

- [ ] **Step 3: 实现问题改写服务**

```java
package com.nageoffer.ai.ragent.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class QueryRewriteService {

    public RewriteResult rewriteWithSplit(String question, List<ChatMessage> history) {
        // 1. 补全上下文：将历史对话中的指代词、代词补全
        String rewrittenQuestion = rewriteWithHistory(question, history);
        
        // 2. 子问题拆分：将复杂问题拆分为多个简单问题
        List<String> subQuestions = splitSubQuestions(rewrittenQuestion);
        
        return RewriteResult.builder()
                .originalQuestion(question)
                .rewrittenQuestion(rewrittenQuestion)
                .subQuestions(subQuestions)
                .build();
    }

    private String rewriteWithHistory(String question, List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return question;
        }
        // 简化实现：直接返回原问题
        // 完整实现需要调用 LLM 进行上下文补全
        return question;
    }

    private List<String> splitSubQuestions(String question) {
        // 简化实现：返回原问题作为唯一子问题
        // 完整实现需要调用 LLM 进行问题拆分
        return List.of(question);
    }
}
```

- [ ] **Step 4: 实现检索引擎**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrievalEngine.java`

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/
git commit -m "feat: 实现 RAG 核心对话流程"
```

---

### Task 11: 文档入库流水线实现

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/FetcherNode.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ParserNode.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/ChunkerNode.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java`

- [ ] **Step 1: 创建节点基类**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IngestionNode.java`：
```java
package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;

public abstract class IngestionNode {
    
    public abstract String getNodeType();
    
    public abstract NodeResult execute(NodeResult input);
    
    protected void beforeExecute(NodeResult input) {
    }
    
    protected void afterExecute(NodeResult input) {
    }
}
```

- [ ] **Step 2: 实现 FetcherNode（抓取节点）**

```java
package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetcherNode extends IngestionNode {

    private final DocumentParser documentParser;

    @Override
    public String getNodeType() {
        return "FETCHER";
    }

    @Override
    public NodeResult execute(NodeResult input) {
        String sourceType = input.getMetadata("sourceType");
        String sourceLocation = input.getMetadata("sourceLocation");
        
        // 根据 sourceType 选择不同的抓取策略
        String content = fetchContent(sourceType, sourceLocation);
        
        input.setContent(content);
        input.setMetadata("contentLength", String.valueOf(content.length()));
        
        return input;
    }

    private String fetchContent(String sourceType, String sourceLocation) {
        // 实现文件抓取、URL 抓取等逻辑
        return "";
    }
}
```

- [ ] **Step 3: 实现 ParserNode（解析节点）**

使用 Apache Tika 进行文档解析

- [ ] **Step 4: 实现 ChunkerNode（分块节点）**

```java
package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.core.chunk.TextChunker;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChunkerNode extends IngestionNode {

    private final TextChunker textChunker;

    @Override
    public String getNodeType() {
        return "CHUNKER";
    }

    @Override
    public NodeResult execute(NodeResult input) {
        String content = input.getContent();
        
        // 使用语义分块
        List<String> chunks = textChunker.chunk(content);
        
        input.setChunks(chunks);
        return input;
    }
}
```

- [ ] **Step 5: 实现 IndexerNode（索引节点）**

```java
package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class IndexerNode extends IngestionNode {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    @Override
    public String getNodeType() {
        return "INDEXER";
    }

    @Override
    public NodeResult execute(NodeResult input) {
        String collectionName = input.getMetadata("collectionName");
        String docId = input.getMetadata("docId");
        List<String> chunks = input.getChunks();
        
        List<VectorChunk> vectorChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            List<Float> embedding = embeddingService.embed(chunk);
            
            VectorChunk vc = VectorChunk.builder()
                    .chunkId(docId + "_" + i)
                    .content(chunk)
                    .embedding(toArray(embedding))
                    .index(i)
                    .build();
            vectorChunks.add(vc);
        }
        
        vectorStoreService.indexDocumentChunks(collectionName, docId, vectorChunks);
        
        input.setMetadata("chunkCount", String.valueOf(chunks.size()));
        return input;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
```

- [ ] **Step 6: 创建入库引擎**

参考现有源码 `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/engine/IngestionEngine.java`

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/
git commit -m "feat: 实现文档入库流水线"
```

---

### Task 12: 管理后台 API 实现

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/RAGController.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeController.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/DashboardController.java`

- [ ] **Step 1: 创建 RAG 对话控制器**

```java
package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.RestResponse;
import com.nageoffer.ai.ragent.rag.dto.ChatRequest;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ragent")
@RequiredArgsConstructor
public class RAGController {

    private final RAGChatService ragChatService;

    @PostMapping("/chat")
    public RestResponse<Void> chat(@RequestBody ChatRequest request, SseEmitter emitter) {
        ragChatService.streamChat(
                request.getQuestion(),
                request.getConversationId(),
                request.getDeepThinking(),
                emitter
        );
        return RestResponse.success();
    }

    @DeleteMapping("/task/{taskId}")
    public RestResponse<Void> stopTask(@PathVariable String taskId) {
        ragChatService.stopTask(taskId);
        return RestResponse.success();
    }
}
```

- [ ] **Step 2: 创建知识库控制器**

```java
package com.nageoffer.ai.ragent.knowledge.controller;

import com.nageoffer.ai.ragent.framework.convention.RestResponse;
import com.nageoffer.ai.ragent.knowledge.dto.*;
import com.nageoffer.ai.ragent.knowledge.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    @PostMapping("/base")
    public RestResponse<String> createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        String id = knowledgeBaseService.create(request);
        return RestResponse.success(id);
    }

    @GetMapping("/base")
    public RestResponse<List<KnowledgeBaseVO>> listKnowledgeBases() {
        return RestResponse.success(knowledgeBaseService.listAll());
    }

    @PostMapping("/document")
    public RestResponse<String> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("kbId") String kbId) {
        String docId = knowledgeDocumentService.uploadDocument(file, kbId);
        return RestResponse.success(docId);
    }

    @GetMapping("/document/{kbId}")
    public RestResponse<List<KnowledgeDocumentVO>> listDocuments(@PathVariable String kbId) {
        return RestResponse.success(knowledgeDocumentService.listByKbId(kbId));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/
git commit -m "feat: 添加管理后台 API"
```

---

## 第五阶段：前端应用

### Task 13: React 前端搭建

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/App.tsx`
- Create: `frontend/src/pages/Chat.tsx`

- [ ] **Step 1: 创建前端项目配置**

```json
{
  "name": "ragent-frontend",
  "private": true,
  "version": "0.0.1",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "react-router-dom": "^6.20.0",
    "axios": "^1.6.0",
    "antd": "^5.11.0",
    "@ant-design/icons": "^5.2.6",
    "marked": "^11.0.0",
    "highlight.js": "^11.9.0"
  },
  "devDependencies": {
    "@types/react": "^18.2.0",
    "@types/react-dom": "^18.2.0",
    "@vitejs/plugin-react": "^4.2.0",
    "typescript": "^5.3.0",
    "vite": "^5.0.0"
  }
}
```

- [ ] **Step 2: 创建聊天页面**

参考现有前端源码实现完整的聊天界面

- [ ] **Step 3: Commit**

```bash
git add frontend/
git commit -m "feat: 添加 React 前端应用"
```

---

## 总结

本实施计划包含 13 个主要任务，涵盖以下阶段：

1. **环境准备**：项目骨架搭建、数据库环境
2. **Framework 基础设施层**：统一响应、异常体系、事件驱动、分布式 ID、上下文透传
3. **Infra-AI 基础设施层**：Embedding 服务、Chat 对话服务
4. **Bootstrap 应用层**：向量存储、向量检索、RAG 核心流程、文档入库流水线、管理后台 API
5. **前端应用**：React 聊天界面

**关键设计决策**：

- 向量存储：使用 PostgreSQL + pgvector，参考现有源码实现
- 消息队列：使用 Spring Events 替代 RocketMQ，减少外部依赖
- 分层架构：严格遵循 framework / infra-ai / bootstrap 三层分离

---

**Plan saved to:** `docs/superpowers/plans/2026-03-30-ragent-implementation.md`
