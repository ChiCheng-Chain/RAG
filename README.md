# Ragent AI 企业级 RAG 智能体平台

## 项目简介

Ragent 是一个模仿企业级生产环境的 RAG 智能体平台。项目模仿企业级工程标准构建。

### 核心特性

- **多路检索引擎**：意图定向检索 + 全局向量检索并行执行，结果经去重、重排序后处理
- **意图识别体系**：树形多级意图分类，置信度不足时主动引导澄清
- **问题重写与拆分**：多轮对话自动补全上下文，复杂问题拆分为子问题分别检索
- **会话记忆管理**：保留近 N 轮对话，超限自动摘要压缩
- **模型路由与容错**：多模型优先级调度、首包探测、健康检查、自动降级
- **文档入库 ETL**：节点编排 Pipeline，从解析到向量化全流程自动化
- **全链路追踪**：每个环节均有 Trace 记录，排查与调优有据可依
- **完整管理后台**：React 管理界面，覆盖知识库管理、意图树编辑、链路追踪等

## 技术架构

### 技术栈

| 层面 | 技术选型 |
|------|----------|
| 后端框架 | Java 17、Spring Boot 3.5、MyBatis Plus |
| 前端框架 | React 18、Vite、TypeScript、Tailwind CSS |
| 关系数据库 | PostgreSQL 16+ |
| 向量数据库 | Milvus 2.6 |
| 缓存/限流 | Redis + Redisson |
| 对象存储 | MinIO (S3 兼容) |
| 文档解析 | Apache Tika 3.2 |
| 模型供应商 | DeepSeek (SiliconFlow)、Qwen (百炼) |
| 认证鉴权 | Sa-Token |

### 模块分层

```
bootstrap/          # 业务启动层 - 面向业务的代码
├── admin/          # 管理后台相关业务
├── knowledge/      # 知识库管理业务
└── rag/            # RAG 对话核心业务

infra-ai/           # AI 基础设施层 - 模型能力抽象
├── chat/           # 对话模型接入
├── embedding/      # 向量化模型接入
└── rerank/         # 重排序模型接入

framework/          # 框架基础层 - 通用能力
├── convention/     # 统一规范（Result、异常、ID生成）
├── idempotent/     # 幂等控制
├── context/        # 用户上下文透传
├── web/            # Web 层通用处理
└── ratelimit/      # 限流实现
```

## 项目规模

- **后端 Java 代码**：约 40,000+ 行，覆盖 400+ 个源文件
- **前端 TypeScript/React 代码**：约 18,000 行
- **数据库表**：20+ 张业务表
- **前端页面**：22+ 个页面/组件

## 本地快速启动

### 前置要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- PostgreSQL 16+
- Milvus 2.6+（可选，默认使用内存向量）
- Redis 6+
- MinIO（可选）

### 启动步骤

#### 1. 克隆项目

```bash
git clone https://github.com/nageoffer/ragent.git
cd ragent
```

#### 2. 初始化数据库

```bash
# 创建数据库
createdb ragent

# 导入表结构（启动时自动执行或手动执行）
psql -U postgres -d ragent -f bootstrap/src/main/resources/schema_pg.sql
```

#### 3. 配置修改（可选）

配置文件位于 `bootstrap/src/main/resources/application.yaml`，主要配置项：

```yaml
# 数据库连接
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/ragent
    username: postgres
    password: 123456

# Redis 配置
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379

# 模型 API Key（SiliconFlow）
ai:
  providers:
    siliconflow:
      api-key: your-api-key-here
```

#### 4. 启动后端

```bash
cd bootstrap
mvn clean install -DskipTests
mvn spring-boot:run
```

后端默认端口：`9090`

#### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认端口：`5173`

#### 6. 访问系统

- 前端页面：http://localhost:5173
- 管理后台：http://localhost:5173/admin
- 默认管理员账号：`admin` / `admin`

### Docker 部署（推荐）

项目提供 Docker Compose 一键部署：

```bash
# 方式一：轻量级部署（使用内存向量，无需 Milvus）
cd resources/docker/lightweight
docker-compose up -d

# 方式二：完整部署（包含 Milvus）
cd resources/docker/full
docker-compose up -d
```

访问 http://localhost:5173 即可开始使用。

## 目录结构

```
ragent/
├── bootstrap/                 # 业务启动模块
│   └── src/main/java/.../     # Controller、Service 实现
├── framework/                 # 框架基础模块
│   ├── convention/            # 统一响应、异常、ID生成
│   ├── context/               # 用户上下文、Trace上下文
│   ├── idempotent/            # 幂等控制
│   ├── ratelimit/            # 限流实现
│   └── web/                  # 全局异常处理、参数解析
├── infra-ai/                  # AI 基础设施模块
│   ├── chat/                 # 对话模型抽象与实现
│   ├── embedding/            # 向量化模型抽象与实现
│   └── rerank/               # 重排序模型抽象与实现
├── resources/                # 资源配置
│   └── docker/               # Docker 部署文件
├── frontend/                 # 前端应用
│   └── src/
│       ├── pages/            # 页面组件
│       ├── components/       # 通用组件
│       ├── services/         # API 调用
│       └── stores/           # 状态管理
└── README.md
```

## 常见问题

### Q1: 如何添加新的模型供应商？

在 `infra-ai` 模块中实现 `ChatClient` 接口，然后在 `application.yaml` 的 `ai.chat.candidates` 中添加配置即可。

### Q2: 如何扩展检索通道？

实现 `SearchChannel` 接口并注册为 Spring Bean，系统会自动加载。

### Q3: 文档入库支持哪些格式？

支持 PDF、Word、PPT、TXT、Markdown、HTML 等常见文档格式，由 Apache Tika 统一解析。

### Q4: 是否支持 Docker 部署？

是的，项目提供完整的 Docker Compose 配置，支持一键部署。

## 后续规划

- [ ] MCP 协议完整支持
- [ ] 多租户隔离
- [ ] 更多文档解析插件
- [ ] 效果评估面板
- [ ] WebSearch 集成

## 交流与支持

- 项目 GitHub：https://github.com/nageoffer/ragent
- 如有问题请提交 Issue

## License

Apache License 2.0
