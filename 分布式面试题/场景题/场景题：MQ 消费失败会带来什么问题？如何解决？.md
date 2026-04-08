# 场景题：MQ 消费失败会带来什么问题？如何解决？

## 一、核心结论

**MQ 消费失败带来的问题：**
1. **数据不一致** - 生产者扣了库存，消费者没生成订单
2. **消息丢失** - 消费失败后消息被丢弃
3. **消息重复** - 重试导致重复消费
4. **消息堆积** - 消费失败导致消息积压
5. **系统雪崩** - 大量失败请求拖垮系统

**解决方案：**
1. **重试机制** - 网络抖动等临时问题自动恢复
2. **死信队列** - 无法消费的消息单独处理
3. **幂等性设计** - 防止重复消费
4. **补偿机制** - 对账修复不一致数据
5. **监控告警** - 及时发现问题

---

## 二、MQ 消费失败会带来什么问题？

### 问题 1：数据不一致 ⭐⭐⭐

#### 场景描述

```
订单支付流程：

生产者（支付系统）：
1. 扣用户余额 100 元 ✓
2. 发送"支付成功"消息到 MQ ✓

消费者（订单系统）：
1. 从 MQ 消费"支付成功"消息
2. 更新订单状态为"已支付" ✗（消费失败）

结果：
- 用户扣了 100 元
- 订单状态还是"未支付"
- 用户投诉：钱扣了订单没生效！
```

#### 数据流图

```
┌──────────┐  支付成功消息  ┌──────────┐
│  支付系统  │ ────────────► │    MQ     │
│ 扣款成功 ✓│               │           │
└──────────┘               └─────┬────┘
                                 │
                                 │ 消费失败 ✗
                                 ▼
                         ┌──────────┐
                         │  订单系统  │
                         │ 状态未更新 │
                         └──────────┘

数据不一致：支付系统扣款成功，订单系统状态未更新
```

#### 真实案例

```
某电商系统故障：
- 促销活动，10 万订单支付
- MQ 消费者代码有 bug，消费失败
- 没有重试机制，消息直接丢弃
- 结果：3 万用户扣了钱，订单没生成
- 客诉电话被打爆，公司损失 500 万+
```

---

### 问题 2：消息丢失 ⭐⭐⭐

#### 丢失场景

```
场景 1：消费失败直接 ACK

消费者代码：
public void onMessage(Message msg) {
    try {
        // 业务处理
        process(msg);
    } catch (Exception e) {
        // ✗ 错误：捕获异常后直接 ACK，消息丢失
        log.error("消费失败", e);
    }
}

结果：消息被确认消费，实际没处理，数据丢失
```

```
场景 2：消费者宕机

生产者 ──► MQ ──► 消费者
                    │
                    │ 处理中...
                    │
               消费者宕机 ✗
                    │
               消息未 ACK
                    │
               MQ 重新投递？
                    │
               如果配置不当，消息丢失
```

```
场景 3：重试次数用尽

消息消费失败
    │
    ▼
第 1 次重试 ──► 失败
    │
    ▼
第 2 次重试 ──► 失败
    │
    ▼
第 3 次重试 ──► 失败
    │
    ▼
达到最大重试次数 ──► 消息被丢弃 ✗
```

---

### 问题 3：消息重复消费 ⭐⭐⭐

#### 重复场景

```
场景 1：消费成功但 ACK 失败

消费者 ──► 处理消息成功 ✓
    │
    │ 发送 ACK 给 MQ
    │
    ✗ ACK 在网络中丢失
    │
    ▼
MQ 认为没消费成功
    │
    ▼
重新投递消息
    │
    ▼
消费者再次处理 ──► 重复消费 ✗
```

```
场景 2：重试机制导致重复

消费者处理消息
    │
    │ 业务处理成功 ✓
    │ 但更新 offset 失败 ✗
    │
    ▼
MQ 认为没消费
    │
    ▼
再次投递
    │
    ▼
重复消费 ✗
```

#### 重复消费后果

```
积分系统：
- 用户下单，送 100 积分
- 消息被消费 3 次
- 用户收到 300 积分（应该只有 100）
- 公司损失：积分被薅羊毛

库存系统：
- 订单取消，回滚库存
- 消息被消费 2 次
- 库存加了 2 次
- 库存数据错乱
```

---

### 问题 4：消息堆积 ⭐⭐

#### 堆积场景

```
正常情况：
生产：1000 条/秒
消费：1000 条/秒
队列长度：稳定在 1000 条

消费失败后：
生产：1000 条/秒
消费：500 条/秒（一半失败，需要重试）
队列长度：每秒增加 500 条

1 小时后：堆积 180 万条
1 天后：堆积 4320 万条

结果：
- MQ 磁盘爆满
- 消费延迟从秒级变成小时级
- 系统雪崩
```

#### 堆积影响

```
┌─────────────────────────────────────────┐
│  消息队列堆积趋势图                      │
│                                          │
│  正常：───────────────────────           │
│                                          │
│  开始堆积：/                             │
│           /                              │
│          /                               │
│         /                                │
│        /                                 │
│       /                                  │
│      /                                   │
│     /                                    │
│    /                                     │
│   /                                      │
│  /                                       │
│ /                                        │
│/                                         │
└─────────────────────────────────────────┘
  0h   2h   4h   6h   8h   10h  12h

12 小时后，堆积消息 2000 万条，MQ 磁盘爆满
```

---

### 问题 5：系统雪崩 ⭐

#### 雪崩链路

```
MQ 消费失败
    │
    ▼
消息重试（1 条变 3 条）
    │
    ▼
消费更慢，堆积更多
    │
    ▼
数据库连接被耗尽（重试查询）
    │
    ▼
正常请求无法获取连接
    │
    ▼
正常业务也失败
    │
    ▼
整个系统雪崩
```

#### 真实案例

```
某金融系统雪崩：

10:00  某个消费逻辑慢 SQL，响应从 50ms 变成 5s
10:01  消费速度下降，队列开始堆积
10:05  堆积 10 万条，消费者线程池满
10:10  数据库连接池耗尽
10:11  正常业务无法获取数据库连接
10:12  整个系统不可用
10:30  修复慢 SQL，系统恢复

损失：20 分钟服务不可用，影响 10 万用户
```

---

## 三、如何解决 MQ 消费失败问题？

### 方案 1：重试机制 ⭐⭐⭐

#### 重试策略

```java
/**
 * 重试配置示例（RocketMQ）
 */
@RocketMQMessageListener(
    topic = "order_created",
    consumerGroup = "order_consumer",
    maxReconsumeTimes = 5,      // 最大重试 5 次
    messageModel = MessageModel.CLUSTERING
)
public class OrderConsumer implements RocketMQListener<OrderMessage> {

    @Override
    public void onMessage(OrderMessage message) {
        // 业务处理
        orderService.createOrder(message);

        // 如果抛出异常，会自动重试
        // 重试间隔：1s, 5s, 10s, 30s, 1min, 2min, 5min, 10min, 20min, 30min, 1h, 2h...
    }
}
```

#### 重试间隔配置

```java
// RocketMQ 重试间隔（单位：秒）
private int[] delayLevel = {1, 5, 10, 30, 60, 120, 300, 600, 1200, 1800, 3600, 7200};

// 第 1 次重试：1 秒后
// 第 2 次重试：5 秒后
// 第 3 次重试：10 秒后
// ...
// 第 12 次重试：2 小时后
```

#### 什么时候用重试？

| 错误类型 | 是否重试 | 说明 |
| :--- | :--- | :--- |
| **网络抖动** | ✅ 重试 | 临时问题，重试可能成功 |
| **数据库连接超时** | ✅ 重试 | 临时问题，重试可能成功 |
| **业务校验失败** | ❌ 不重试 | 如库存不足，重试也没用 |
| **数据格式错误** | ❌ 不重试 | 消息本身有问题，重试没用 |
| **空指针异常** | ❌ 不重试 | 代码 bug，需要修复 |

#### 代码实现

```java
@Service
@RocketMQMessageListener(
    topic = "order_created",
    consumerGroup = "order_consumer"
)
public class OrderConsumer implements RocketMQListener<OrderMessage> {

    @Override
    public void onMessage(OrderMessage message) {
        try {
            // 业务处理
            orderService.createOrder(message);
        } catch (BusinessException e) {
            // 业务异常，不重试（如库存不足、数据校验失败）
            log.error("业务异常，不重试：{}", e.getMessage());
            // 直接 ACK，消息丢弃
        } catch (Exception e) {
            // 系统异常，抛出异常触发重试
            log.error("系统异常，触发重试", e);
            throw new RuntimeException(e);
        }
    }
}
```

---

### 方案 2：死信队列 ⭐⭐⭐

#### 死信队列原理

```
正常消费流程：

消息 ──► 消费者 ──► 成功 ──► ACK
              │
              │ 失败
              │
              ▼
         重试 1 次 ──► 成功 ──► ACK
              │
              │ 失败
              │
              ▼
         重试 2 次 ──► 成功 ──► ACK
              │
              │ 失败
              │
              ▼
         重试 N 次 ──► 还是失败
              │
              ▼
         发送到死信队列 ──► 人工处理
```

#### 死信队列配置

```java
/**
 * 死信队列配置
 */
@Configuration
public class DeadLetterQueueConfig {

    @Bean
    public Topic orderDeadLetterTopic() {
        // 创建死信队列 Topic
        return new Topic("order_created_DLQ");
    }
}

// RocketMQ 自动支持死信队列
// 格式：%DLQ% + consumerGroup
// 如：order_consumer 的死信队列是 %DLQ%order_consumer
```

#### 处理死信消息

```java
/**
 * 死信队列消费者
 */
@Service
@RocketMQMessageListener(
    topic = "%DLQ%order_consumer",  // 死信队列 Topic
    consumerGroup = "dlq_consumer"
)
public class DeadLetterConsumer implements RocketMQListener<Message> {

    @Autowired
    private AlertService alertService;

    @Override
    public void onMessage(Message message) {
        // 1. 记录告警
        alertService.sendAlert("死信消息", message);

        // 2. 记录到数据库，人工处理
        deadLetterMapper.insert(convertToEntity(message));

        // 3. 分析失败原因
        String reason = analyzeFailureReason(message);
        log.error("死信消息，失败原因：{}", reason);

        // 4. 不重试，直接 ACK（避免无限循环）
    }
}
```

#### 死信处理流程

```
死信消息处理流程：

1. 自动告警
   └─> 发送钉钉/企业微信通知

2. 记录数据库
   └─> 保存消息内容、失败原因、时间

3. 人工分析
   └─> 是 bug？修复后重新投递
   └─> 是脏数据？直接丢弃

4. 重新处理
   └─> 修复 bug 后，从数据库取出消息重新发送
```

---

### 方案 3：幂等性设计 ⭐⭐⭐

#### 为什么需要幂等性？

```
场景：消息重复消费

生产者发送：订单 ID=123，金额=100 元
    │
    ▼
MQ 投递 ──► 消费者处理成功 ✓
    │
    │ ACK 丢失
    │
    ▼
MQ 重新投递
    │
    ▼
消费者再次处理 ✗
    │
    ▼
用户收到 2 次积分（应该只有 1 次）
```

#### 幂等性方案 1：唯一键去重

```java
// 数据库唯一索引
CREATE UNIQUE INDEX uk_order_id ON orders(order_id);

// 消费者代码
@Service
public class OrderConsumer implements RocketMQListener<OrderMessage> {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public void onMessage(OrderMessage message) {
        try {
            // 插入订单，唯一索引保证幂等
            orderMapper.insert(message.getOrderId(), message.getAmount());
        } catch (DuplicateKeyException e) {
            // 重复消息，忽略
            log.info("重复消息，已处理过：{}", message.getOrderId());
        }
    }
}
```

#### 幂等性方案 2：状态检查

```java
@Service
public class OrderConsumer {

    @Override
    public void onMessage(OrderMessage message) {
        // 1. 查询当前状态
        Order order = orderMapper.selectById(message.getOrderId());

        // 2. 状态检查（幂等核心）
        if (order.getStatus() >= OrderStatus.PAID) {
            // 已经是已支付状态，不需要重复处理
            log.info("订单已支付，跳过：{}", message.getOrderId());
            return;
        }

        // 3. 更新状态
        orderMapper.updateStatus(message.getOrderId(), OrderStatus.PAID);
    }
}
```

#### 幂等性方案 3：Redis 分布式锁

```java
@Service
public class IdempotentConsumer {

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public void onMessage(OrderMessage message) {
        String lockKey = "order:process:" + message.getOrderId();

        // 1. 尝试获取锁（SETNX）
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", 10, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(locked)) {
            // 获取锁失败，说明正在处理
            log.info("消息正在处理，跳过：{}", message.getOrderId());
            return;
        }

        try {
            // 2. 处理业务
            processOrder(message);
        } finally {
            // 3. 释放锁（可选，让锁自然过期）
            // redisTemplate.delete(lockKey);
        }
    }
}
```

#### 幂等性方案 4：消息去重表

```java
// 去重表
CREATE TABLE message_dedup (
    message_id VARCHAR(64) PRIMARY KEY,
    consumer_group VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

@Service
public class DedupConsumer {

    @Autowired
    private MessageDedupMapper dedupMapper;

    @Override
    public void onMessage(OrderMessage message) {
        // 1. 检查是否已处理
        if (dedupMapper.exists(message.getMessageId())) {
            log.info("消息已处理：{}", message.getMessageId());
            return;
        }

        // 2. 处理业务
        processOrder(message);

        // 3. 记录已处理
        dedupMapper.insert(message.getMessageId(), "order_consumer");
    }
}
```

---

### 方案 4：补偿机制 ⭐⭐

#### 补偿场景

```
重试 5 次后还是失败 → 进入死信队列
    │
    │ 人工修复 bug
    │
    ▼
补偿任务重新处理死信消息
```

#### 补偿方案 1：定时对账

```java
/**
 * 定时对账任务
 */
@Component
public class ReconciliationTask {

    @Autowired
    private PaymentMapper paymentMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 每天凌晨 2 点对账
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void reconciliation() {
        // 1. 查询支付成功但订单未支付的记录
        List<Payment> payments = paymentMapper.selectPaidButOrderNotPaid();

        // 2. 补偿：重新发送消息
        for (Payment payment : payments) {
            log.info("对账发现不一致，补偿：{}", payment.getId());

            OrderMessage message = new OrderMessage(payment.getOrderId());
            rocketMQTemplate.send("order_created", message);
        }

        log.info("对账完成，共{}条不一致记录", payments.size());
    }
}
```

#### 补偿方案 2：本地消息表

```java
/**
 * 本地消息表
 */
@Data
public class LocalMessage {
    private String id;
    private String topic;
    private String message;
    private Integer status;  // 0-待发送，1-已发送，2-已完成
    private Integer retryCount;
    private Date createTime;
}

/**
 * 事务 + 本地消息表
 */
@Service
public class PaymentService {

    @Autowired
    private LocalMessageMapper messageMapper;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Transactional
    public void pay(Order order) {
        // 1. 本地事务：扣款
        paymentMapper.deduct(order.getUserId(), order.getAmount());

        // 2. 本地事务：保存消息
        LocalMessage message = new LocalMessage();
        message.setTopic("payment_success");
        message.setMessage(order.getId().toString());
        message.setStatus(0);
        messageMapper.insert(message);
    }

    /**
     * 定时任务：发送本地消息
     */
    @Scheduled(fixedRate = 5000)
    public void sendPendingMessages() {
        List<LocalMessage> messages = messageMapper.selectPending();

        for (LocalMessage msg : messages) {
            // 发送消息
            rocketMQTemplate.send(msg.getTopic(), msg.getMessage());

            // 更新状态
            msg.setStatus(2);
            messageMapper.update(msg);
        }
    }
}
```

#### 补偿方案 3：MQ 事务消息

```java
/**
 * 事务消息监听器
 */
@RocketMQTransactionListener
public class TransactionListenerImpl implements RocketMQLocalTransactionListener {

    @Autowired
    private PaymentMapper paymentMapper;

    /**
     * 执行本地事务
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            // 1. 执行本地事务（扣款）
            Order order = (Order) arg;
            paymentMapper.deduct(order.getUserId(), order.getAmount());

            // 2. 提交事务
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            // 3. 回滚
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 事务回查（当 MQ 没收到确认时）
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // 查询本地事务状态
        String orderId = msg.getHeaders().get("order_id").toString();
        Payment payment = paymentMapper.selectByOrderId(orderId);

        if (payment != null && payment.getStatus() == PAID) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        return RocketMQLocalTransactionState.ROLLBACK;
    }
}
```

---

### 方案 5：监控告警 ⭐⭐

#### 监控指标

```java
/**
 * 监控配置
 */
@Configuration
public class MonitorConfig {

    /**
     * 消费延迟监控
     */
    @Bean
    public ConsumerLagMonitor consumerLagMonitor() {
        return new ConsumerLagMonitor(
            "order_consumer",
            10000,  // 延迟超过 1 万条告警
            "dingtalk_alert"
        );
    }

    /**
     * 消费失败率监控
     */
    @Bean
    public ConsumeFailRateMonitor failRateMonitor() {
        return new ConsumeFailRateMonitor(
            "order_consumer",
            0.05,  // 失败率超过 5% 告警
            "dingtalk_alert"
        );
    }

    /**
     * 死信队列监控
     */
    @Bean
    public DeadLetterMonitor dlqMonitor() {
        return new DeadLetterMonitor(
            "%DLQ%order_consumer",
            100,  // 死信超过 100 条告警
            "dingtalk_alert"
        );
    }
}
```

#### 告警规则

| 指标 | 阈值 | 告警级别 | 处理方式 |
| :--- | :--- | :--- | :--- |
| 消费延迟 | > 1 万条 | 警告 | 关注 |
| 消费延迟 | > 10 万条 | 严重 | 立即处理 |
| 失败率 | > 5% | 警告 | 关注 |
| 失败率 | > 20% | 严重 | 立即处理 |
| 死信数量 | > 100 条 | 警告 | 分析原因 |
| 死信数量 | > 1000 条 | 严重 | 立即处理 |

#### 告警通知

```java
/**
 * 告警通知服务
 */
@Service
public class AlertService {

    @Autowired
    private DingTalkClient dingTalkClient;

    /**
     * 发送告警
     */
    public void sendAlert(String title, String content) {
        DingTalkMessage message = new DingTalkMessage();
        message.setTitle(title);
        message.setContent(content);
        message.setReceivers(Arrays.asList("张三", "李四"));

        dingTalkClient.send(message);

        // 同时记录日志
        log.error("【告警】{} - {}", title, content);
    }
}
```

---

## 四、完整解决方案架构图

```
                        MQ 消费失败处理完整流程

┌─────────────────────────────────────────────────────────────────┐
│                         生产者                                   │
│  ┌─────────────┐                                               │
│  │  事务消息   │  ← 保证消息发送成功                            │
│  └─────────────┘                                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                           MQ                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │  消息持久化  │  │  重试队列   │  │  死信队列   │             │
│  └─────────────┘  └─────────────┘  └─────────────┘             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         消费者                                   │
│  ┌─────────────┐                                               │
│  │  幂等检查   │  ← 防止重复消费                               │
│  │  (唯一键/状态检查/Redis 锁)                                  │
│  └──────┬──────┘                                               │
│         │                                                       │
│  ┌──────▼──────┐                                               │
│  │  业务处理   │                                               │
│  └──────┬──────┘                                               │
│         │                                                       │
│    ┌────┴────┐                                                 │
│    │         │                                                 │
│    ▼         ▼                                                 │
│  成功      失败                                                │
│    │         │                                                 │
│    │    ┌────┴────┐                                           │
│    │    │         │                                           │
│    │    ▼         ▼                                           │
│    │  可重试    不可重试                                       │
│    │    │         │                                           │
│    │    ▼         ▼                                           │
│    │  重试     死信队列                                       │
│    │  (5 次)     + 告警                                        │
│    │                                                           │
│    └────────► ACK ◄─────────┐                                 │
│                              │                                 │
│                         ┌────┴────┐                           │
│                         │  监控   │                           │
│                         │  告警   │                           │
│                         └────┬────┘                           │
│                              │                                 │
│                         ┌────▼────┐                           │
│                         │ 补偿任务 │                           │
│                         │ 定时对账 │                           │
│                         └─────────┘                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 五、核心对比表

| 问题 | 影响 | 解决方案 |
| :--- | :--- | :--- |
| **数据不一致** | 用户投诉、资金损失 | 重试 + 事务消息 + 对账补偿 |
| **消息丢失** | 数据丢失、业务中断 | 持久化 + ACK 机制 + 死信队列 |
| **消息重复** | 数据错乱、资损风险 | 幂等性设计（唯一键/状态检查） |
| **消息堆积** | 延迟增加、系统雪崩 | 监控告警 + 扩容消费者 |
| **系统雪崩** | 服务不可用 | 限流 + 降级 + 熔断 |

---

## 六、面试答题话术（直接背）

**面试官问：MQ 消费失败会带来什么问题？如何解决？**

答：我从两个方面回答：

**第一，消费失败带来的问题有 5 个：**

1. **数据不一致**：生产者扣了钱，消费者没生成订单，用户投诉钱扣了订单没生效。

2. **消息丢失**：消费失败后直接 ACK，或者重试次数用尽被丢弃，消息就丢了。

3. **消息重复**：消费成功但 ACK 丢失，MQ 重新投递，导致重复消费，如积分发了 2 次。

4. **消息堆积**：消费失败导致消息积压，延迟从秒级变成小时级，甚至 MQ 磁盘爆满。

5. **系统雪崩**：大量重试拖慢消费速度，数据库连接耗尽，正常业务也失败。

**第二，解决方案有 5 个：**

1. **重试机制**：网络抖动等临时问题，自动重试 5 次，重试间隔从 1 秒到 2 小时递增。但业务异常（如库存不足）不重试。

2. **死信队列**：重试 5 次还是失败的消息，发送到死信队列，记录告警，人工分析处理。

3. **幂等性设计**：防止重复消费，用唯一索引、状态检查、Redis 分布式锁或消息去重表。

4. **补偿机制**：定时对账修复不一致数据，或者用本地消息表、事务消息保证最终一致性。

5. **监控告警**：监控消费延迟、失败率、死信数量，超过阈值立即告警，及时处理。

**举个例子**：我们当时订单系统消费失败没有重试，导致 3 万订单没生成。后来加了重试机制（5 次）、死信队列（失败消息人工处理）、幂等性设计（唯一索引防重复）、定时对账（每天凌晨 2 点修复不一致数据），从此再也没出过问题。
