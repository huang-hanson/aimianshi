# 场景题：Redis + Lua 在秒杀中的作用？如何保证原子性、防超卖、一人一单？

## 一、核心结论

**Redis + Lua 的核心作用：** 将多个 Redis 操作合并为一个**原子操作**，避免并发问题。

**为什么用 Lua 脚本：**
- **原子性**：Lua 脚本在 Redis 中是单线程执行的，不会被中断
- **高性能**：一次网络请求完成多个操作，减少网络往返
- **数据一致性**：避免并发导致的数据不一致

**秒杀中的三大作用：**
1. **保证原子性**：查询库存 + 扣减库存原子执行
2. **防止超卖**：库存判断和扣减在同一脚本中完成
3. **一人一单**：用户下单资格检查原子执行

---

## 二、为什么需要原子性？（问题分析）

### 问题场景：没有 Lua 脚本时

```java
// ❌ 错误示例：分两步操作，存在并发问题
public boolean seckill(Long seckillId) {
    String key = "seckill:stock:" + seckillId;

    // 步骤 1：查询库存
    Integer stock = (Integer) redisTemplate.opsForValue().get(key);
    if (stock == null || stock <= 0) {
        return false;  // 库存不足
    }

    // ⚠️ 问题：这里可能发生并发！
    // 线程 A 查询 stock=10
    // 线程 B 查询 stock=10
    // 线程 A 扣减 stock=9
    // 线程 B 扣减 stock=8
    // ... 两个线程都成功了，但实际只有 10 件库存

    // 步骤 2：扣减库存
    redisTemplate.opsForValue().decrement(key);
    return true;
}
```

### 并发问题图解

```
时间    线程 A                    线程 B
│
├─→ GET stock = 10
│
│                       ├─→ GET stock = 10
│                       │
├─→ DECR stock = 9
│
│                       ├─→ DECR stock = 8
│
└─→ 都成功了！但库存只有 10 件，卖出了 20 件 → 超卖！
```

### 问题根源

```
1. 查询和扣减是两次独立的 Redis 操作
2. 两次操作之间有其他线程插入
3. 没有原子性保证

解决方案：用 Lua 脚本将两步合并为一步原子操作
```

---

## 三、Lua 脚本保证原子性 ⭐⭐⭐

### Lua 脚本核心原理

```
Lua 脚本在 Redis 中的执行特性：
1. 整个脚本作为一个命令执行
2. 执行期间不会被其他命令打断
3. 要么全部成功，要么全部失败
4. 天然保证原子性
```

### 基础示例：原子扣减库存

```lua
-- Lua 脚本：原子扣减库存
-- 参数：KEYS[1] = 库存 key
-- 返回值：1 = 成功，0 = 失败，-1 = 库存不存在

-- 1. 获取当前库存
local stock = tonumber(redis.call('GET', KEYS[1]))

-- 2. 判断库存是否存在
if stock == nil then
    return -1  -- 库存不存在（未初始化）
end

-- 3. 判断库存是否充足
if stock <= 0 then
    return 0   -- 库存不足
end

-- 4. 原子扣减库存
redis.call('DECR', KEYS[1])

-- 5. 返回成功
return 1
```

### Java 调用代码

```java
@Service
public class SeckillStockService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 原子扣减库存（Lua 脚本）
     */
    public boolean decreaseStock(Long seckillId) {
        String key = "seckill:stock:" + seckillId;

        // Lua 脚本
        String script = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then
                return -1
            end
            if stock <= 0 then
                return 0
            end
            redis.call('DECR', KEYS[1])
            return 1
        """;

        // 执行 Lua 脚本
        Long result = redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(key)
        );

        // 处理结果
        if (result == -1) {
            throw new BusinessException("秒杀库存未初始化");
        }
        return result == 1;
    }
}
```

### 原子性执行流程

```
线程 A 执行 Lua 脚本：
┌─────────────────────────────────┐
│  1. GET stock = 10              │
│  2. stock > 0 ✓                 │  ← 整个脚本原子执行
│  3. DECR stock = 9              │     期间不会被打断
│  4. RETURN 1 (成功)             │
└─────────────────────────────────┘

线程 B 执行 Lua 脚本：
┌─────────────────────────────────┐
│  1. GET stock = 9               │
│  2. stock > 0 ✓                 │  ← 必须等线程 A 执行完
│  3. DECR stock = 8              │
│  4. RETURN 1 (成功)             │
└─────────────────────────────────┘

结果：库存从 10 → 9 → 8，不会超卖！
```

---

## 四、Lua 脚本防止超卖 ⭐⭐⭐

### 方案 1：简单防超卖

```lua
-- Lua 脚本：防止超卖
-- 核心：库存判断和扣减在同一脚本中完成

local key = KEYS[1]
local stock = tonumber(redis.call('GET', key))

-- 库存不存在
if stock == nil then
    return -1
end

-- 库存不足（防超卖核心判断）
if stock <= 0 then
    return 0
end

-- 原子扣减
redis.call('DECR', key)
return 1
```

### 方案 2：预减库存 + 最终扣减（双重保障）

```lua
-- Lua 脚本：双重防超卖
-- 1. Redis 预减库存（第一道防线）
-- 2. 数据库最终扣减（第二道防线）

local stock_key = KEYS[1]           -- Redis 库存 key
local order_key = KEYS[2]           -- 用户订单 key
local user_id = ARGV[1]             -- 用户 ID
local seckill_id = ARGV[2]          -- 秒杀 ID

-- 1. 检查是否重复下单
if redis.call('EXISTS', order_key) == 1 then
    return -2  -- 已下单，防止重复
end

-- 2. 获取库存
local stock = tonumber(redis.call('GET', stock_key))

-- 3. 库存检查（防超卖）
if stock == nil then
    return -1  -- 库存不存在
end

if stock <= 0 then
    return 0   -- 库存不足
end

-- 4. 预减库存
redis.call('DECR', stock_key)

-- 5. 记录用户下单资格（一人一单）
redis.call('SET', order_key, seckill_id, 'EX', 3600)

return 1  -- 预扣成功
```

### Java 调用代码

```java
@Service
public class SeckillAntiService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 防超卖 + 一人一单（Lua 脚本）
     */
    public SeckillResult preCheck(Long userId, Long seckillId) {
        String stockKey = "seckill:stock:" + seckillId;
        String orderKey = "seckill:order:" + userId + ":" + seckillId;

        String script = """
            local stock_key = KEYS[1]
            local order_key = KEYS[2]
            local user_id = ARGV[1]
            local seckill_id = ARGV[2]

            -- 1. 检查是否重复下单
            if redis.call('EXISTS', order_key) == 1 then
                return -2
            end

            -- 2. 获取库存
            local stock = tonumber(redis.call('GET', stock_key))

            -- 3. 库存检查（防超卖）
            if stock == nil then
                return -1
            end

            if stock <= 0 then
                return 0
            end

            -- 4. 预减库存
            redis.call('DECR', stock_key)

            -- 5. 记录用户下单资格
            redis.call('SET', order_key, seckill_id, 'EX', 3600)

            return 1
        """;

        Long result = redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Arrays.asList(stockKey, orderKey),
            String.valueOf(userId),
            String.valueOf(seckillId)
        );

        // 处理结果
        switch (result.intValue()) {
            case -2: return SeckillResult.fail("请勿重复下单");
            case -1: return SeckillResult.fail("秒杀不存在");
            case 0:  return SeckillResult.fail("库存不足");
            default: return SeckillResult.success("预扣成功，请继续下单");
        }
    }
}
```

### 防超卖执行流程

```
库存 = 10，用户 A 和用户 B 同时请求

用户 A 的 Lua 脚本：
┌─────────────────────────────────┐
│  GET stock = 10                 │
│  stock > 0 ✓                    │
│  DECR stock = 9                 │
│  SET order_key (A 已下单)        │
│  RETURN 1                       │
└─────────────────────────────────┘

用户 B 的 Lua 脚本（等 A 执行完）：
┌─────────────────────────────────┐
│  GET stock = 9                  │
│  stock > 0 ✓                    │
│  DECR stock = 8                 │
│  SET order_key (B 已下单)        │
│  RETURN 1                       │
└─────────────────────────────────┘

... 执行 10 次后，stock = 0

用户 C 的 Lua 脚本：
┌─────────────────────────────────┐
│  GET stock = 0                  │
│  stock <= 0 ✗                   │  ← 防超卖生效
│  RETURN 0 (失败)                │
└─────────────────────────────────┘

结果：10 件库存卖出 10 件，不会超卖！
```

---

## 五、Lua 脚本实现一人一单 ⭐⭐

### 方案 1：Redis SETNX 实现

```lua
-- Lua 脚本：一人一单
-- 核心：用 SETNX（SET if Not eXists）保证原子性

local order_key = KEYS[1]           -- 用户订单 key
local user_id = ARGV[1]             -- 用户 ID
local seckill_id = ARGV[2]          -- 秒杀 ID

-- 1. 检查是否已下单（一人一单核心）
if redis.call('EXISTS', order_key) == 1 then
    return 0  -- 已下单，拒绝
end

-- 2. 设置下单标记（原子操作）
-- SETNX：只有 key 不存在时才设置成功
redis.call('SET', order_key, seckill_id, 'EX', 3600)

return 1  -- 成功
```

### 方案 2：SETNX + 库存扣减（合并操作）

```lua
-- Lua 脚本：一人一单 + 扣减库存
-- 将两个操作合并为一个原子操作

local stock_key = KEYS[1]           -- 库存 key
local order_key = KEYS[2]           -- 用户订单 key
local seckill_id = ARGV[1]

-- 1. 一人一单检查
if redis.call('EXISTS', order_key) == 1 then
    return -1  -- 已下单
end

-- 2. 获取库存
local stock = tonumber(redis.call('GET', stock_key))

-- 3. 库存检查
if stock == nil or stock <= 0 then
    return 0  -- 库存不足
end

-- 4. 扣减库存
redis.call('DECR', stock_key)

-- 5. 设置下单标记
redis.call('SET', order_key, seckill_id, 'EX', 3600)

return 1  -- 成功
```

### Java 调用代码

```java
@Service
public class OnePersonOneOrderService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 一人一单检查（Lua 脚本）
     */
    public boolean checkOnePersonOneOrder(Long userId, Long seckillId) {
        String orderKey = "seckill:order:" + userId + ":" + seckillId;

        String script = """
            local order_key = KEYS[1]

            -- 检查是否已下单
            if redis.call('EXISTS', order_key) == 1 then
                return 0  -- 已下单
            end

            -- 设置下单标记
            redis.call('SET', order_key, '1', 'EX', 3600)

            return 1  -- 成功
        """;

        Long result = redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(orderKey)
        );

        return result == 1;
    }
}
```

### 一人一单执行流程

```
用户 A 第一次请求：
┌─────────────────────────────────┐
│  EXISTS order_key = 0 (不存在)  │
│  SET order_key = 1 (成功)       │
│  RETURN 1                       │
└─────────────────────────────────┘
结果：允许下单

用户 A 第二次请求（重复下单）：
┌─────────────────────────────────┐
│  EXISTS order_key = 1 (已存在)  │
│  RETURN 0 (拒绝)                │  ← 一人一单生效
└─────────────────────────────────┘
结果：拒绝下单

用户 B 第一次请求：
┌─────────────────────────────────┐
│  EXISTS order_key:A = 0         │  ← 检查的是 B 的 key
│  SET order_key:B = 1            │
│  RETURN 1                       │
└─────────────────────────────────┘
结果：允许下单（不同用户可以）
```

---

## 六、完整秒杀 Lua 脚本（综合应用）

### 完整脚本：原子性 + 防超卖 + 一人一单

```lua
-- 秒杀完整 Lua 脚本
-- 功能：一人一单 + 防超卖 + 库存扣减 + 生成订单号

-- KEYS 定义
-- KEYS[1]: stock_key - 库存 key
-- KEYS[2]: order_key - 用户订单 key
-- KEYS[3]: order_id_key - 订单 ID 生成器 key

-- ARGV 定义
-- ARGV[1]: user_id - 用户 ID
-- ARGV[2]: seckill_id - 秒杀 ID
-- ARGV[3]: timestamp - 当前时间戳

local stock_key = KEYS[1]
local order_key = KEYS[2]
local order_id_key = KEYS[3]
local user_id = ARGV[1]
local seckill_id = ARGV[2]
local timestamp = ARGV[3]

-- ========== 第一步：一人一单检查 ==========
if redis.call('EXISTS', order_key) == 1 then
    return {code = -2, msg = "请勿重复下单"}
end

-- ========== 第二步：库存检查（防超卖）==========
local stock = tonumber(redis.call('GET', stock_key))

if stock == nil then
    return {code = -1, msg = "秒杀库存不存在"}
end

if stock <= 0 then
    return {code = 0, msg = "库存不足"}
end

-- ========== 第三步：原子扣减库存 ==========
redis.call('DECR', stock_key)

-- ========== 第四步：生成订单号 ==========
-- 订单号格式：seckill_id + timestamp + random
local order_id = seckill_id .. timestamp .. math.random(1000, 9999)

-- ========== 第五步：记录用户订单 ==========
redis.call('SET', order_key, order_id, 'EX', 3600)

-- ========== 第六步：将订单加入待处理队列 ==========
local order_queue = "seckill:order:queue"
redis.call('LPUSH', order_queue, order_id)

-- ========== 返回成功 ==========
return {code = 1, msg = "秒杀成功", order_id = order_id}
```

### Java 调用完整代码

```java
@Service
public class SeckillLuaService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 完整秒杀流程（Lua 脚本）
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillResult seckill(Long userId, Long seckillId) {
        String stockKey = "seckill:stock:" + seckillId;
        String orderKey = "seckill:order:" + userId + ":" + seckillId;
        String orderIdKey = "seckill:order:id:generator";

        // Lua 脚本
        String script = buildLuaScript();

        // 执行脚本
        Map<String, Object> result = redisTemplate.execute(
            new DefaultRedisScript<>(script, Map.class),
            Arrays.asList(stockKey, orderKey, orderIdKey),
            String.valueOf(userId),
            String.valueOf(seckillId),
            String.valueOf(System.currentTimeMillis())
        );

        // 处理结果
        Integer code = (Integer) result.get("code");
        String msg = (String) result.get("msg");

        if (code != 1) {
            return SeckillResult.fail(msg);
        }

        // 获取订单号
        String orderId = (String) result.get("order_id");

        // 发送 MQ 消息，异步创建订单
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setSeckillId(seckillId);
        rocketMQTemplate.send("seckill:order:create", message);

        return SeckillResult.success("秒杀成功，订单号：" + orderId);
    }

    /**
     * 构建 Lua 脚本
     */
    private String buildLuaScript() {
        return """
            local stock_key = KEYS[1]
            local order_key = KEYS[2]
            local order_id_key = KEYS[3]
            local user_id = ARGV[1]
            local seckill_id = ARGV[2]
            local timestamp = ARGV[3]

            -- 一人一单检查
            if redis.call('EXISTS', order_key) == 1 then
                return {code = -2, msg = "请勿重复下单"}
            end

            -- 库存检查
            local stock = tonumber(redis.call('GET', stock_key))
            if stock == nil then
                return {code = -1, msg = "秒杀库存不存在"}
            end
            if stock <= 0 then
                return {code = 0, msg = "库存不足"}
            end

            -- 扣减库存
            redis.call('DECR', stock_key)

            -- 生成订单号
            local order_id = seckill_id .. timestamp .. math.random(1000, 9999)

            -- 记录订单
            redis.call('SET', order_key, order_id, 'EX', 3600)

            -- 加入待处理队列
            redis.call('LPUSH', 'seckill:order:queue', order_id)

            return {code = 1, msg = "秒杀成功", order_id = order_id}
        """;
    }
}
```

---

## 七、Lua 脚本预加载优化 ⭐

### 问题：每次执行都传输脚本内容

```java
// ❌ 效率低：每次执行都要传输脚本内容
redisTemplate.execute(script, keys, args);
```

### 优化：使用 SHA 缓存脚本

```java
@Service
public class LuaScriptCache {

    @Autowired
    private RedisTemplate redisTemplate;

    // 缓存脚本 SHA
    private String seckillScriptSha;

    /**
     * 初始化：加载 Lua 脚本到 Redis
     */
    @PostConstruct
    public void init() {
        String script = buildLuaScript();

        // 加载脚本到 Redis，获取 SHA
        seckillScriptSha = redisTemplate.execute(
            new DefaultRedisScript<>(script, String.class)
        );

        log.info("Lua 脚本已加载，SHA: {}", seckillScriptSha);
    }

    /**
     * 使用 SHA 执行脚本（高效）
     */
    public Map<String, Object> executeSeckill(Long userId, Long seckillId) {
        String stockKey = "seckill:stock:" + seckillId;
        String orderKey = "seckill:order:" + userId + ":" + seckillId;

        // 使用 SHA 执行，不需要传输脚本内容
        return redisTemplate.execute(
            new DefaultRedisScript<>(buildLuaScript(), Map.class),
            Arrays.asList(stockKey, orderKey, "order_id_key"),
            String.valueOf(userId),
            String.valueOf(seckillId),
            String.valueOf(System.currentTimeMillis())
        );
    }

    /**
     * 如果 SHA 失效，重新加载
     */
    public Map<String, Object> executeWithFallback(Long userId, Long seckillId) {
        try {
            return executeSeckill(userId, seckillId);
        } catch (RedisException e) {
            // SHA 失效，重新加载脚本
            log.warn("Lua 脚本 SHA 失效，重新加载");
            init();
            return executeSeckill(userId, seckillId);
        }
    }
}
```

---

## 八、核心对比表

| 对比维度 | 不用 Lua 脚本 | 使用 Lua 脚本 |
| :--- | :--- | :--- |
| **原子性** | 无（多次独立操作） | 有（单原子操作） |
| **网络开销** | 高（多次网络往返） | 低（一次网络请求） |
| **并发安全** | 需要分布式锁 | 天然安全 |
| **代码复杂度** | 高（需要加锁逻辑） | 低（脚本内完成） |
| **性能** | 低（1000 QPS） | 高（10 万 + QPS） |
| **超卖风险** | 有 | 无 |

---

## 九、面试答题话术（直接背）

**面试官问：Redis + Lua 在秒杀中的作用？如何保证原子性、防超卖、一人一单？**

答：我从三个方面回答：

**第一，Redis + Lua 的核心作用：**

Lua 脚本在 Redis 中是**单线程原子执行**的，执行期间不会被其他命令打断。核心作用是将多个 Redis 操作合并为一个原子操作，避免并发问题。

不用 Lua 脚本时，查询库存和扣减库存是两次独立操作，中间可能被其他线程插入，导致超卖。用了 Lua 脚本后，这两步在一个原子操作中完成，不会被中断。

**第二，如何保证原子性：**

Lua 脚本在 Redis 中的执行特性是**整个脚本作为一个命令执行**，执行期间不会被其他命令打断。

比如扣减库存：
```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock <= 0 then
    return 0
end
redis.call('DECR', KEYS[1])
return 1
```

查询和扣减在同一个脚本中完成，要么全部成功，要么全部失败，天然保证原子性。

**第三，如何防超卖：**

防超卖的核心是**库存判断和扣减必须在同一原子操作中**。

Lua 脚本先检查库存是否大于 0，如果是才扣减，两个操作在同一个脚本中完成。即使 10 万并发请求，也是串行执行，不会出现两个线程同时看到库存 = 10 的情况。

**第四，如何实现一人一单：**

用 Redis 的 EXISTS 检查用户是否已下单：
```lua
if redis.call('EXISTS', order_key) == 1 then
    return 0  -- 已下单，拒绝
end
redis.call('SET', order_key, seckill_id)
return 1
```

检查存在和设置标记在同一个原子操作中完成，用户并发请求也不会重复下单。

**综合应用：**

我们把一人一单、防超卖、库存扣减、订单号生成全部放在一个 Lua 脚本中，一次网络请求完成所有检查，性能从 1000 QPS 提升到 10 万 + QPS，且不会超卖、不会重复下单。
