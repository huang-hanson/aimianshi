# 如何保证 MQ 的可靠性？

## 一、核心结论

**MQ 可靠性 = 生产端不丢 + 服务端不丢 + 消费端不重不漏**

保证 MQ 可靠性需要从**生产者、MQ 服务端、消费者**三个环节入手，每个环节都有对应的丢失风险和解决方案。

**核心三原则：**
1. **生产端**：Confirm 机制 + 重试
2. **服务端**：持久化 + 多副本
3. **消费端**：手动 ACK + 幂等消费

---

## 二、MQ 消息丢失的三个阶段

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  生产者     │ ───► │  MQ 服务端   │ ───► │  消费者     │
│  (丢消息①)  │      │  (丢消息②)  │      │  (丢消息③)  │
└─────────────┘      └─────────────┘      └─────────────┘
```

**① 生产端丢失**：消息没发送到 MQ 就失败了（网络问题、MQ 宕机）

**② 服务端丢失**：MQ 收到消息但没持久化就宕机了

**③ 消费端丢失**：消费者拿到消息但处理失败，MQ 以为成功了

---

## 三、生产端可靠性保证

### 3.1 问题分析

```
生产者 ──网络故障──► MQ Broker
         ↓
      消息丢失（生产者不知道发送失败）
```

### 3.2 解决方案

#### 方案 1：Confirm 确认机制（推荐）

**原理**：生产者发送消息后等待 Broker 的 ACK 确认，未收到则重试

**RabbitMQ 实现：**
```java
// 开启 Confirm 模式
channel.confirmSelect();

// 添加确认监听
channel.addConfirmListener(new ConfirmListener() {
    @Override
    public void handleAck(long deliveryTag, boolean multiple) {
        // 消息发送成功
        log.info("消息发送成功：{}", deliveryTag);
    }

    @Override
    public void handleNack(long deliveryTag, boolean multiple) {
        // 消息发送失败，重试或记录日志
        log.error("消息发送失败：{}", deliveryTag);
        // 重试逻辑
    }
});
```

**Kafka 实现：**
```properties
# acks=all 保证所有副本都确认
acks=all

# 重试次数
retries=3

# 重试间隔
retry.backoff.ms=100
```

**RocketMQ 实现：**
```java
// 同步发送（等待响应）
SendResult sendResult = producer.send(msg);
if (sendResult.getSendStatus() == SendStatus.SEND_OK) {
    // 发送成功
} else {
    // 发送失败，重试
}
```

---

#### 方案 2：事务消息（不推荐，性能差）

**原理**：发送方开启事务，确保消息发送与本地事务一起提交

```java
// RabbitMQ 事务
channel.txSelect();
try {
    channel.basicPublish(exchange, routingKey, message);
    // 本地业务逻辑
    channel.txCommit();
} catch (Exception e) {
    channel.txRollback();
}
```

**缺点**：同步等待，吞吐量下降 50% 以上，生产环境很少用

---

#### 方案 3：本地消息表（最终一致性）

**原理**：将消息先写入本地数据库，再由定时任务投递到 MQ

```
┌─────────────────────────────────────────┐
│  1. 业务数据 + 消息写入本地表（同一事务）│
│  2. 定时任务扫描未发送消息              │
│  3. 发送到 MQ                           │
│  4. 更新消息状态为已发送                │
└─────────────────────────────────────────┘
```

**适用场景**：对可靠性要求极高，能接受秒级延迟的场景

---

#### 方案 4：幂等性设计（配合重试）

**核心思想**：消费端支持去重，允许生产端重试

```java
// 消费端去重示例
public void consume(Message message) {
    String messageId = message.getId();

    // Redis 去重（SETNX）
    if (redisTemplate.opsForValue().setIfAbsent(
            "msg_processed:" + messageId, "1", 24, HOURS)) {
        // 第一次消费，处理业务逻辑
        process(message);
    } else {
        // 重复消息，直接忽略
        log.warn("重复消息：{}", messageId);
    }
}
```

---

## 四、MQ 服务端可靠性保证

### 4.1 问题分析

```
MQ Broker 收到消息
      ↓
  还没写磁盘就宕机了
      ↓
  消息丢失
```

### 4.2 解决方案

#### 方案 1：消息持久化

**RabbitMQ 持久化配置：**
```java
// 1. Exchange 持久化
channel.exchangeDeclare("myExchange", BuiltinExchangeType.DIRECT, true);

// 2. Queue 持久化
channel.queueDeclare("myQueue", true, false, false, null);

// 3. Message 持久化
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .deliveryMode(2)  // 2=持久化，1=不持久化
    .build();
channel.basicPublish(exchange, routingKey, props, body);
```

**Kafka 持久化配置：**
```properties
# 消息持久化到 commitlog
# 默认就是持久化的，无需额外配置

# 刷盘策略（同步刷盘更可靠，异步性能更好）
flush.messages=10000        # 多少条消息刷盘
flush.ms=1000               # 多少时间刷盘
```

**RocketMQ 持久化配置：**
```properties
# 同步刷盘（更可靠）
flushDiskType=SYNC_FLUSH

# 异步刷盘（性能更好）
flushDiskType=ASYNC_FLUSH
```

---

#### 方案 2：多副本机制

**RabbitMQ 镜像队列：**
```
Queue A (Master) ───► Queue A (Mirror 1)
                    ───► Queue A (Mirror 2)
```
- 消息同步到所有镜像节点
- Master 宕机，Mirror 自动切换

**Kafka 多副本：**
```properties
# 副本数
num.partitions=3
default.replication.factor=3

# 最小同步副本数（防止数据丢失）
min.insync.replicas=2

# acks 配置（配合 min.insync.replicas 使用）
acks=all
```

**RocketMQ Dledger：**
```
Broker A (Master) ───► Broker A (Slave 1)
                     ───► Broker A (Slave 2)
```
- 同步复制 + Raft 协议
- 自动故障转移

---

#### 方案 3：ACK 机制

**RabbitMQ 手动 ACK：**
```java
// 关闭自动 ACK
channel.basicConsume(queue, false, callback);

// 消费成功才 ACK
try {
    process(message);
    channel.basicAck(deliveryTag, false);
} catch (Exception e) {
    // 消费失败，NACK 重回队列
    channel.basicNack(deliveryTag, false, true);
}
```

**Kafka 手动提交 Offset：**
```java
// 关闭自动提交
props.put("enable.auto.commit", "false");

// 业务处理成功后手动提交
consumer.commitSync();
```

---

## 五、消费端可靠性保证

### 5.1 问题分析

```
消费者收到消息
      ↓
  处理业务逻辑时异常
      ↓
  没来得及 ACK，MQ 以为消费成功
      ↓
  消息丢失
```

### 5.2 解决方案

#### 方案 1：手动 ACK（核心）

上面已讲，核心是**业务处理成功后再 ACK**

#### 方案 2：重试机制

**RabbitMQ 重试队列：**
```
主队列 ──消费失败──► 重试队列 ──延时──► 主队列
                  ↓ (超过 3 次)
                死信队列
```

**Spring AMQP 重试配置：**
```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 1000  # 首次重试间隔 1s
          max-attempts: 3         # 最多重试 3 次
          max-interval: 10000     # 最大间隔 10s
          multiplier: 2.0         # 间隔倍数（指数退避）
```

#### 方案 3：死信队列（DLQ）

**RabbitMQ 死信队列配置：**
```java
// 死信交换机
channel.exchangeDeclare("dlx.exchange", BuiltinExchangeType.DIRECT, true);

// 死信队列
channel.queueDeclare("dlx.queue", true, false, false,
    Map.of("x-dead-letter-exchange", "dlx.exchange",
           "x-dead-letter-routing-key", "dlx.key"));

// 主队列绑定死信策略
channel.queueDeclare("main.queue", true, false, false,
    Map.of("x-dead-letter-exchange", "dlx.exchange",
           "x-dead-letter-routing-key", "dlx.key",
           "x-message-ttl", 60000));  // 60 秒过期
```

**适用场景**：超过重试次数仍然失败的消息，需要人工介入处理

#### 方案 4：幂等消费（必须）

**去重方案对比：**

| 方案 | 实现方式 | 适用场景 |
|------|---------|---------|
| **数据库唯一键** | 插入业务表，利用唯一索引去重 | 有数据库的场景 |
| **Redis SETNX** | `SETNX msg:id 1 EX 24h` | 高并发场景 |
| **状态机** | `UPDATE order SET status=2 WHERE id=1 AND status=1` | 订单状态流转 |
| **消息 ID 表** | 单独建表记录已消费消息 ID | 精确去重 |

**代码示例：**
```java
@Transactional
public void consume(OrderMessage message) {
    String messageId = message.getId();

    // 方案 1：利用数据库唯一索引
    try {
        messageLogMapper.insert(new MessageLog(messageId, "ORDER"));
    } catch (DuplicateKeyException e) {
        log.warn("重复消息，跳过：{}", messageId);
        return;
    }

    // 处理业务逻辑
    orderService.process(message);
}
```

---

## 六、各 MQ 可靠性配置对比表

| 配置项 | RabbitMQ | Kafka | RocketMQ |
|--------|----------|-------|----------|
| **生产端确认** | Confirm 机制 | acks=all | 同步发送 |
| **消息持久化** | deliveryMode=2 | 默认持久化 | 同步刷盘 |
| **副本机制** | 镜像队列 | 多副本 + ISR | Master/Slave |
| **消费确认** | 手动 ACK | 手动 commit | ACK 机制 |
| **失败重试** | 重试队列 | 消费者重试 | 重试队列 |
| **死信处理** | DLX | 消费重试表 | DLQ |
| **事务支持** | 支持（性能差） | 事务（性能差） | 事务消息（推荐） |

---

## 七、生产环境最佳实践

### 7.1 可靠性配置组合

```yaml
# RabbitMQ 推荐配置
生产者：Confirm + 重试
服务端：持久化 + 镜像队列
消费者：手动 ACK + 重试队列 + 死信队列 + 幂等

# Kafka 推荐配置
acks=all
min.insync.replicas=2
replication.factor=3
消费者：手动 commit + 幂等

# RocketMQ 推荐配置
生产者：同步发送 + 重试
服务端：同步刷盘 + 多副本
消费者：ACK + 幂等
```

### 7.2 监控告警

```
关键监控指标：
- 消息堆积量
- 消费延迟
- 死信队列消息数
- 重试队列消息数
- 生产/消费 TPS
```

---

## 八、面试答题话术（直接背）

**面试官问：如何保证 MQ 的可靠性？**

答：我从生产端、服务端、消费端三个环节来说：

**第一，生产端保证消息不丢失：**
1. 使用 **Confirm 机制**（RabbitMQ）或**同步发送**（RocketMQ），等待 Broker 确认
2. Kafka 配置 `acks=all`，保证所有副本确认
3. 配合**重试机制**，失败后自动重试
4. 关键业务用**本地消息表**，保证消息最终发出

**第二，服务端保证消息不丢失：**
1. **消息持久化**：RabbitMQ 设置 deliveryMode=2，Kafka 默认持久化，RocketMQ 同步刷盘
2. **多副本机制**：RabbitMQ 镜像队列，Kafka 多副本 + min.insync.replicas=2，RocketMQ 主从同步
3. **ACK 机制**：消费者确认后才删除消息

**第三，消费端保证消息不重不漏：**
1. **手动 ACK**：业务处理成功后再确认
2. **重试机制**：失败的消息进入重试队列，指数退避
3. **死信队列**：超过重试次数进入 DLQ，人工介入
4. **幂等消费**：Redis SETNX 或数据库唯一键去重

**总结**：核心是**生产端 Confirm、服务端持久化 + 多副本、消费端手动 ACK + 幂等**，三者缺一不可。

---

## 九、扩展问题

### Q1：消息积压了怎么办？

**紧急处理：**
1. 先扩容消费者，提升消费能力
2. 临时关闭非核心业务，集中资源消费
3. 如果积压太多，可以新建 Topic，10 倍队列数，写程序批量转发

**长期优化：**
1. 优化消费逻辑，提升消费速度
2. 增加消费者并发度
3. 评估是否需要拆分 Topic

### Q2：如何保证消息不重复消费？

**答案**：消费端做**幂等性设计**
- 数据库唯一键
- Redis SETNX 去重
- 状态机 CAS

### Q3：顺序消息如何保证？

**答案**：
- RabbitMQ：单队列单消费者
- Kafka：单 Partition 单消费者
- RocketMQ：队列有序 + 顺序消费监听器
