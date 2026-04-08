# 场景题：AI 项目整体架构是怎样的？

## 一、核心结论

**AI 项目（尤其是 LLM 应用）的整体架构可分为 6 层：**

```
用户层 → 接入层 → 应用层 → 模型层 → 数据层 → 基础设施层
```

**核心设计原则：**
- **分层解耦**：各层独立演进，便于替换模型/向量库
- **可观测性**：全链路日志、Trace、Token 计数
- **安全合规**：输入过滤、输出审核、数据脱敏

---

## 二、AI 项目整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              AI 项目整体架构                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                         用户层 User Layer                        │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐             │   │
│  │  │ Web App │  │ Mobile  │  │  钉钉    │  │  微信   │             │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                       接入层 Gateway Layer                       │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │   │
│  │  │ API Gateway │  │  负载均衡     │  │  鉴权认证    │               │   │
│  │  │   Kong/     │  │   Nginx/    │  │   JWT/      │               │   │
│  │  │   APISIX    │  │   ALB       │  │   OAuth2    │               │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                       应用层 Application Layer                   │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │                    Agent 编排层                              │ │   │
│  │  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐         │ │   │
│  │  │  │ LangChain│  │ LangGraph│  │ AutoGen │  │ 自研    │         │ │   │
│  │  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘         │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐             │   │
│  │  │ RAG 引擎  │  │ Prompt  │  │ 工具调用 │  │ 记忆管理 │             │   │
│  │  │         │  │ 管理    │  │ Function│  │ Memory  │             │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                        模型层 Model Layer                        │   │
│  │  ┌─────────────────┐  ┌─────────────────┐                        │   │
│  │  │   LLM 服务       │  │   Embedding 服务  │                        │   │
│  │  │  ┌───────────┐  │  │  ┌───────────┐  │                        │   │
│  │  │  │ GPT-4     │  │  │  │ text-embed│  │                        │   │
│  │  │  │ Claude    │  │  │ │-3-large   │  │                        │   │
│  │  │  │ Qwen      │  │  │  │ BGE       │  │                        │   │
│  │  │  │ DeepSeek  │  │  │            │  │                        │   │
│  │  │  └───────────┘  │  │  └───────────┘  │                        │   │
│  │  └─────────────────┘  └─────────────────┘                        │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │              模型网关（统一 API + 路由 + 降级）               │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                        数据层 Data Layer                         │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐             │   │
│  │  │ 向量数据库 │  │ 关系数据库 │  │ 对象存储  │  │ 缓存    │             │   │
│  │  │ Milvus  │  │ MySQL   │  │    S3   │  │ Redis   │             │   │
│  │  │ Qdrant  │  │   PG    │  │  MinIO  │  │         │             │   │
│  │  │ Chroma  │  │         │  │         │  │         │             │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│                                    ▼                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                     基础设施层 Infrastructure                    │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐             │   │
│  │  │  Kubernetes│  │  消息队列  │  │  日志系统  │  │ 监控告警 │             │   │
│  │  │   K8s    │  │   Kafka  │  │  ELK     │  │ Prometheus│            │   │
│  │  │         │  │   RocketMQ│ │  Loki    │  │ Grafana │             │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 三、各层详细设计

### 3.1 用户层（User Layer）

**职责：** 多终端接入，提供用户交互界面

**典型组件：**

| 终端 | 技术栈 | 说明 |
|------|--------|------|
| Web App | React/Vue + TypeScript | 管理后台、对话界面 |
| Mobile | Flutter/React Native | iOS/Android 原生体验 |
| 钉钉/企微 | 小程序/机器人 API | 企业微信、钉钉集成 |
| API | OpenAPI 规范 | 供第三方系统调用 |

---

### 3.2 接入层（Gateway Layer）

**职责：** 流量入口、鉴权、限流、路由

**核心功能：**

```yaml
# API Gateway 配置示例（Kong）
routes:
  - name: ai-chat
    paths:
      - /api/v1/chat
    services:
      - ai-chat-service
    plugins:
      - name: jwt-auth
      - name: rate-limiting
        config:
          minute: 60
          policy: local
      - name: request-transformer
        config:
          add:
            headers:
              - "X-User-Id:$(consumer_id)"
```

**关键配置：**
- **鉴权**：JWT/OAuth2/API Key
- **限流**：按用户/IP/接口维度
- **日志**：请求/响应全量记录
- **监控**：QPS、延迟、错误率

---

### 3.3 应用层（Application Layer）⭐⭐⭐

**职责：** AI 业务逻辑编排，是 AI 项目的核心

#### 3.3.1 Agent 编排层

**主流框架对比：**

| 框架 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| **LangChain** | 快速原型/RAG | 生态丰富、组件多 | 复杂场景不够灵活 |
| **LangGraph** | 复杂工作流 | 支持循环、状态机 | 学习成本高 |
| **AutoGen** | 多 Agent 协作 | 原生支持多 Agent | 微软生态绑定 |
| **自研框架** | 定制化需求 | 完全可控 | 开发成本高 |

**LangChain 典型用法：**

```python
from langchain.agents import AgentExecutor, create_openai_functions_agent
from langchain.memory import ConversationBufferMemory
from langchain.chat_models import ChatOpenAI
from langchain.prompts import ChatPromptTemplate, MessagesPlaceholder

# 1. 初始化 LLM
llm = ChatOpenAI(model="gpt-4", temperature=0.7)

# 2. 配置记忆
memory = ConversationBufferMemory(
    memory_key="chat_history",
    return_messages=True
)

# 3. 定义工具
tools = [
    SearchTool(),      # 联网搜索
    CalculatorTool(),  # 计算器
    DatabaseTool(),    # 数据库查询
]

# 4. 创建 Prompt 模板
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个智能助手，使用工具回答问题"),
    MessagesPlaceholder(variable_name="chat_history"),
    ("human", "{input}"),
    MessagesPlaceholder(variable_name="agent_scratchpad")
])

# 5. 创建 Agent
agent = create_openai_functions_agent(llm, tools, prompt)
executor = AgentExecutor(
    agent=agent,
    tools=tools,
    memory=memory,
    verbose=True
)

# 6. 执行
response = executor.invoke({"input": "查询张三的订单信息"})
```

#### 3.3.2 RAG 引擎

**RAG 核心流程：**

```
用户问题
    │
    ▼
┌─────────────────┐
│  问题预处理      │  ← 改写、扩展、关键词提取
└────┬────────────┘
     │
     ▼
┌─────────────────┐
│  向量检索        │  ← 相似度搜索 Top-K
└────┬────────────┘
     │
     ▼
┌─────────────────┐
│  重排序 Rerank   │  ← 精排提高相关性
└────┬────────────┘
     │
     ▼
┌─────────────────┐
│  Prompt 组装     │  ← 上下文 + 问题 + 指令
└────┬────────────┘
     │
     ▼
┌─────────────────┐
│  LLM 生成答案    │
└────┬────────────┘
     │
     ▼
返回用户
```

**RAG 代码实现：**

```python
from langchain.vectorstores import Milvus
from langchain.embeddings import HuggingFaceEmbeddings
from langchain.retrievers import ContextualCompressionRetriever
from langchain.retrievers.document_compressors import CrossEncoderReranker

class RAGEngine:
    def __init__(self):
        # 1. 初始化 Embedding
        self.embeddings = HuggingFaceEmbeddings(
            model_name="BAAI/bge-large-zh-v1.5"
        )
        
        # 2. 初始化向量数据库
        self.vectorstore = Milvus(
            embedding_function=self.embeddings,
            connection_args={"host": "milvus", "port": 19530},
            collection_name="knowledge_base"
        )
        
        # 3. 初始化重排序模型
        reranker = CrossEncoderReranker(
            model_name="BAAI/bge-reranker-large"
        )
        
        # 4. 创建检索器 + 压缩器
        base_retriever = self.vectorstore.as_retriever(
            search_kwargs={"k": 10}  # 召回 10 篇
        )
        self.retriever = ContextualCompressionRetriever(
            base_compressor=reranker,
            base_retriever=base_retriever
        )
    
    def query(self, question: str, history: list = None):
        # 1. 检索相关文档
        docs = self.retriever.get_relevant_documents(question)
        
        # 2. 组装 Prompt
        context = "\n\n".join([d.page_content for d in docs])
        prompt = f"""基于以下上下文回答问题：

上下文：
{context}

问题：{question}

请用中文回答："""
        
        # 3. 调用 LLM
        response = llm.invoke(prompt)
        
        # 4. 返回答案 + 来源
        return {
            "answer": response.content,
            "sources": [doc.metadata for doc in docs[:3]]
        }
```

#### 3.3.3 Prompt 管理

**为什么需要 Prompt 管理：**
- Prompt 版本迭代快
- 不同场景需要不同 Prompt
- A/B 测试需要对比效果

**Prompt 管理方案：**

```python
# Prompt 版本管理
PROMPT_TEMPLATES = {
    "customer_service": {
        "v1": """你是一个客服助手，请友好地回答用户问题。
用户问题：{question}""",
        "v2": """你是一个专业客服助手。
要求：
1. 回答准确简洁
2. 语气友好
3. 不知道就说不知道

用户问题：{question}""",
        "current": "v2"
    },
    "code_review": {
        "v1": """请审查以下代码：
{code}""",
        "current": "v1"
    }
}

def get_prompt(scene: str, **kwargs):
    template = PROMPT_TEMPLATES[scene][PROMPT_TEMPLATES[scene]["current"]]
    return template.format(**kwargs)
```

#### 3.3.4 记忆管理（Memory）

**记忆类型：**

| 类型 | 说明 | 实现 |
|------|------|------|
| **短期记忆** | 当前对话历史 | ConversationBufferMemory |
| **长期记忆** | 跨会话持久化 | VectorStore + 用户 ID |
| **工作记忆** | 任务执行中的中间状态 | Redis |

```python
from langchain.memory import ConversationBufferWindowMemory
from langchain.vectorstores import Chroma

class LongTermMemory:
    """长期记忆：向量存储 + 检索"""
    
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.embeddings = HuggingFaceEmbeddings()
        self.store = Chroma(
            collection_name=f"memory_{user_id}",
            embedding_function=self.embeddings
        )
    
    def add(self, content: str, metadata: dict = None):
        """添加记忆"""
        self.store.add_texts([content], [{
            "timestamp": datetime.now().isoformat(),
            **(metadata or {})
        }])
    
    def search(self, query: str, k: int = 3):
        """检索相关记忆"""
        return self.store.similarity_search(query, k=k)
```

---

### 3.4 模型层（Model Layer）⭐⭐⭐

**职责：** 提供 LLM 和 Embedding 推理能力

#### 3.4.1 模型选型矩阵

| 任务 | 推荐模型 | 原因 |
|------|---------|------|
| **复杂推理** | GPT-4/Claude-3-Opus | 最强推理能力 |
| **日常对话** | GPT-3.5-Turbo/Qwen-Max | 性价比高 |
| **代码生成** | Claude-3.5-Sonnet/StarCoder | 代码能力强 |
| **中文场景** | Qwen/GLM-Edge | 中文优化 |
| **Embedding** | BGE-Large/text-embed-3 | 中文检索好 |
| **Rerank** | BGE-Reranker | 精排效果好 |

#### 3.4.2 模型网关设计

**为什么需要模型网关：**
- 统一多家厂商 API
- 自动降级/切换
- 统一计费和监控

```python
class ModelGateway:
    """统一模型调用网关"""
    
    def __init__(self):
        self.providers = {
            "openai": OpenAIProvider(),
            "anthropic": AnthropicProvider(),
            "dashscope": DashscopeProvider(),  # 通义千问
        }
        self.fallback_order = ["openai", "anthropic", "dashscope"]
    
    async def chat(self, messages: list, model: str = "gpt-4"):
        """带降级的调用"""
        last_error = None
        
        for provider_name in self.fallback_order:
            try:
                provider = self.providers[provider_name]
                response = await provider.chat(messages, model=model)
                
                # 记录成功
                self.record_metrics(provider_name, "success")
                return response
                
            except Exception as e:
                last_error = e
                # 记录失败
                self.record_metrics(provider_name, "error")
                # 尝试下一个
                continue
        
        # 全部失败
        raise ModelUnavailableError(f"所有模型都不可用：{last_error}")
    
    def record_metrics(self, provider: str, status: str):
        """记录指标"""
        # 发送到 Prometheus
        metrics.increment(f"model_call_{status}", 
                         tags={"provider": provider})
```

#### 3.4.3 Token 计数与成本控制

```python
import tiktoken

class TokenCounter:
    """Token 计数器"""
    
    def __init__(self):
        self.encoding = tiktoken.get_encoding("cl100k_base")
    
    def count(self, text: str) -> int:
        return len(self.encoding.encode(text))
    
    def count_messages(self, messages: list) -> int:
        """计算多轮对话的 Token 数"""
        total = 0
        for msg in messages:
            total += self.count(msg["content"])
            total += 4  # 每条消息的 overhead
        total += 2  # 对话的 overhead
        return total

# 成本计算
COST_PER_1K = {
    "gpt-4": 0.03,      # $0.03 / 1K tokens
    "gpt-3.5-turbo": 0.002,
    "qwen-max": 0.004,
}

def calculate_cost(model: str, tokens: int) -> float:
    rate = COST_PER_1K.get(model, 0)
    return (tokens / 1000) * rate
```

---

### 3.5 数据层（Data Layer）

**职责：** 存储向量、关系数据、文件

#### 3.5.1 向量数据库选型

| 数据库 | 特点 | 适用场景 |
|--------|------|---------|
| **Milvus** | 功能全、性能好 | 大规模生产环境 |
| **Qdrant** | 轻量、Rust 编写 | 中小规模 |
| **Chroma** | 最简单、嵌入式 | 开发测试 |
| **pgvector** | PG 扩展 | 已有 PG 架构 |
| **Redis Vector** | 内存加速 | 缓存 + 向量混合 |

#### 3.5.2 数据处理流水线

```python
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain.document_loaders import PyPDFLoader

class DataPipeline:
    """数据处理流水线"""
    
    def __init__(self):
        self.splitter = RecursiveCharacterTextSplitter(
            chunk_size=500,
            chunk_overlap=50,
            separators=["\n\n", "\n", "。", "，", ""]
        )
    
    def process(self, file_path: str) -> list:
        """处理文档"""
        # 1. 加载文档
        loader = PyPDFLoader(file_path)
        docs = loader.load()
        
        # 2. 添加元数据
        for doc in docs:
            doc.metadata["source"] = file_path
            doc.metadata["timestamp"] = datetime.now().isoformat()
        
        # 3. 切分
        chunks = self.splitter.split_documents(docs)
        
        # 4. 向量化并存储
        embeddings = HuggingFaceEmbeddings()
        vectorstore = Milvus.from_documents(
            chunks,
            embeddings,
            collection_name="knowledge"
        )
        
        return chunks
```

---

### 3.6 基础设施层（Infrastructure）

**职责：** 提供运行环境、可观测性、可靠性

#### 3.6.1 完整技术栈

```yaml
# Kubernetes 部署
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-chat-service
spec:
  replicas: 3
  selector:
    matchLabels:
      app: ai-chat
  template:
    spec:
      containers:
      - name: ai-chat
        image: ai-chat:latest
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        env:
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: ai-secrets
              key: openai-key
        livenessProbe:
          httpGet:
            path: /health
            port: 8000
          initialDelaySeconds: 30
          periodSeconds: 10
```

#### 3.6.2 监控告警

```python
from prometheus_client import Counter, Histogram, Gauge

# 定义指标
REQUEST_COUNT = Counter(
    'ai_request_total',
    'Total AI requests',
    ['model', 'status']
)

REQUEST_LATENCY = Histogram(
    'ai_request_latency_seconds',
    'AI request latency',
    ['model'],
    buckets=(0.1, 0.5, 1, 2, 5, 10)
)

TOKEN_USAGE = Counter(
    'ai_tokens_total',
    'Total tokens used',
    ['model', 'type']  # input/output
)

# 使用
@REQUEST_LATENCY.labels(model='gpt-4').time():
    response = llm.chat(messages)
    
REQUEST_COUNT.labels(model='gpt-4', status='success').inc()
TOKEN_USAGE.labels(model='gpt-4', type='input').inc(input_tokens)
TOKEN_USAGE.labels(model='gpt-4', type='output').inc(output_tokens)
```

---

## 四、AI 项目架构图（简化版）

```
                    ┌─────────────┐
                    │   用户终端   │
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────┐
                    │ API Gateway │
                    │ + 鉴权限流   │
                    └──────┬──────┘
                           │
                           ▼
┌───────────────────────────────────────────────┐
│            AI 应用服务（Python/FastAPI）        │
│                                               │
│  ┌─────────────────────────────────────────┐ │
│  │  Agent 编排（LangChain/LangGraph）      │ │
│  │                                         │ │
│  │  ┌───────┐  ┌───────┐  ┌───────┐       │ │
│  │  │ RAG   │  │ Tool  │  │Memory │       │ │
│  │  │ 引擎  │  │ 调用  │  │ 管理  │       │ │
│  │  └───────┘  └───────┘  └───────┘       │ │
│  └─────────────────────────────────────────┘ │
└───────────────────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
         ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  向量数据库   │  │  关系数据库   │  │  对象存储     │
│  Milvus     │  │  PostgreSQL │  │     S3      │
│  Qdrant     │  │    MySQL    │  │    MinIO    │
└──────────────┘  └──────────────┘  └──────────────┘
         │                 │                 │
         └─────────────────┼─────────────────┘
                           │
                           ▼
                  ┌─────────────┐
                  │  模型服务    │
                  │  OpenAI API │
                  │  Anthropic  │
                  │  通义千问    │
                  └─────────────┘
```

---

## 五、面试答题话术

**面试官问：AI 项目整体架构是怎样的？**

**答：** 我从六层架构来回答：

**第一层是用户层**，多终端接入，包括 Web、Mobile、钉钉企微等。

**第二层是接入层**，用 API Gateway 做鉴权、限流、路由，保证入口安全。

**第三层是应用层，这是核心**，包含 4 个模块：
1. **Agent 编排**：用 LangChain/LangGraph 组织业务逻辑
2. **RAG 引擎**：向量检索 + 重排序 + Prompt 组装
3. **Prompt 管理**：版本控制、A/B 测试
4. **记忆管理**：短期对话历史 + 长期向量记忆

**第四层是模型层**，对接多家 LLM（OpenAI、Anthropic、通义），通过模型网关统一 API、自动降级。

**第五层是数据层**，向量数据库（Milvus/Qdrant）存知识，关系数据库存业务数据，对象存储存文档。

**第六层是基础设施**，K8s 容器化部署，Prometheus + Grafana 监控，ELK 日志系统。

**核心设计原则：** 分层解耦、可观测性、安全合规。

**举个例子**：我们项目的 RAG 流程是：用户问题 → 向量检索召回 10 篇 → BGE-Reranker 重排序取 Top-3 → 组装 Prompt → 调用 GPT-4 生成答案 → 返回用户。

---

## 六、扩展问题

### 扩展 1：如何保证 RAG 检索质量？

**答：** 四个优化点：
1. **文档切分**：500-1000 tokens + 50-100 重叠
2. **Embedding 选型**：BGE-Large-Zh 中文效果好
3. **重排序**：召回 10 篇，Rerank 精排取 Top-3
4. **HyDE**：用 LLM 生成假设文档再检索

### 扩展 2：如何处理大 Context？

**答：** 三种方案：
1. **Map-Reduce**：分块处理再汇总
2. **Refine**：迭代更新答案
3. **Sliding Window**：保留最近 N 轮

### 扩展 3：如何降低 Token 成本？

**答：** 四个手段：
1. **小模型优先**：简单任务用 GPT-3.5/Qwen
2. **Prompt 压缩**：精简冗余内容
3. **缓存**：相同问题直接返回缓存答案
4. **批量处理**：合并多个请求
