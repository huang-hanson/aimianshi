# 如果 MQ 出问题，系统如何降级？

## 一、核心结论

**MQ 降级核心思想：保业务、弃异步、限流量、兜底处理**

**降级原则：**
1. **核心业务优先**：保证下单、支付等核心流程
2. **非核心业务降级**：日志、通知、推荐等可延后
3. **快速切换**：秒级发现，秒级切换
4. **可恢复**：MQ 恢复后能快速补数据

---

## 二、MQ 故障场景分类

### 2.1 故障场景

```
┌─────────────────────────────────────────────────────────┐
│                  MQ 故障场景分类                         │
├─────────────────┬───────────────────────────────────────┤
│   故障类型      │            具体表现                   │
├─────────────────┼───────────────────────────────────────┤
│ Broker 宕机     │ 主节点挂掉，从节点未跟上              │
│ 网络分区        │ 生产者/消费者连不上 MQ                │
│ 消息大量堆积    │ 消费能力不足，队列爆满                │
│ 磁盘写满        │ 无法写入新消息                        │
│ 内存溢出        │ Broker OOM，服务不可用                │
│ 集群脑裂        │ 多节点数据不一致                      │
└─────────────────┴───────────────────────────────────────┘
```

### 2.2 故障等级

| 等级 | 影响范围 | 响应时间 | 降级策略 |
|------|---------|---------|---------|
| **P0** | 全部 MQ 不可用 | 立即 | 全面降级 |
| **P1** | 部分 Topic 不可用 | 5 分钟 | 局部降级 |
| **P2** | 性能下降 | 15 分钟 | 限流降级 |

---

## 三、降级方案总览

### 3.1 降级策略架构图

```
                         ┌─────────────────┐
                         │   请求入口      │
                         └────────┬────────┘
                                  │
         ┌────────────────────────┼────────────────────────┐
         │                        │                        │
         ▼                        ▼                        ▼
   ┌───────────┐           ┌───────────┐           ┌───────────┐
   │ 核心业务  │           │ 重要业务  │           │ 非核心    │
   │ 下单/支付 │           │ 库存/物流 │           │ 通知/统计 │
   └─────┬─────┘           └─────┬─────┘           └─────┬─────┘
         │                        │                        │
         ▼                        ▼                        ▼
   ┌───────────┐           ┌───────────┐           ┌───────────┐
   │ 同步执行  │           │ 本地队列  │           │ 直接丢弃  │
   │ 写数据库  │           │ 延迟重试  │           │ 或采样    │
   └───────────┘           └───────────┘           └───────────┘
```

---

## 四、具体降级方案

### 4.1 方案一：同步降级（核心业务）

**适用场景**：下单、支付、扣库存等核心流程

**降级思路**：MQ 不可用时，异步改同步，直接写数据库

**架构图**：
```
正常流程：
服务 ──► MQ ──► 消费者 ──► 数据库

降级流程：
服务 ──► 数据库（直接写）
```

**代码实现**：
```java
@Service
public class OrderService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderMapper orderMapper;

    // 正常情况：发送 MQ
    // 降级情况：直接写库
    public void createOrder(Order order) {
        // 1. 检查 MQ 是否可用
        if (MqHealthChecker.isAvailable()) {
            // 正常流程：发送 MQ
            rabbitTemplate.convertAndSend("order.queue", order);
            log.info("订单已发送到 MQ：{}", order.getId());
        } else {
            // 降级流程：直接写库
            orderMapper.insert(order);
            log.warn("MQ 不可用，降级直接写库：{}", order.getId());

            // 2. 记录降级日志，后续补偿
            MqFallbackRecorder.record("order.queue", order);
        }
    }
}
```

**MQ 健康检查**：
```java
@Component
public class MqHealthChecker {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 本地缓存 MQ 状态（避免每次都检查）
    private static volatile boolean mqAvailable = true;
    private static volatile long lastCheckTime = 0;

    @Scheduled(fixedRate = 5000) // 5 秒检查一次
    public void checkMqHealth() {
        try {
            rabbitTemplate.execute(channel -> {
                channel.queueDeclarePassive("health-check-queue");
                return null;
            });
            mqAvailable = true;
        } catch (Exception e) {
            mqAvailable = false;
        }
        lastCheckTime = System.currentTimeMillis();
    }

    public static boolean isAvailable() {
        // 如果超过 15 秒未检查，认为不可用
        if (System.currentTimeMillis() - lastCheckTime > 15000) {
            return false;
        }
        return mqAvailable;
    }
}
```

---

### 4.2 方案二：本地队列降级（重要业务）

**适用场景**：库存扣减、物流更新等重要但可短暂延迟的业务

**降级思路**：MQ 不可用时，消息存入本地队列，等 MQ 恢复后重发

**架构图**：
```
正常流程：
服务 ──► MQ ──► 消费者

降级流程：
服务 ──► 本地队列（内存/数据库）
            │
            ▼
      [定时任务扫描]
            │
            ▼
      MQ 恢复后重发
```

**代码实现**：
```java
@Component
public class MqFallbackSender {

    // 本地队列（内存版，简单场景）
    private final BlockingQueue<MqMessage> localQueue = new LinkedBlockingQueue<>(10000);

    // 数据库版（推荐，可持久化）
    @Autowired
    private MqFallbackMapper fallbackMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送消息（带降级）
     */
    public boolean send(String queue, Object message) {
        try {
            // 尝试发送 MQ
            rabbitTemplate.convertAndSend(queue, message);
            return true;
        } catch (Exception e) {
            log.error("MQ 发送失败，进入降级流程", e);

            // 降级：存入本地队列
            MqMessage mqMessage = new MqMessage(queue, message, System.currentTimeMillis());

            // 方案 1：内存队列（简单，重启丢失）
            if (localQueue.offer(mqMessage)) {
                log.info("消息已存入本地内存队列");
            }

            // 方案 2：数据库（推荐，可持久化）
            fallbackMapper.insert(mqMessage);
            log.info("消息已存入降级表");

            return false;
        }
    }

    /**
     * 定时任务：重发降级消息
     */
    @Scheduled(fixedRate = 10000) // 10 秒执行一次
    public void retryFallbackMessages() {
        // 检查 MQ 是否恢复
        if (!MqHealthChecker.isAvailable()) {
            return;
        }

        // 从数据库读取未重发的消息
        List<MqMessage> messages = fallbackMapper.selectUnsent(100);

        for (MqMessage msg : messages) {
            try {
                rabbitTemplate.convertAndSend(msg.getQueue(), msg.getContent());
                // 重发成功，更新状态
                fallbackMapper.updateStatus(msg.getId(), 1);
                log.info("降级消息重发成功：{}", msg.getId());
            } catch (Exception e) {
                log.error("降级消息重发失败：{}", msg.getId(), e);
            }
        }
    }
}

// 降级消息表
@Data
public class MqFallbackMessage {
    private Long id;
    private String queue;          // 目标队列
    private String content;        // 消息内容（JSON）
    private Long createTime;       // 创建时间
    private Integer status;        // 0-未发送 1-已发送
    private Integer retryCount;    // 重试次数
}
```

**数据库表结构**：
```sql
CREATE TABLE mq_fallback_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    queue VARCHAR(255) NOT NULL COMMENT '目标队列',
    content TEXT NOT NULL COMMENT '消息内容',
    create_time BIGINT NOT NULL COMMENT '创建时间',
    status TINYINT DEFAULT 0 COMMENT '0-未发送 1-已发送',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
);
```

---

### 4.3 方案三：限流降级（高并发场景）

**适用场景**：秒杀、抢购等流量洪峰场景

**降级思路**：MQ 处理能力不足时，限制进入系统的流量

**架构图**：
```
                    ┌─────────────┐
    请求 ──► 限流器 ──►│   通过：发 MQ  │
                    │   拒绝：返回 │
                    └─────────────┘
```

**代码实现**：
```java
@Component
public class MqRateLimiter {

    // 令牌桶限流器
    private final RateLimiter rateLimiter = RateLimiter.create(1000); // 每秒 1000 个令牌

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public boolean sendWithRateLimit(String queue, Object message) {
        // 1. 尝试获取令牌
        if (!rateLimiter.tryAcquire()) {
            // 限流：直接拒绝
            log.warn("MQ 限流，拒绝消息：{}", queue);

            // 可选：返回友好提示
            throw new RateLimitException("系统繁忙，请稍后重试");
        }

        // 2. 发送 MQ
        try {
            rabbitTemplate.convertAndSend(queue, message);
            return true;
        } catch (Exception e) {
            log.error("MQ 发送失败", e);
            return false;
        }
    }
}

// Sentinel 限流配置（推荐）
@RestController
@RequestMapping("/order")
public class OrderController {

    @PostMapping("/create")
    @SentinelResource(value = "createOrder",
            blockHandler = "handleBlock") // 限流回调
    public Result createOrder(@RequestBody Order order) {
        orderService.create(order);
        return Result.success();
    }

    // 限流回调方法
    public Result handleBlock(@RequestBody Order order, BlockException ex) {
        log.warn("订单创建被限流", ex);
        return Result.fail("系统繁忙，请稍后重试");
    }
}
```

---

### 4.4 方案四：消息丢弃（非核心业务）

**适用场景**：日志收集、数据统计、用户行为追踪等非核心业务

**降级思路**：MQ 不可用时，直接丢弃或采样保留

**策略选择**：

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| **全部丢弃** | 直接不处理 | 纯日志、埋点 |
| **采样保留** | 保留 1% 或 10% | 用户行为分析 |
| **本地缓存** | 写入本地文件 | 审计日志 |

**代码实现**：
```java
@Component
public class LogService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 方案 1：直接丢弃
     */
    public void logUserAction(UserAction action) {
        try {
            rabbitTemplate.convertAndSend("log.queue", action);
        } catch (Exception e) {
            // 非核心业务，直接丢弃
            log.debug("日志消息丢弃：{}", action.getType());
        }
    }

    /**
     * 方案 2：采样保留（10%）
     */
    private static final Random random = new Random();

    public void logUserBehavior(UserBehavior behavior) {
        try {
            rabbitTemplate.convertAndSend("behavior.queue", behavior);
        } catch (Exception e) {
            // 采样 10% 写入本地文件
            if (random.nextInt(10) == 0) {
                log.info("采样保存行为数据：{}", behavior.toJson());
                // 写入本地文件或数据库
                LocalBackup.save(behavior);
            }
        }
    }

    /**
     * 方案 3：写入本地文件
     */
    public void logAudit(AuditLog log) {
        try {
            rabbitTemplate.convertAndSend("audit.queue", log);
        } catch (Exception e) {
            // 审计日志必须保存，写本地文件
            log.warn("审计日志写入本地文件：{}", log.getId());
            FileAppender.append("audit.log", log.toJson());
        }
    }
}
```

---

### 4.5 方案五：服务熔断（Sentinel/Hystrix）

**适用场景**：MQ 持续不可用，避免资源浪费

**降级思路**：检测到 MQ 故障后，快速熔断，不再尝试发送

**架构图**：
```
         ┌─────────────┐
请求 ──► │ 熔断器       │
         │  closed     │ ──► 正常发送 MQ
         │  open       │ ──► 直接降级
         │  half-open  │ ──► 尝试恢复
         └─────────────┘
```

**代码实现（Sentinel）**：
```java
@Component
public class MqCircuitBreaker {

    // 熔断规则配置
    @PostConstruct
    public void init() {
        List<FlowRule> rules = new ArrayList<>();

        FlowRule rule = new FlowRule("mqSend");
        rule.setCount(100);  // QPS 阈值
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);

        // 熔断策略：异常比例
        List<DegradeRule> degradeRules = new ArrayList<>();
        DegradeRule degradeRule = new DegradeRule("mqSend")
                .setCount(0.5)      // 异常比例 50%
                .setTimeWindow(10)  // 熔断 10 秒
                .setStatIntervalMs(10000);
        degradeRules.add(degradeRule);

        DegradeRuleManager.loadRules(degradeRules);
    }

    @SentinelResource(value = "mqSend",
            fallback = "sendFallback",           // 异常降级
            blockHandler = "sendBlockHandler")   // 限流降级
    public void send(String queue, Object message) {
        rabbitTemplate.convertAndSend(queue, message);
    }

    // 异常降级方法
    public void sendFallback(String queue, Object message, Throwable ex) {
        log.error("MQ 异常降级：{}", queue, ex);
        // 降级逻辑：写数据库或本地队列
        fallbackMapper.insert(new MqMessage(queue, message));
    }

    // 限流降级方法
    public void sendBlockHandler(String queue, Object message, BlockException ex) {
        log.warn("MQ 限流降级：{}", queue);
        // 直接拒绝或返回友好提示
    }
}
```

---

## 五、降级策略对比表

| 方案 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| **同步降级** | 核心业务（下单/支付） | 数据不丢失，实时性强 | 增加数据库压力，响应变慢 |
| **本地队列** | 重要业务（库存/物流） | 可恢复，不丢数据 | 需要补偿机制，有延迟 |
| **限流降级** | 高并发（秒杀/抢购） | 保护系统，防止雪崩 | 部分用户请求被拒绝 |
| **消息丢弃** | 非核心（日志/统计） | 简单快速，无负担 | 数据丢失 |
| **服务熔断** | MQ 持续故障 | 快速失败，节省资源 | 需要配置熔断规则 |

---

## 六、生产环境降级实践

### 6.1 降级配置中心

**统一管理降级开关**：
```yaml
# Nacos 配置中心配置
mq:
  fallback:
    enabled: true                    # 是否开启降级
    strategy: "local_queue"          # 降级策略：sync/local_queue/limit/drop
    check-interval: 5000             # MQ 健康检查间隔 (ms)

    # 各业务降级配置
    business:
      order:                         # 订单业务
        strategy: "sync"             # 同步降级
      payment:                       # 支付业务
        strategy: "sync"
      inventory:                     # 库存业务
        strategy: "local_queue"
      logistics:                     # 物流业务
        strategy: "local_queue"
      notification:                  # 通知业务
        strategy: "drop"             # 直接丢弃
      analytics:                     # 分析业务
        strategy: "sample"           # 采样保留
        sample-rate: 0.1             # 10% 采样率
```

---

### 6.2 降级监控告警

**核心监控指标**：
```promql
# 降级触发次数
mq_fallback_trigger_total{business="order"}

# 降级队列积压量
mq_fallback_queue_size

# 降级消息重发成功率
mq_fallback_retry_success_rate

# MQ 可用率
mq_health_check_available
```

**告警规则**：
```yaml
# Alertmanager 告警配置
- alert: MqFallbackTriggered
  expr: rate(mq_fallback_trigger_total[1m]) > 10
  for: 1m
  annotations:
    summary: "MQ 降级触发频繁"
    description: "业务 {{ $labels.business }} 降级触发次数 {{ $value }}/秒"

- alert: MqFallbackQueueHigh
  expr: mq_fallback_queue_size > 10000
  for: 5m
  annotations:
    summary: "降级队列积压过多"
```

---

### 6.3 数据补偿机制

**降级后数据一致性保证**：
```java
@Component
public class DataCompensator {

    @Autowired
    private MqFallbackMapper fallbackMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 定时任务：补偿降级数据
     */
    @Scheduled(fixedRate = 60000) // 每分钟执行
    public void compensate() {
        // 1. 检查 MQ 是否恢复
        if (!MqHealthChecker.isAvailable()) {
            return;
        }

        // 2. 读取待补偿数据
        List<MqFallbackMessage> messages = fallbackMapper.selectUnsent(500);

        // 3. 批量重发
        for (MqFallbackMessage msg : messages) {
            try {
                rabbitTemplate.convertAndSend(msg.getQueue(), msg.getContent());
                fallbackMapper.delete(msg.getId());
                log.info("补偿消息发送成功：{}", msg.getId());
            } catch (Exception e) {
                log.error("补偿消息发送失败：{}", msg.getId(), e);

                // 重试次数过多，发送告警
                if (msg.getRetryCount() >= 10) {
                    alertService.send("补偿消息重试失败", msg);
                }
            }
        }
    }
}
```

---

## 七、面试答题话术（直接背）

**面试官问：如果 MQ 出问题，系统如何降级？**

答：我从降级原则、具体方案、生产实践三方面说：

**第一，降级原则：**
1. **保核心**：下单、支付等核心业务优先保证
2. **弃非核心**：日志、通知、统计等业务可降级
3. **快速切换**：秒级发现故障，秒级切换降级
4. **可恢复**：MQ 恢复后能快速补数据

**第二，5 种具体降级方案：**

1. **同步降级**（核心业务）：MQ 不可用时，异步改同步，直接写数据库。适用于下单、支付等核心流程。

2. **本地队列降级**（重要业务）：消息存入本地队列（内存或数据库），等 MQ 恢复后重发。适用于库存、物流等业务。

3. **限流降级**（高并发）：MQ 处理能力不足时，用令牌桶或 Sentinel 限流，保护系统不雪崩。适用于秒杀、抢购场景。

4. **消息丢弃**（非核心业务）：日志、统计等非核心业务，MQ 不可用时直接丢弃或采样保留。

5. **服务熔断**（MQ 持续故障）：用 Sentinel/Hystrix 熔断，快速失败，避免资源浪费。

**第三，生产实践：**
- **配置中心**：用 Nacos 统一管理降级开关
- **监控告警**：监控降级触发次数、队列积压量
- **数据补偿**：MQ 恢复后定时重发降级消息

**举例**：我们生产环境订单业务配置的是同步降级，MQ 故障时直接写订单表，等 MQ 恢复后异步发送消息更新其他系统。

---

## 八、扩展问题

### Q1：降级后数据不一致怎么办？

**答**：三种方案：
1. **本地队列 + 补偿**：降级消息存队列，MQ 恢复后重发
2. **数据库事务表**：降级时写事务表，定时任务扫描发送
3. **本地消息表**：业务数据和消息在同一事务写入，定时投递

---

### Q2：如何快速发现 MQ 故障？

**答**：
1. **健康检查**：定时探测 MQ 连通性（5 秒一次）
2. **发送失败监控**：监控发送异常率，超过阈值告警
3. **APM 监控**：SkyWalking/Prometheus 监控 MQ 指标
4. **业务指标**：消费延迟、消息堆积量突增

---

### Q3：本地队列用内存还是数据库？

**答**：
- **内存队列**：简单快速，但重启丢失，适用于可丢失场景
- **数据库**：可持久化，数据不丢，推荐用于重要业务
- **混合方案**：先写内存，满了再写磁盘/数据库

---

### Q4：降级后如何恢复？

**答**：
1. **自动恢复**：MQ 健康检查通过后，自动切回正常模式
2. **手动恢复**：运维确认 MQ 稳定后，手动切换开关
3. **灰度恢复**：先恢复部分流量，确认无问题再全量
