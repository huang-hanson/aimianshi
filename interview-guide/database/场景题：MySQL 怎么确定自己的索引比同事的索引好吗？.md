# 场景题：MySQL 中怎么确定自己的索引创建的比同事的索引要好呢？

## 一、核心结论

**判断索引优劣的唯一标准是：实际业务场景下的查询性能**，而不是索引数量或复杂度。

核心评估维度：
1. **查询性能**：EXPLAIN 分析执行计划，看 `type`、`rows`、`Extra`
2. **索引区分度**：Cardinality 越高，过滤效果越好
3. **覆盖范围**：是否能用覆盖索引避免回表
4. **维护成本**：索引占用空间、写入时的更新开销
5. **实际耗时**：生产环境慢查询日志 + performance_schema 监控

type字段说明：

1. **const（常量级，最快）**     

**含义**：通过主键或唯一索引，**最多只匹配一行**，MySQL 直接当成常量。 

**典型场景**
- 根据主键查询
- 根据唯一索引精确匹配
```sql
SELECT * FROM user WHERE id = 100;
```
**表现**：速度极快，几乎瞬间出结果。    

2. **eq_ref（等值关联，仅次于 const）**  

**含义**：多表 join 时，驱动表每一行，被驱动表**最多匹配一行**。一般出现在 **主键 / 唯一索引** 关联。  


**典型场景**
```sql
SELECT * FROM order
JOIN user ON order.user_id = user.id;
```

user.id 是主键，所以是 eq_ref。

3. **ref（普通等值索引）**

**含义**：使用**普通索引等值查询**，可能匹配多行。


**典型场景**
- 普通索引 = 查询
```sql
SELECT * FROM user WHERE phone = '138xxxx';
```
phone 是普通索引，不是唯一键。

4. **range（范围查询）**  


**含义**：索引进行范围扫描。 


**典型场景**      

- between
- < >= <=
- in
- like 'xxx%'
```sql
SELECT * FROM user WHERE id BETWEEN 1 AND 100;
```

5. **index（索引全扫描）**     


**含义**：遍历**整个索引树**，没用到索引快速定位，只是比扫全表好一点。    


**典型场景**

- 没按最左前缀查
- 索引字段做了函数 / 运算
- 只需要查索引里的字段，被迫全扫索引
```sql
SELECT name FROM user;
-- name 是索引，但没where条件
```
6. **ALL（全表扫描，最差）**

**含义**：**没用到任何索引**，一行行扫全表。

**典型场景**

- 没建索引
- 索引失效
- 数据量太小 MySQL 懒得用索引
```sql
SELECT * FROM user WHERE address = '北京';
-- address 无索引
```

**一句话总结（面试背这个）**
- **const**：主键 / 唯一索引，只查一行
- **eq_ref**：join 连主键 / 唯一索引
- **ref**：普通索引等值查询
- **range**：索引范围查询
- **index**：扫整个索引
- **ALL**：扫全表，最差

---

## 二、索引优劣对比评估流程

### 1. 第一步：EXPLAIN 执行计划对比（最核心）

分别对两个索引执行相同的查询，对比 EXPLAIN 结果：

```sql
-- 你的索引
EXPLAIN SELECT * FROM order_table WHERE user_id = 123 AND status = 1;

-- 同事的索引
EXPLAIN SELECT * FROM order_table WHERE user_id = 123 AND status = 1;
```

**重点对比 4 个核心字段：**

| 字段 | 含义 | 优劣判断标准 |
| :--- | :--- | :--- |
| `type` | 访问类型 | `const` > `eq_ref` > `ref` > `range` > `index` > `ALL`<br>**越靠左越好**，`ALL` 表示全表扫描（最差） |
| `key` | 实际使用的索引 | 是否命中预期索引，有没有走错索引 |
| `rows` | 预估扫描行数 | **越少越好**，说明索引过滤效果好 |
| `Extra` | 额外信息 | - `Using index`：覆盖索引（最优）<br>- `Using where`：正常过滤<br>- `Using filesort`：需要外部排序（差）<br>- `Using temporary`：使用临时表（差） |

---

### 2. 第二步：索引区分度对比（Cardinality）

```sql
SHOW INDEX FROM order_table;
```

**核心看 Cardinality 列：**

| 索引 | Cardinality | 总行数 | 区分度 | 评价 |
| :--- | :--- | :--- | :--- | :--- |
| 你的索引 `idx_user_status` | 95000 | 100000 | 95% | 优秀 |
| 同事的索引 `idx_status` | 5 | 100000 | 0.005% | 极差 |

**判断标准：**
- Cardinality / 总行数 > 80%：区分度优秀，索引效果好
- Cardinality / 总行数 < 10%：区分度差，索引容易失效
- Cardinality 接近 1：完全没区分度（如性别字段），索引无效

---

### 3. 第三步：实际查询性能测试（最真实）

**（1）大表数据量测试**

```sql
-- 开启 profiling
SET profiling = 1;

-- 执行你的索引查询
SELECT * FROM order_table WHERE user_id = 123 AND status = 1;

-- 执行同事的索引查询
SELECT * FROM order_table WHERE status = 1;

-- 查看耗时对比
SHOW PROFILES;
```

**（2）对比指标：**

| 指标 | 你的索引 | 同事的索引 | 评价 |
| :--- | :--- | :--- | :--- |
| Query_Time | 0.001s | 0.5s | 你的快 500 倍 |
| Rows_examined | 10 | 50000 | 你的扫描少 5000 倍 |
| Rows_sent | 10 | 10 | 返回结果一样 |

---

### 4. 第四步：覆盖索引能力对比（避免回表）

**场景：查询用户订单列表，只需要 user_id、order_id、create_time**

```sql
-- 你的索引：idx_user_create(user_id, create_time, order_id)
SELECT user_id, order_id, create_time
FROM order_table
WHERE user_id = 123
ORDER BY create_time DESC;

-- 同事的索引：idx_user(user_id)
SELECT user_id, order_id, create_time
FROM order_table
WHERE user_id = 123
ORDER BY create_time DESC;
```

**EXPLAIN 对比：**

| 指标 | 你的索引 | 同事的索引 |
| :--- | :--- | :--- |
| type | range | range |
| key | idx_user_create | idx_user |
| rows | 100 | 100 |
| **Extra** | **Using index**（覆盖索引） | **Using filesort**（需排序） |

**结论：** 你的索引更优，因为：
1. 避免了回表查询（Using index）
2. 利用索引顺序避免排序（Using filesort）

---

## 三、常见索引优劣对比场景

### 场景 1：单字段索引 vs 复合索引

**需求：查询 `WHERE user_id = ? AND status = ?`**

| 索引方案 | 索引定义 | 适用场景 | 优劣 |
| :--- | :--- | :--- | :--- |
| 你的索引 | `idx_user_status(user_id, status)` | 同时查 user_id + status | **优**：一次索引定位 |
| 同事的索引 | `idx_user(user_id)` + `idx_status(status)` | 只查单字段 | **劣**：只能用其中一个 |

**EXPLAIN 对比：**

```
-- 你的索引
type: ref
key: idx_user_status
rows: 10
Extra: Using where

-- 同事的索引
type: ref
key: idx_user
rows: 1000
Extra: Using where
```

**结论：** 复合索引更优，因为能同时过滤两个条件，rows 更少。

---

### 场景 2：索引列顺序对比

**需求：高频查询 `WHERE user_id = ?`，低频查询 `WHERE create_time = ?`**

| 索引方案 | 索引定义 | 评价 |
| :--- | :--- | :--- |
| 你的索引 | `idx_user_create(user_id, create_time)` | **优**：高频列在前 |
| 同事的索引 | `idx_create_user(create_time, user_id)` | **劣**：低频列在前 |

**原因：** 违反最左前缀原则，`WHERE user_id = ?` 无法使用同事的索引。

---

### 场景 3：覆盖索引 vs 普通索引

**需求：`SELECT id, user_id FROM order_table WHERE user_id = ?`**

| 索引方案 | 索引定义 | Extra | 评价 |
| :--- | :--- | :--- | :--- |
| 你的索引 | `idx_user_id(user_id, id)` | Using index | **优**：覆盖索引 |
| 同事的索引 | `idx_user(user_id)` | Using where | **劣**：需要回表 |

**结论：** 覆盖索引避免回表，性能提升 10 倍以上。

---

## 四、生产环境验证方法

### 1. 慢查询日志分析

```sql
-- 开启慢查询
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 0.1; -- 超过 0.1 秒记录

-- 查看慢查询日志
mysqldumpslow /var/log/mysql/slow.log;
```

**对比两个索引对应的查询，看谁的慢查询更少。**

---

### 2. performance_schema 实时监控

```sql
-- 查看索引使用统计
SELECT
    OBJECT_SCHEMA,
    OBJECT_NAME,
    INDEX_NAME,
    COUNT_STAR,
    SUM_TIMER_WAIT / 1000000000000 AS total_latency_sec,
    AVG_TIMER_WAIT / 1000000000 AS avg_latency_ms
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE OBJECT_NAME = 'order_table'
ORDER BY SUM_TIMER_WAIT DESC;
```

**对比指标：**
- `avg_latency_ms`：平均耗时，越低越好
- `COUNT_STAR`：使用次数，高频查询优先优化

---

### 3. 索引使用情况监控

```sql
-- 查看索引是否被使用
SELECT
    INDEX_NAME,
    COUNT_READ,
    COUNT_WRITE
FROM information_schema.STATISTICS
JOIN performance_schema.table_io_waits_summary_by_index_usage
ON STATISTICS.TABLE_NAME = table_io_waits_summary_by_index_usage.OBJECT_NAME
WHERE STATISTICS.TABLE_NAME = 'order_table';
```

**如果同事的索引 `COUNT_READ = 0`，说明从未被使用，可以删除。**

---

## 五、索引优劣判断 checklist

**你的索引比同事的好的标志：**

- [ ] EXPLAIN 的 `type` 更优（const > ref > range > ALL）
- [ ] EXPLAIN 的 `rows` 更少（扫描行数少）
- [ ] EXPLAIN 的 `Extra` 有 `Using index`（覆盖索引）
- [ ] 没有 `Using filesort` 和 `Using temporary`
- [ ] Cardinality 区分度高（> 80%）
- [ ] 实际查询耗时更短（profiling 验证）
- [ ] 慢查询日志中该查询出现频率低
- [ ] 索引大小合理，不会占用过多存储
- [ ] 符合最左前缀原则，能命中高频查询
- [ ] 没有被业务代码反向优化（如索引列参与计算）

---

## 六、面试答题话术（直接背）

**面试官问：MySQL 中怎么确定自己的索引创建的比同事的索引要好呢？**

答：判断索引优劣不能靠主观感觉，需要从 4 个维度量化对比：

**第一，EXPLAIN 执行计划对比**，这是最核心的方法。重点看 4 个字段：type 访问类型（const 优于 ref 优于 range）、rows 扫描行数（越少越好）、Extra 额外信息（Using index 最优，Using filesort 最差）。

**第二，索引区分度对比**。用 SHOW INDEX 查看 Cardinality，区分度 = Cardinality / 总行数，越高说明索引过滤效果越好。如果同事的索引 Cardinality 只有 5，而表有 10 万行，说明区分度极差。

**第三，实际查询性能测试**。用 SET profiling = 1 对比实际耗时，或者看生产环境的慢查询日志，谁的查询耗时短、慢查询少，谁的索引就更优。

**第四，覆盖索引能力**。如果我的索引能包含查询所需的所有字段，就能避免回表，性能提升 10 倍以上。

**举个例子**：查询 `WHERE user_id = ? AND status = ?`，我的复合索引 `idx_user_status(user_id, status)` 扫描 10 行，同事的单列索引 `idx_status(status)` 扫描 5 万行，那我的索引明显更优。

**总结**：索引优劣的核心标准是**实际业务场景下的查询性能**，用 EXPLAIN 量化分析，用 profiling 验证实战，用慢查询日志监控长期效果。
