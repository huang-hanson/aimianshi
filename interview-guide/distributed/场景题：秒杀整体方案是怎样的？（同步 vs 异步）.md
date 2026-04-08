# 场景题：秒杀整体方案是怎样的？（同步 vs 异步）

## 一、核心结论

**秒杀的核心挑战：** 瞬时高并发（10 万 + QPS）vs 有限库存（100-1000 件）

**整体方案架构：**

```
用户请求 → 网关限流 → CDN 静态资源 → 缓存预减库存 → MQ 异步下单 → 数据库最终扣减
```

**同步 vs 异步选择：**
- **同步方案**：简单直接，但并发低（1000 QPS），适合小流量场景
- **异步方案**：复杂但并发高（10 万 + QPS），适合大流量秒杀场景

**推荐方案：** **全链路异步化**（网关限流 + 缓存预扣 +MQ 削峰 + 异步下单）

---

## 二、秒杀系统核心难点

### 难点 1：瞬时高并发

```
正常电商系统：
- 日均 QPS：1000
- 峰值 QPS：5000

秒杀活动（双 11 零点）：
- 瞬时 QPS：10 万 +
- 持续时间：1-5 分钟
- 库存：100-1000 件

问题：99% 的请求最终都会失败，但要承受 100 倍流量冲击
```

### 难点 2：超卖问题

```
库存：100 件
请求：10 万 +

如果处理不当：
- 并发查询库存都是 100
- 并发扣减库存都成功
- 实际卖出 1000 件，超卖 900 件！
```

### 难点 3：响应时间

```
用户期望：点击后 1 秒内知道结果
系统压力：10 万 QPS 下要保证响应时间

矛盾：
- 同步处理：数据库扛不住，响应慢
- 异步处理：用户体验好，但系统复杂
```

---

## 三、同步方案 vs 异步方案

### 方案 1：同步秒杀方案 ⭐

#### 架构流程

```
用户请求
    │
    ▼
┌─────────────┐
│  网关限流   │  ← 第一道防线
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  业务服务   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  数据库     │  ← 直接操作数据库
│  (行锁)     │
└──────┬──────┘
       │
       ▼
  返回结果
```

#### 代码实现

```java
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 同步秒杀（不推荐）
     */
    @PostMapping("/sync/{id}")
    public Result seckillSync(@PathVariable Long id, @RequestParam Long userId) {
        // 直接同步调用
        SeckillResult result = seckillService.seckill(id, userId);
        return Result.success(result);
    }
}

@Service
public class SeckillService {

    @Autowired
    private SeckillMapper seckillMapper;

    /**
     * 同步秒杀逻辑
     */
    @Transactional
    public SeckillResult seckill(Long seckillId, Long userId) {
        // 1. 检查秒杀时间
        Seckill seckill = seckillMapper.selectById(seckillId);
        if (seckill.getStartTime().after(new Date())) {
            return SeckillResult.fail("秒杀未开始");
        }
        if (seckill.getEndTime().before(new Date())) {
            return SeckillResult.fail("秒杀已结束");
        }

        // 2. 检查库存（行锁）
        int stock = seckillMapper.checkStock(seckillId);
        if (stock <= 0) {
            return SeckillResult.fail("库存不足");
        }

        // 3. 扣减库存（带行锁的 UPDATE）
        int affected = seckillMapper.decreaseStock(seckillId);
        if (affected == 0) {
            return SeckillResult.fail("库存不足");
        }

        // 4. 创建订单
        Order order = new Order();
        order.setUserId(userId);
        order.setSeckillId(seckillId);
        orderMapper.insert(order);

        return SeckillResult.success(order.getId());
    }
}
```

#### SQL 实现

```sql
-- 扣减库存（带行锁）
UPDATE seckill
SET stock = stock - 1
WHERE id = #{seckillId}
  AND stock > 0;  -- 关键：条件判断，防止超卖

-- 返回影响行数，如果为 0 表示库存不足
```

#### 优缺点分析

| 优点 | 缺点 |
| :--- | :--- |
| 实现简单，代码直观 | 并发能力低（1000 QPS 左右） |
| 实时返回结果，用户体验好 | 数据库压力大，容易成为瓶颈 |
| 数据一致性好 | 响应时间不稳定，可能超时 |
| 不需要额外组件 | 无法应对 10 万 + QPS |

#### 适用场景

```
✅ 小规模秒杀（库存 < 100，预期 QPS < 1000）
✅ 内部测试、灰度活动
✅ 对实时性要求极高的场景

❌ 大规模秒杀（库存 > 1000，预期 QPS > 1 万）
❌ 双 11、618 等大促活动
```

---

### 方案 2：异步秒杀方案 ⭐⭐⭐（推荐）

#### 架构流程

```
用户请求
    │
    ▼
┌─────────────┐
│  CDN 静态化  │  ← 页面静态资源走 CDN
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  网关限流   │  ← 第一道防线：限流
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Redis 预减  │  ← 第二道防线：缓存预扣
│  库存       │
└──────┬──────┘
       │ 预扣成功
       ▼
┌─────────────┐
│  发送 MQ    │  ← 第三道防线：消息队列削峰
└──────┬──────┘
       │
       ▼
  返回排队中     ← 用户立即得到响应

       │
       ▼ (异步处理)
┌─────────────┐
│  MQ 消费者   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  数据库下单  │  ← 慢速但安全
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  通知用户   │  ← WebSocket/轮询
└─────────────┘
```

#### 代码实现

```java
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 异步秒杀（推荐）
     */
    @PostMapping("/async/{id}")
    public Result seckillAsync(@PathVariable Long id,
                                @RequestParam Long userId) {
        // 1. 预检查（时间、资格等）
        Seckill seckill = seckillService.getSeckill(id);
        if (!seckillService.isEligible(userId, seckill)) {
            return Result.fail("不符合秒杀资格");
        }

        // 2. Redis 预减库存
        boolean success = seckillService.preDecreaseStock(id);
        if (!success) {
            return Result.fail("库存不足");
        }

        // 3. 发送 MQ 消息，异步下单
        SeckillMessage message = new SeckillMessage(userId, id);
        rocketMQTemplate.send("seckill_order", message);

        // 4. 立即返回，告知用户排队中
        return Result.success("排队中，请稍后查看结果");
    }

    /**
     * 查询秒杀结果
     */
    @GetMapping("/result/{id}")
    public Result getSeckillResult(@PathVariable Long id,
                                    @RequestParam Long userId) {
        // 轮询查询结果
        Order order = orderService.getOrderByUserIdAndSeckillId(userId, id);
        if (order != null) {
            return Result.success(order);
        }
        return Result.processing("处理中");
    }
}

@Service
public class SeckillService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private SeckillMapper seckillMapper;

    /**
     * Redis 预减库存
     */
    public boolean preDecreaseStock(Long seckillId) {
        String key = "seckill:stock:" + seckillId;

        // Lua 脚本保证原子性
        String script = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then
                return -1  -- 库存不存在
            end
            if stock <= 0 then
                return 0   -- 库存不足
            end
            redis.call('DECR', KEYS[1])
            return 1       -- 预扣成功
        """;

        Long result = (Long) redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(key)
        );

        return result == 1;
    }
}

/**
 * MQ 消费者 - 异步下单
 */
@RocketMQMessageListener(
    topic = "seckill_order",
    consumerGroup = "seckill_consumer"
)
public class SeckillConsumer implements RocketMQListener<SeckillMessage> {

    @Autowired
    private OrderService orderService;

    @Override
    public void onMessage(SeckillMessage message) {
        try {
            // 异步创建订单（慢但安全）
            orderService.createOrder(message.getUserId(), message.getSeckillId());
        } catch (Exception e) {
            // 下单失败，回滚 Redis 库存
            orderService.rollbackStock(message.getSeckillId());
        }
    }
}
```

#### 初始化预热

```java
@Component
public class SeckillWarmUp {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private SeckillMapper seckillMapper;

    /**
     * 秒杀开始前预热库存到 Redis
     */
    @PostConstruct
    public void warmUp() {
        List<Seckill> seckills = seckillMapper.selectActiveSeckills();

        for (Seckill seckill : seckills) {
            String key = "seckill:stock:" + seckill.getId();
            redisTemplate.opsForValue().set(key, seckill.getStock());
        }
    }
}
```

#### 优缺点分析

| 优点 | 缺点 |
| :--- | :--- |
| 并发能力高（10 万 + QPS） | 实现复杂，需要多个组件配合 |
| 数据库压力小（MQ 削峰） | 用户不能实时知道结果 |
| 响应时间稳定 | 需要处理消息丢失、重复消费 |
| 可扩展性好 | 需要额外的 Redis、MQ 组件 |

#### 适用场景

```
✅ 大规模秒杀（库存 > 1000，预期 QPS > 1 万）
✅ 双 11、618 等大促活动
✅ 高并发场景

❌ 小规模秒杀（杀鸡用牛刀）
❌ 对实时性要求极高的场景
```

---

## 四、同步 vs 异步 核心对比

### 性能对比

| 指标 | 同步方案 | 异步方案 |
| :--- | :--- | :--- |
| **并发能力** | 1000 QPS | 10 万 + QPS |
| **响应时间** | 500ms - 2s | 50ms（返回排队） |
| **数据库压力** | 高（直接打库） | 低（MQ 削峰） |
| **成功率** | 低（数据库瓶颈） | 高（层层过滤） |

### 架构复杂度对比

| 维度 | 同步方案 | 异步方案 |
| :--- | :--- | :--- |
| **代码复杂度** | 低（简单直接） | 高（多组件配合） |
| **依赖组件** | 数据库 | Redis + MQ + 数据库 |
| **数据一致性** | 强一致 | 最终一致 |
| **运维成本** | 低 | 高 |

### 流量过滤对比

```
同步方案流量走向：
10 万请求 → 数据库 → 99900 失败 + 100 成功
           ↑
      数据库压力大！

异步方案流量走向：
10 万请求
    │
    ├─→ 网关限流 → 拒绝 5 万
    │
    ├─→ Redis 预扣 → 失败 4 万
    │
    ├─→ MQ 削峰 → 剩余 1 万慢慢处理
    │
    └─→ 数据库 → 100 成功
```

---

## 五、秒杀系统核心优化点

### 优化 1：页面静态化 ⭐⭐

**问题：** 动态页面每次请求都要查询数据库

**方案：** 秒杀页面静态化，部署到 CDN

```html
<!-- 秒杀页面静态化 -->
<!-- 部署到 CDN，用户直接从 CDN 获取 -->
<!DOCTYPE html>
<html>
<head>
    <title>iPhone 15 秒杀</title>
</head>
<body>
    <div id="countdown">距离秒杀开始：00:05:00</div>
    <button id="seckillBtn" disabled>秒杀即将开始</button>

    <script>
        // 倒计时
        setInterval(updateCountdown, 1000);

        // 秒杀按钮点击
        $('#seckillBtn').click(function() {
            $.ajax({
                url: '/seckill/async/123',
                method: 'POST',
                data: { userId: 1001 },
                success: function(result) {
                    // 返回排队中，轮询结果
                    pollResult();
                }
            });
        });

        // 轮询结果
        function pollResult() {
            setInterval(function() {
                $.get('/seckill/result/123?userId=1001', function(data) {
                    if (data.status === 'success') {
                        alert('秒杀成功！');
                    }
                });
            }, 1000);
        }
    </script>
</body>
</html>
```

**效果：** 90% 的静态资源请求被 CDN 拦截

---

### 优化 2：网关限流 ⭐⭐⭐

**方案：** Nginx + Sentinel 多层限流

```nginx
# Nginx 限流配置
# 1. IP 限流：单 IP 每秒 10 次请求
limit_req_zone $binary_remote_addr zone=ip_limit:10m rate=10r/s;

# 2. 接口限流：秒杀接口每秒 1 万次
limit_req_zone $uri zone=api_limit:10m rate=10000r/s;

server {
    location /seckill/ {
        # IP 限流
        limit_req zone=ip_limit burst=20 nodelay;

        # 接口限流
        limit_req zone=api_limit burst=1000 nodelay;

        proxy_pass http://backend;
    }
}
```

```java
// Sentinel 限流
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @SentinelResource(
        value = "seckill",
        blockHandler = "handleBlock"
    )
    @PostMapping("/async/{id}")
    public Result seckill(@PathVariable Long id,
                          @RequestParam Long userId) {
        // ...
    }

    // 限流处理方法
    public Result handleBlock(Long id, Long userId, BlockException ex) {
        return Result.fail("人太多了，请稍后再试");
    }
}
```

**效果：** 拦截 50% + 的恶意请求和刷单请求

---

### 优化 3：Redis 预减库存 ⭐⭐⭐

**方案：** Lua 脚本保证原子性

```java
@Service
public class SeckillStockService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 预减库存（Lua 脚本保证原子性）
     */
    public boolean decreaseStock(Long seckillId) {
        String key = "seckill:stock:" + seckillId;

        String script = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then
                return -1  -- 库存不存在
            end
            if stock <= 0 then
                return 0   -- 库存不足
            end
            redis.call('DECR', KEYS[1])
            return 1       -- 成功
        """;

        Long result = redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(key)
        );

        if (result == -1) {
            throw new BusinessException("秒杀不存在");
        }
        return result == 1;
    }

    /**
     * 回滚库存（下单失败时）
     */
    public void rollbackStock(Long seckillId) {
        String key = "seckill:stock:" + seckillId;
        redisTemplate.opsForValue().increment(key);
    }
}
```

**效果：** 拦截 90% + 的无效请求，数据库只处理真正有希望的请求

---

### 优化 4：MQ 削峰填谷 ⭐⭐

**方案：** RocketMQ 异步下单

```java
/**
 * MQ 消息定义
 */
@Data
@AllArgsConstructor
public class SeckillMessage {
    private Long userId;
    private Long seckillId;
    private LocalDateTime createTime;
}

/**
 * 发送 MQ 消息
 */
@Service
public class SeckillMQService {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    public void sendSeckillMessage(SeckillMessage message) {
        // 顺序消息：保证同一用户的订单顺序处理
        rocketMQTemplate.syncSendOrderly(
            "seckill_order",
            message,
            String.valueOf(message.getUserId())  // 按用户 ID 哈希
        );
    }
}

/**
 * 消费者 - 异步下单
 */
@RocketMQMessageListener(
    topic = "seckill_order",
    consumerGroup = "seckill_order_group",
    consumeThreadMax = 50  // 控制并发度
)
public class SeckillOrderConsumer implements RocketMQListener<SeckillMessage> {

    @Autowired
    private OrderService orderService;

    @Override
    public void onMessage(SeckillMessage message) {
        try {
            // 创建订单
            orderService.createOrder(message.getUserId(), message.getSeckillId());

            // 通知用户成功
            notifySuccess(message.getUserId());
        } catch (Exception e) {
            // 下单失败，回滚库存
            orderService.rollbackStock(message.getSeckillId());

            // 通知用户失败
            notifyFail(message.getUserId());

            // 重试或记录死信
            throw new RuntimeException("下单失败", e);
        }
    }
}
```

**效果：** 数据库 QPS 从 10 万降到 1000，平滑处理

---

### 优化 5：防止超卖 ⭐⭐⭐

**方案：** 数据库乐观锁 + Redis 预扣双重保障

```sql
-- 方案 1：乐观锁（version）
UPDATE seckill
SET stock = stock - 1, version = version + 1
WHERE id = #{seckillId}
  AND stock > 0
  AND version = #{version};  -- 版本号检查

-- 方案 2：条件判断（推荐）
UPDATE seckill
SET stock = stock - 1
WHERE id = #{seckillId}
  AND stock > 0;  -- 关键：条件判断

-- 返回影响行数，如果为 0 表示库存不足
```

```java
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 创建订单（防止超卖）
     */
    @Transactional
    public void createOrder(Long userId, Long seckillId) {
        // 1. 检查是否重复下单
        Order exists = orderMapper.getByUserIdAndSeckillId(userId, seckillId);
        if (exists != null) {
            throw new BusinessException("已下单，请勿重复提交");
        }

        // 2. 数据库扣减库存
        int affected = seckillMapper.decreaseStock(seckillId);
        if (affected == 0) {
            throw new BusinessException("库存不足");
        }

        // 3. 创建订单
        Order order = new Order();
        order.setUserId(userId);
        order.setSeckillId(seckillId);
        orderMapper.insert(order);
    }
}
```

**效果：** 100% 防止超卖

---

### 优化 6：防止重复下单 ⭐⭐

**方案：** 唯一索引 + Redis 分布式锁

```sql
-- 数据库唯一索引
CREATE UNIQUE INDEX uk_user_seckill
ON orders(user_id, seckill_id);
```

```java
@Service
public class SeckillService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 防止重复下单（Redis 分布式锁）
     */
    public Result seckill(Long userId, Long seckillId) {
        String lockKey = "seckill:lock:" + userId + ":" + seckillId;

        // 尝试获取锁
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(locked)) {
            return Result.fail("请勿重复提交");
        }

        try {
            // 秒杀逻辑
            return doSeckill(userId, seckillId);
        } finally {
            // 释放锁（Lua 脚本保证原子性）
            String unlockScript = """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                else
                    return 0
                end
            """;
            redisTemplate.execute(
                new DefaultRedisScript<>(unlockScript, Long.class),
                Collections.singletonList(lockKey),
                "1"
            );
        }
    }
}
```

**效果：** 100% 防止重复下单

---

## 六、完整秒杀时序图

```
用户          网关         服务          Redis        MQ          数据库
 │            │            │            │            │            │
 │──请求─────>│            │            │            │            │
 │            │──限流检查─>│            │            │            │
 │            │            │            │            │            │
 │<─拒绝──────│ (超出限制)  │            │            │            │
 │            │            │            │            │            │
 │──请求─────>│            │            │            │            │
 │            │──通过─────>│            │            │            │
 │            │            │──预扣库存─>│            │            │
 │            │            │<─成功──────│            │            │
 │            │            │            │            │            │
 │            │            │──发送 MQ──>│            │            │
 │<─排队中────│────────────│            │            │            │
 │            │            │            │            │            │
 │            │            │            │            │──消费 MQ──>│
 │            │            │            │            │            │──扣库存─>│
 │            │            │            │            │            │<─成功────│
 │            │            │            │            │<─成功──────│            │
 │            │            │            │            │            │            │
 │<─成功通知──│───────────────────────────────────────────────────│            │
 │            │            │            │            │            │
```

---

## 七、面试答题话术（直接背）

**面试官问：秒杀整体方案是怎样的？同步和异步怎么选？**

答：我从三个方面回答：

**第一，秒杀的核心挑战：**

秒杀的核心是**瞬时高并发 vs 有限库存**的矛盾。比如双 11 零点，10 万 + QPS 涌入，但库存只有 100 件，99.9% 的请求都会失败，但系统要承受 100 倍的流量冲击。

**第二，同步方案 vs 异步方案：**

**同步方案**是请求直接打到数据库，用行锁保证不超卖。优点是简单、实时返回结果；缺点是并发低（1000 QPS），数据库压力大。适合小规模秒杀。

**异步方案**是全链路异步化：
1. **页面静态化**：静态资源走 CDN，拦截 90% 流量
2. **网关限流**：Nginx + Sentinel 多层限流，拦截 50% + 恶意请求
3. **Redis 预扣**：Lua 脚本原子性预减库存，拦截 90% + 无效请求
4. **MQ 削峰**：异步下单，数据库 QPS 从 10 万降到 1000
5. **结果通知**：WebSocket 或轮询通知用户

异步方案并发高（10 万 + QPS），数据库压力小，但实现复杂，用户不能实时知道结果。

**第三，怎么选：**

小规模秒杀（库存 < 100，QPS < 1000）用同步方案，简单高效。

大规模秒杀（库存 > 1000，QPS > 1 万）用异步方案，虽然复杂但能抗住流量。

**核心优化点**：页面静态化、网关限流、Redis 预扣、MQ 削峰、唯一索引防重、乐观锁防超卖。

**举个例子**：我们当时双 11 秒杀，用的就是异步方案。页面部署到 CDN，网关限流 1 万 QPS，Redis 预扣库存，MQ 异步下单。最终抗住了 10 万 QPS，数据库实际 QPS 只有 1000，没有超卖，没有宕机。
