# 如何设计 MQ 高可用方案（如 Consumer、集群、容灾）？

## 一、核心结论

**MQ 高可用 = 高可用集群 + 消费者高可用 + 跨机房/跨地域容灾**

**设计原则：**
1. **无单点故障**：任何节点宕机都不影响整体服务
2. **自动故障转移**：主节点挂掉，从节点秒级接管
3. **数据不丢失**：多副本同步，保证数据可靠性
4. **可水平扩展**：支持动态扩容，应对流量增长

**高可用目标：**
- 单机故障：无感知，自动切换
- 机房故障：秒级切换，数据不丢
- 地域故障：分钟级切换，可接受少量数据丢失

---

## 二、MQ 高可用架构总览

### 2.1 整体架构图

```
                         ┌─────────────────────────────────┐
                         │          负载均衡层              │
                         │   Nginx / LVS / DNS 轮询         │
                         └────────────────┬────────────────┘
                                          │
              ┌───────────────────────────┼───────────────────────────┐
              │                           │                           │
              ▼                           ▼                           ▼
    ┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
    │   Producer 集群  │         │   Producer 集群  │         │   Producer 集群  │
    │    (机房 A)     │         │    (机房 B)     │         │    (机房 C)     │
    └────────┬────────┘         └────────┬────────┘         └────────┬────────┘
             │                           │                           │
             └───────────────────────────┼───────────────────────────┘
                                         │
              ┌──────────────────────────┴──────────────────────────┐
              │                                                     │
              ▼                                                     ▼
    ┌─────────────────┐                                   ┌─────────────────┐
    │   MQ 集群 (主)   │◄────── 同步/异步复制 ──────►      │   MQ 集群 (备)   │
    │    (机房 A)     │                                   │    (机房 B)     │
    └────────┬────────┘                                   └────────┬────────┘
             │                                                      │
             └───────────────────────────┬──────────────────────────┘
                                         │
              ┌──────────────────────────┴──────────────────────────┐
              │                           │                           │
              ▼                           ▼                           ▼
    ┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
    │  Consumer 集群  │         │  Consumer 集群  │         │  Consumer 集群  │
    │    (机房 A)     │         │    (机房 B)     │         │    (机房 C)     │
    └─────────────────┘         └─────────────────┘         └─────────────────┘
```

---

### 2.2 高可用层级

```
┌─────────────────────────────────────────────────────────┐
│                   MQ 高可用层级                          │
├─────────────────┬───────────────────────────────────────┤
│   层级          │           方案                        │
├─────────────────┼───────────────────────────────────────┤
│ L1: 消费者高可用 │ 多实例 + 负载均衡 + 自动重连          │
│ L2: Broker 高可用│ 主从复制 + 故障自动转移               │
│ L3: 集群高可用  │ 多节点 + 副本机制 + 仲裁              │
│ L4: 机房容灾    │ 多机房部署 + 流量切换                 │
│ L5: 异地容灾    │ 异地多活 + 数据同步                 │
└─────────────────┴───────────────────────────────────────┘
```

---

## 三、Consumer 高可用设计

### 3.1 多实例部署

**架构**：
```
              ┌─────────────┐
              │  负载均衡   │
              │  (K8s/Nginx)│
              └──────┬──────┘
                     │
       ┌─────────────┼─────────────┐
       │             │             │
       ▼             ▼             ▼
 ┌──────────┐  ┌──────────┐  ┌──────────┐
 │Consumer 1│  │Consumer 2│  │Consumer 3│
 └────┬─────┘  └────┬─────┘  └────┬─────┘
      │             │             │
      └─────────────┼─────────────┘
                    │
              ┌─────▼─────┐
              │  MQ Broker│
              └───────────┘
```

**部署要点**：
1. **至少 2 个实例**：防止单实例宕机
2. **跨可用区部署**：实例分布在不同机房/可用区
3. **无状态设计**：消费者不保存状态，可随意扩缩容
4. **K8s 部署**：利用 Deployment + HPA 自动扩缩容

**K8s 部署配置**：
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mq-consumer
spec:
  replicas: 3  # 至少 3 个副本
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0  # 更新时保证 0 不可用
  template:
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchLabels:
                  app: mq-consumer
              topologyKey: failure-domain.beta.kubernetes.io/zone  # 跨可用区
      containers:
      - name: consumer
        image: mq-consumer:latest
        livenessProbe:  # 健康检查
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:  # 就绪检查
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
```

---

### 3.2 消费组模式

**RabbitMQ 消费组**：
```
              ┌──────────────┐
              │   Queue      │
              └──────┬───────┘
                     │
       ┌─────────────┼─────────────┐
       │             │             │
       ▼             ▼             ▼
 ┌──────────┐  ┌──────────┐  ┌──────────┐
 │Consumer A│  │Consumer B│  │Consumer C│
 │ (组 1)   │  │ (组 1)   │  │ (组 1)   │
 └──────────┘  └──────────┘  └──────────┘
      │             │             │
      ▼             ▼             ▼
  消息 1          消息 2          消息 3
  (负载均衡，每条消息只被一个消费者消费)
```

**Kafka 消费组**：
```
              ┌──────────────────────────┐
              │      Topic (3 Partitions)│
              │  P0  │  P1  │  P2        │
              └──┬───┴──┬───┴──┬─────────┘
                 │      │      │
                 ▼      ▼      ▼
           ┌────────────────────────┐
           │   Consumer Group       │
           │  C0   │  C1   │  C2    │
           │ (P0)  │ (P1)  │ (P2)   │
           └────────────────────────┘
```

**配置要点**：
- 消费者数量 >= Partition 数量
- 每个 Partition 只被一个消费者消费
- 消费者宕机，Partition 自动 realance

---

### 3.3 消费者故障转移

**RabbitMQ 自动重连**：
```java
@Configuration
public class RabbitConfig {

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost("mq-server");
        factory.setPort(5672);
        factory.setUsername("user");
        factory.setPassword("password");

        // 自动重连配置
        factory.setCacheMode(CacheMode.CONNECTION);
        factory.setConnectionCacheSize(10);

        // 重试配置
        factory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(true);
        factory.getRabbitConnectionFactory().setNetworkRecoveryInterval(5000);

        return factory;
    }
}
```

**Kafka 消费者故障转移**：
```java
@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                  "kafka1:9092,kafka2:9092,kafka3:9092");  // 多节点
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-consumer-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // 会话超时（检测消费者宕机）
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);

        // 心跳间隔
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        // 最大轮询间隔（超过认为消费者挂了）
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

        return new DefaultKafkaConsumerFactory<>(props);
    }
}
```

---

## 四、MQ 集群高可用设计

### 4.1 RabbitMQ 集群模式

#### 模式 1：普通集群（镜像队列）

**架构**：
```
        ┌─────────┐
        │  Node1  │◄──── Master
        └────┬────┘
             │ 同步
        ┌────▼────┐
        │  Node2  │◄──── Mirror
        └────┬────┘
             │ 同步
        ┌────▼────┐
        │  Node3  │◄──── Mirror
        └─────────┘
```

**配置**：
```bash
# 1. 启用镜像队列策略
rabbitmqctl set_policy ha-all "^ha\." '{"ha-mode":"all","ha-sync-mode":"automatic"}'

# ha-mode: all-所有节点同步，exactly-指定数量，nodes-指定节点
# ha-sync-mode: automatic-自动同步，manual-手动同步
```

**优缺点**：
| 优点 | 缺点 |
|------|------|
| 高可用，任意节点宕机可用 | 数据全量同步，网络开销大 |
| 故障自动转移 | 队列深度受限（受最小节点限制）|

---

#### 模式 2：Quorum Queue（推荐）

**架构**（基于 Raft 协议）：
```
        ┌─────────┐
        │  Node1  │◄──── Leader
        └────┬────┘
             │ Raft 复制
        ┌────▼────┐
        │  Node2  │◄──── Follower
        └────┬────┘
             │ Raft 复制
        ┌────▼────┐
        │  Node3  │◄──── Follower
        └─────────┘

        写操作：Leader 处理，同步到多数 Follower 后返回
        读操作：Leader 处理
```

**配置**：
```java
// 声明 Quorum Queue
Map<String, Object> args = new HashMap<>();
args.put("x-queue-type", "quorum");

channel.queueDeclare("order-queue", true, false, false, args);
```

**优缺点**：

| 优点 | 缺点 |
|------|------|
| 数据强一致（Raft 协议） | 性能比普通队列低 |
| 自动故障转移 | 至少 3 节点 |
| 支持仲裁（多数节点存活即可）| 仅支持单 Leader |

---

### 4.2 Kafka 集群高可用

#### 架构设计

```
                    ┌─────────────┐
                    │  Zookeeper  │
                    │  集群 (3 节点) │
                    └──────┬──────┘
                           │
       ┌───────────────────┼───────────────────┐
       │                   │                   │
       ▼                   ▼                   ▼
 ┌──────────┐        ┌──────────┐        ┌──────────┐
 │ Broker1  │        │ Broker2  │        │ Broker3  │
 │          │        │          │        │          │
 │ P0(L)    │        │ P1(L)    │        │ P2(L)    │
 │ P1(R)    │        │ P2(R)    │        │ P0(R)    │
 │ P2(R)    │        │ P0(R)    │        │ P1(R)    │
 └──────────┘        └──────────┘        └──────────┘

 L=Leader, R=Replica
 每个 Partition 有 1 个 Leader + 2 个 Replica
```

**关键配置**：
```properties
# Broker 配置
# 副本数
default.replication.factor=3

# 最小同步副本数（防止数据丢失）
min.insync.replicas=2

# ISR 超时（副本落后多少时间踢出 ISR）
replica.lag.time.max.ms=30000

# 自动 Leader 选举
auto.leader.rebalance.enable=true
leader.imbalance.check.interval.seconds=30
```

**Controller 故障转移**：
- Kafka 2.x：Zookeeper 选举 Controller
- Kafka 3.x：KRaft 模式（去 Zookeeper）

---

### 4.3 RocketMQ 集群高可用

#### 架构设计

```
                    ┌─────────────┐
                    │  NameServer │
                    │  集群 (2 节点) │
                    └──────┬──────┘
                           │
       ┌───────────────────┼───────────────────┐
       │                   │                   │
       ▼                   ▼                   ▼
 ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
 │ Broker-A     │   │ Broker-B     │   │ Broker-C     │
 │ (Master)     │   │ (Master)     │   │ (Master)     │
 │              │   │              │   │              │
 │ ┌────────┐   │   │ ┌────────┐   │   │ ┌────────┐   │
 │ │ Slave  │   │   │ │ Slave  │   │   │ │ Slave  │   │
 │ │ (同步) │   │   │ │ (同步) │   │   │ │ (同步) │   │
 │ └────────┘   │   │ └────────┘   │   │ └────────┘   │
 └──────────────┘   └──────────────┘   └──────────────┘
```

**部署模式**：

| 模式 | 说明 | 可用性 |
|------|------|--------|
| 单 Master | 单节点 | 无高可用 |
| 多 Master | 多节点无 Slave | 部分高可用 |
| 多 Master 多 Slave（同步）| 同步刷盘 | 高可用 + 数据不丢 |
| 多 Master 多 Slave（异步）| 异步刷盘 | 高可用 + 可能丢数据 |
| Dledger | 多副本 + Raft | 高可用 + 强一致 |

**Dledger 配置**（推荐）：
```properties
# 启用 Dledger
enableDLegerCommitLog=true

# Dledger 组名
dLegerGroup=RaftGroup1

# 节点配置
dLegerPeers=n0-192.168.1.10:9878;n1-192.168.1.11:9878;n2-192.168.1.12:9878

# 当前节点 ID
dLegerSelfId=n0

# 端口
dLegerPort=9878
```

---

## 五、跨机房容灾设计

### 5.1 同城双活

**架构**：
```
                    ┌─────────────┐
                    │   全局负载   │
                    │  GSLB/DNS   │
                    └──────┬──────┘
                           │
         ┌─────────────────┴─────────────────┐
         │                                   │
         ▼                                   ▼
 ┌─────────────────┐               ┌─────────────────┐
 │    机房 A        │               │    机房 B        │
 │  (主)           │               │  (备)           │
 │                 │               │                 │
 │ ┌─────────────┐ │               │ ┌─────────────┐ │
 │ │ MQ 集群     │ │               │ │ MQ 集群     │ │
 │ │ (双写)     │ │               │ │ (双写)     │ │
 │ └─────────────┘ │               │ └─────────────┘ │
 └─────────────────┘               └─────────────────┘
         │                                   │
         └───────────────┬───────────────────┘
                         │
                  ┌──────▼──────┐
                  │  数据同步   │
                  │ (MQ 消息同步)│
                  └─────────────┘
```

**方案选择**：

| 方案 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **冷备** | 备机房平时不流量 | 成本低 | 切换慢（分钟级） |
| **温备** | 备机房只读流量 | 切换较快 | 数据可能不一致 |
| **双活** | 两机房都写 | 切换快 | 复杂度高，需解决冲突 |

---

### 5.2 异地容灾

**架构**（三地五中心）：
```
     ┌──────────────────────────────────────────────────────┐
     │                      北京 (主)                        │
     │   ┌─────────────┐           ┌─────────────┐         │
     │   │  机房 A1    │           │  机房 A2    │         │
     │   │  MQ 集群    │◄──同步──►│  MQ 集群    │         │
     │   └─────────────┘           └─────────────┘         │
     └──────────────────────────────────────────────────────┘
                              │
                       异步复制
                              │
     ┌──────────────────────────────────────────────────────┐
     │                      上海 (备)                        │
     │   ┌─────────────┐           ┌─────────────┐         │
     │   │  机房 B1    │           │  机房 B2    │         │
     │   │  MQ 集群    │◄──同步──►│  MQ 集群    │         │
     │   └─────────────┘           └─────────────┘         │
     └──────────────────────────────────────────────────────┘
                              │
                       异步复制
                              │
     ┌──────────────────────────────────────────────────────┐
     │                      广州 (备)                        │
     │   ┌─────────────┐                                    │
     │   │  机房 C1    │                                    │
     │   │  MQ 集群    │                                    │
     │   └─────────────┘                                    │
     └──────────────────────────────────────────────────────┘
```

**切换策略**：
1. **正常情况**：北京双机房双写
2. **北京单机房故障**：流量切到北京另一机房
3. **北京全部故障**：流量切到上海，异步复制数据

---

## 六、高可用配置最佳实践

### 6.1 RabbitMQ 高可用配置

```yaml
# docker-compose 部署高可用 RabbitMQ
version: '3'
services:
  rabbitmq1:
    image: rabbitmq:3.12-management
    hostname: rabbitmq1
    environment:
      RABBITMQ_ERLANG_COOKIE: "mycookie"
      RABBITMQ_DEFAULT_USER: admin
      RABBITMQ_DEFAULT_PASS: password
    ports:
      - "5672:5672"
      - "15672:15672"
    command: >
      bash -c "
        rabbitmq-server &
        sleep 10 &&
        rabbitmqctl join_cluster rabbitmq@rabbitmq2 &&
        rabbitmqctl set_policy ha-all '^ha\\.' '{\"ha-mode\":\"all\",\"ha-sync-mode\":\"automatic\"}'
      "

  rabbitmq2:
    image: rabbitmq:3.12-management
    hostname: rabbitmq2
    environment:
      RABBITMQ_ERLANG_COOKIE: "mycookie"
    ports:
      - "5673:5672"
      - "15673:15672"

  rabbitmq3:
    image: rabbitmq:3.12-management
    hostname: rabbitmq3
    environment:
      RABBITMQ_ERLANG_COOKIE: "mycookie"
    ports:
      - "5674:5672"
    depends_on:
      - rabbitmq2
    command: >
      bash -c "
        sleep 15 &&
        rabbitmqctl join_cluster rabbitmq@rabbitmq2
      "
```

---

### 6.2 Kafka 高可用配置

```yaml
# docker-compose 部署高可用 Kafka (KRaft 模式)
version: '3'
services:
  controller1:
    image: apache/kafka:3.6.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@controller1:9093,2@controller2:9093,3@controller3:9093"
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    ports:
      - "9093:9093"

  controller2:
    image: apache/kafka:3.6.0
    environment:
      KAFKA_NODE_ID: 2
      KAFKA_PROCESS_ROLES: controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@controller1:9093,2@controller2:9093,3@controller3:9093"
    ports:
      - "9094:9093"

  controller3:
    image: apache/kafka:3.6.0
    environment:
      KAFKA_NODE_ID: 3
      KAFKA_PROCESS_ROLES: controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@controller1:9093,2@controller2:9093,3@controller3:9093"
    ports:
      - "9095:9093"

  broker1:
    image: apache/kafka:3.6.0
    environment:
      KAFKA_NODE_ID: 101
      KAFKA_PROCESS_ROLES: broker
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@controller1:9093,2@controller2:9093,3@controller3:9093"
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker1:9092
    ports:
      - "9092:9092"

  broker2:
    image: apache/kafka:3.6.0
    environment:
      KAFKA_NODE_ID: 102
      KAFKA_PROCESS_ROLES: broker
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@controller1:9093,2@controller2:9093,3@controller3:9093"
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker2:9093
    ports:
      - "9093:9093"

  broker3:
    image: apache/kafka:3.6.0
    environment:
      KAFKA_NODE_ID: 103
      KAFKA_PROCESS_ROLES: broker
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@controller1:9093,2@controller2:9093,3@controller3:9093"
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9094
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker3:9094
    ports:
      - "9094:9094"
```

---

### 6.3 RocketMQ 高可用配置

```yaml
# docker-compose 部署高可用 RocketMQ (Dledger)
version: '3'
services:
  namesrv:
    image: apache/rocketmq:4.9.4
    command: sh namesrv.sh
    ports:
      - "9876:9876"
    environment:
      JAVA_OPT: "-Xms256m -Xmx256m"

  broker-a:
    image: apache/rocketmq:4.9.4
    command: sh broker.sh
    ports:
      - "10911:10911"
      - "10909:10909"
    environment:
      JAVA_OPT: "-Xms512m -Xmx512m"
      NAMESRV_ADDR: namesrv:9876
    volumes:
      - ./conf/dledger/broker-a.conf:/home/rocketmq/conf/dledger.conf

  broker-b:
    image: apache/rocketmq:4.9.4
    command: sh broker.sh
    ports:
      - "20911:10911"
      - "20909:10909"
    environment:
      JAVA_OPT: "-Xms512m -Xmx512m"
      NAMESRV_ADDR: namesrv:9876
    volumes:
      - ./conf/dledger/broker-b.conf:/home/rocketmq/conf/dledger.conf

  broker-c:
    image: apache/rocketmq:4.9.4
    command: sh broker.sh
    ports:
      - "30911:10911"
      - "30909:10909"
    environment:
      JAVA_OPT: "-Xms512m -Xmx512m"
      NAMESRV_ADDR: namesrv:9876
    volumes:
      - ./conf/dledger/broker-c.conf:/home/rocketmq/conf/dledger.conf
```

**Dledger 配置文件**（broker-a.conf）：
```properties
brokerClusterName=DefaultCluster
brokerName=broker-a
listenPort=10911
namesrvAddr=namesrv:9876

# Dledger 配置
enableDLegerCommitLog=true
dLegerGroup=DefaultDledgerGroup
dLegerPeers=n0-broker-a:40911;n1-broker-b:40911;n2-broker-c:40911
dLegerSelfId=n0
dLegerPort=40911
```

---

## 七、高可用测试方案

### 7.1 故障注入测试

**测试场景**：

| 测试项 | 操作 | 预期结果 |
|--------|------|---------|
| Broker 宕机 | kill -9 Broker 进程 | 秒级切换，消息不丢 |
| 网络分区 | iptables 阻断节点间通信 | 多数节点可用 |
| 磁盘故障 | 模拟磁盘写满 | 自动切换到其他节点 |
| 消费者宕机 | kill -9 Consumer | 消息重投，其他消费者接管 |

**测试脚本**：
```bash
#!/bin/bash
# RabbitMQ 故障注入测试

# 1. 发送消息
for i in {1..1000}; do
    rabbitmqadmin publish exchange=ha-exchange routing_key=ha-key payload="message-$i"
done

# 2. 模拟 Master 宕机
ssh rabbitmq1 "rabbitmqctl stop_app"

# 3. 检查消息是否可消费
rabbitmqadmin get queue=ha-queue count=1000

# 4. 检查 Mirror 是否成为 Master
rabbitmqctl list_queues name state
```

---

### 7.2 压力测试

**工具选择**：
- RabbitMQ: `perf-test`
- Kafka: `kafka-producer-perf-test`
- RocketMQ: `benchmark/benchmarkProducer.sh`

**RabbitMQ 压测**：
```bash
# 每秒 1000 条消息，持续 60 秒
docker run --rm -it pivotalrabbitmq/perf-test \
  --uri amqp://rabbitmq1:5672 \
  --rate 1000 \
  --size 1024 \
  --publishers 10 \
  --consumers 10
```

---

## 八、面试答题话术（直接背）

**面试官问：如何设计 MQ 高可用方案？**

答：我从消费者高可用、集群高可用、容灾三方面说：

**第一，Consumer 高可用：**
1. **多实例部署**：至少 2 个实例，跨可用区部署
2. **消费组模式**：多条消息并行消费，故障自动转移
3. **自动重连**：配置网络恢复和重试机制
4. **K8s 部署**：利用健康检查 + 自动重启 + HPA 扩缩容

**第二，MQ 集群高可用：**
1. **RabbitMQ**：镜像队列模式或 Quorum Queue（Raft 协议）
2. **Kafka**：多副本（replication.factor=3）+ min.insync.replicas=2
3. **RocketMQ**：多 Master 多 Slave 或 Dledger 模式

**第三，跨机房容灾：**
1. **同城双活**：两个机房双写，数据实时同步
2. **异地容灾**：三地五中心，异步复制
3. **故障切换**：秒级检测，秒级切换

**核心原则**：无单点、自动切换、数据不丢、可水平扩展

**举例**：我们生产环境 RocketMQ 用的是 3 节点 Dledger 模式，任意节点宕机不影响服务，消费者 K8s 部署，3 副本跨可用区，保证高可用。

---

## 九、扩展问题

### Q1：RabbitMQ 镜像队列和 Quorum Queue 怎么选？

**答**：
- **镜像队列**：性能好，适合一般场景
- **Quorum Queue**：数据强一致，适合金融、订单等核心业务
- **建议**：新业务优先用 Quorum Queue，老业务迁移逐步进行

---

### Q2：Kafka 如何保证 Controller 高可用？

**答**：
- **Kafka 2.x**：Zookeeper 集群选举 Controller，挂掉自动重选
- **Kafka 3.x**：KRaft 模式，Controller 内嵌到 Broker，Raft 协议选举
- **配置**：至少 3 个 Controller 节点，容忍 1 个故障

---

### Q3：脑裂问题怎么解决？

**答**：
1. **奇数节点**：集群节点数为奇数（3/5），避免平票
2. **仲裁机制**：多数节点存活才提供服务
3. **网络分区检测**：分区后，少数派自动降级为只读或不可用
4. **配置**：RabbitMQ 设置 `cluster_partition_handling=ignore_minority`
