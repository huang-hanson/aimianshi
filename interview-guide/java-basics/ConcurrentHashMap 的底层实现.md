# [并发] ConcurrentHashMap 的底层实现

## 一、题目分析

### 考察点
- ConcurrentHashMap 的底层数据结构
- JDK 1.7 与 JDK 1.8 的实现区别
- 线程安全的实现原理
- CAS 和 synchronized 的联合使用
- 锁粒度优化

### 难度等级
⭐⭐⭐⭐ (4/5 星) - 高频核心题

### 适用岗位
- 初级/中级/高级 Java 开发工程师
- 所有 Java 并发相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> JDK 1.8 中，ConcurrentHashMap 采用 **数组 + 链表 + 红黑树** 结构，使用 **CAS + synchronized** 保证线程安全，锁粒度从 JDK 1.7 的**分段锁**优化为**桶锁（每个数组节点一把锁）**，并发度更高。

---

## 三、深度剖析

### 3.1 结构演变对比

```
┌─────────────────────────────────────────────────────────┐
│           JDK 1.7：Segment 分段锁                         │
│                                                          │
│  ConcurrentHashMap                                       │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Segment[] segments (默认 16 个)                     │    │
│  │  ┌─────────────┐                                │    │
│  │  │ Segment 0   │ → ReentrantLock                │    │
│  │  │ HashEntry[] │ → 数组 + 链表                   │    │
│  │  └─────────────┘                                │    │
│  │  ┌─────────────┐                                │    │
│  │  │ Segment 1   │ → ReentrantLock                │    │
│  │  │ HashEntry[] │ → 数组 + 链表                   │    │
│  │  └─────────────┘                                │    │
│  └─────────────────────────────────────────────────┘    │
│  锁粒度：一个 Segment 一把锁（包含多个桶）                    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│         JDK 1.8：CAS + synchronized 桶锁                   │
│                                                          │
│  ConcurrentHashMap                                       │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Node<K,V>[] table (数组)                          │    │
│  │  ┌─────┐  ┌──────┐    ┌──────┐                   │    │
│  │  │  0  │→ │ Node │ →  │ Node │ → ...            │    │
│  │  ├─────┤  └──────┘    └──────┘                   │    │
│  │  │  1  │        ↓                                 │    │
│  │  ├─────┤  ┌───────────────┐                      │    │
│  │  │  2  │→ │ TreeNode 树   │ (链表长度>8 转树)         │    │
│  │  ├─────┤  └───────────────┘                      │    │
│  └─────────────────────────────────────────────────┘    │
│  锁粒度：每个数组节点一把锁（synchronized）                  │
└─────────────────────────────────────────────────────────┘
```

### 3.2 JDK 1.7 vs JDK 1.8 对比表

| 对比项 | JDK 1.7 | JDK 1.8 |
|--------|---------|---------|
| **数据结构** | 数组 + 链表 | 数组 + 链表 + 红黑树 |
| **锁机制** | ReentrantLock（分段锁） | synchronized + CAS |
| **锁粒度** | Segment（段） | Node（桶） |
| **并发度** | 最多 16 个并发 | 最多 16 个并发，实际更高 |
| **put 实现** | 锁 Segment，遍历链表头插 | 锁桶节点，尾插法 |
| **get 实现** | 无锁，volatile 保证可见性 | 无锁，volatile 保证可见性 |
| **扩容优化** | 需要锁住整个 Segment | 支持多线程协同扩容 |

### 3.3 核心字段详解（JDK 1.8）

```java
public class ConcurrentHashMap<K,V> extends AbstractMap<K,V> {

    // 默认初始容量：16
    private static final int DEFAULT_CAPACITY = 16;

    // 最大容量：2^30
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    // 默认并发级别（JDK 1.8 已废弃，但保留常量）
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    // 负载因子
    private static final float LOAD_FACTOR = 0.75f;

    // 树化阈值：链表长度>=8 转红黑树
    static final int TREEIFY_THRESHOLD = 8;

    // 去树化阈值：链表长度<=6 转回链表
    static final int UNTREEIFY_THRESHOLD = 6;

    // 最小树化容量：数组长度>=64 才树化
    static final int MIN_TREEIFY_CAPACITY = 64;

    // 存储数据的数组
    transient volatile Node<K,V>[] table;

    // 扩容时的新数组
    private transient volatile Node<K,V>[] nextTable;

    // 基础大小（没有锁竞争时的元素计数）
    private transient volatile long baseCount;

    // 扩容状态标记
    private transient volatile int sizeCtl;

    // 内部锁容器
    private transient volatile Node<K,V>[] counterCells;
}

// 节点结构
static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    volatile V val;           // volatile 保证可见性
    volatile Node<K,V> next;  // volatile 保证可见性

    Node(int hash, K key, V val) {
        this.hash = hash;
        this.key = key;
        this.val = val;
    }
}

// 树节点
static final class TreeNode<K,V> extends Node<K,V> {
    TreeNode<K,V> parent;
    TreeNode<K,V> left;
    TreeNode<K,V> right;
    TreeNode<K,V> prev;
    boolean red;
}
```

### 3.4 关键参数速记表

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 初始容量 | 16 | 必须是 2 的幂次方 |
| 负载因子 | 0.75 | 扩容时机 |
| 树化阈值 | 8 | 链表长度≥8 转红黑树 |
| 去树化阈值 | 6 | 链表长度≤6 转回链表 |
| 最小树化容量 | 64 | 数组长度<64 优先扩容 |
| 并发级别 | 16 | JDK 1.8 已废弃 |

### 3.5 put 方法执行流程（JDK 1.8）

```
1. 计算 hash 值
   └─> spread(key.hashCode()) = (h ^ (h >>> 16)) & HASH_BITS

2. 判断数组是否初始化
   ├─> 未初始化：调用 initTable() 初始化
   └─> 已初始化：继续

3. 计算数组索引位置
   └─> index = (n - 1) & hash

4. 判断该位置是否为空
   ├─> 为空：CAS 插入新节点
   └─> 不为空：进入下一步

5. 处理哈希冲突
   ├─> 是树节点：调用 putTreeVal()
   └─> 是链表节点：
       ├─> 锁住头节点（synchronized(f)）
       ├─> 遍历链表/树
       │   ├─> key 已存在：覆盖 value
       │   └─> key 不存在：插入尾部
       └─> 判断是否需要树化（长度>=8）

6. 判断是否需要扩容
   └─> size >= threshold：调用 transfer() 扩容

7. 更新计数器
   └─> addCount(1L, binCount)
```

### 3.6 线程安全实现原理

```java
// putVal 方法核心逻辑（简化版）
final V putVal(K key, V value, boolean onlyIfAbsent) {
    // 1. 计算 hash
    int hash = spread(key.hashCode());
    int binCount = 0;

    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;

        // 2. 数组未初始化，先初始化
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();

        // 3. 该位置为空，CAS 插入
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
                break;
        }

        // 4. 正在扩容，帮助扩容
        else if ((fh = f.hash) == MOVED)
            helpTransfer(tab, f);

        // 5. 锁住头节点，处理冲突
        else {
            V oldVal = null;
            synchronized (f) {  // 锁粒度：单个桶
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        // 链表遍历
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            if (e.hash == hash &&
                                ((k = e.key) == key || key.equals(k))) {
                                oldVal = e.val;
                                if (!onlyIfAbsent)
                                    e.val = value;
                                break;
                            }
                            if ((e = e.next) == null) {
                                e.next = new Node<K,V>(hash, key, value);
                                break;
                            }
                        }
                    }
                }
            }
            if (binCount >= TREEIFY_THRESHOLD)
                treeifyBin(tab, i);  // 树化
            if (oldVal != null)
                return oldVal;
            break;
        }
    }
    addCount(1L, binCount);  // 更新计数
    return null;
}
```

**关键点：**
1. **volatile**：`val` 和 `next` 字段用 volatile 修饰，保证可见性
2. **CAS**：插入空位置时用 CAS，无锁竞争
3. **synchronized**：哈希冲突时锁住头节点，粒度更细
4. **get 无锁**：读操作不需要加锁，性能更高

### 3.7 扩容机制（JDK 1.8 优化）

```java
// 扩容核心逻辑（简化版）
private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
    int n = tab.length;
    int stride = (n >>> 3) / NCPU;  // 每个线程处理的桶数
    if (stride < MIN_TRANSFER_STRIDE)
        stride = MIN_TRANSFER_STRIDE;

    // 多线程协同扩容
    // 每个线程分配一部分桶进行迁移
    // 迁移完成后，原位置放置 ForwardingNode 标记
}
```

**扩容特点：**
- 支持多线程协同扩容
- 每个线程处理一部分桶
- 迁移完成后设置 ForwardingNode 标记
- get 遇到 ForwardingNode 会转发到新表

---

## 四、代码示例

### 4.1 ConcurrentHashMap 基本使用

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ConcurrentHashMapExample {
    public static void main(String[] args) {
        // 1. 创建 ConcurrentHashMap
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

        // 2. 添加元素
        map.put("Alice", 25);
        map.put("Bob", 30);
        map.put("Charlie", 35);

        // 3. 获取元素
        Integer age = map.get("Alice");
        System.out.println("Alice's age: " + age);  // 25

        // 4. 原子操作
        map.putIfAbsent("David", 40);  // 不存在才放入
        map.computeIfAbsent("Eve", k -> 45);  // 不存在时计算
        map.compute("Alice", (k, v) -> v + 1);  // 计算新值

        // 5. 遍历（线程安全）
        map.forEach((k, v) -> System.out.println(k + ": " + v));

        // 6. 并行遍历（并行度阈值）
        map.forEach(2, (k, v) -> System.out.println(k + ": " + v));

        // 7. 指定初始容量和并发级别
        ConcurrentHashMap<String, Integer> customMap =
            new ConcurrentHashMap<>(32, 0.75f, 16);
    }
}
```

### 4.2 多线程并发测试

```java
import java.util.concurrent.*;

public class ConcurrentTest {
    public static void main(String[] args) throws Exception {
        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1000);

        // 1000 个线程并发写入
        for (int i = 0; i < 1000; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    map.put("key" + index, index);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        System.out.println("最终大小：" + map.size());  // 1000

        // 对比 HashMap（线程不安全）
        testHashMapUnsafe();
    }

    private static void testHashMapUnsafe() {
        Map<String, Integer> hashMap = new HashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 1000; i++) {
            final int index = i;
            executor.submit(() -> {
                hashMap.put("key" + index, index);
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 大小可能小于 1000（数据丢失）
        System.out.println("HashMap 最终大小：" + hashMap.size());
    }
}
```

### 4.3 原子操作方法

```java
public class AtomicOperations {
    public static void main(String[] args) {
        ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();

        // 1. putIfAbsent - 不存在才放入
        map.putIfAbsent("count", 0L);

        // 2. compute - 重新计算
        map.compute("count", (k, v) -> v + 1);

        // 3. computeIfAbsent - 不存在时计算
        map.computeIfAbsent("newKey", k -> 100L);

        // 4. computeIfPresent - 存在时计算
        map.computeIfPresent("count", (k, v) -> v * 2);

        // 5. merge - 合并值
        map.merge("count", 1L, Long::sum);

        // 6. replace - 替换
        map.replace("count", 100L);  // 无条件替换
        map.replace("count", 100L, 200L);  // 比较后替换

        System.out.println(map);
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：ConcurrentHashMap 为什么线程安全？

**答：** 通过以下机制保证线程安全：

```
1. volatile 修饰
   - Node 的 val 和 next 字段用 volatile 修饰
   - 保证多线程之间的可见性

2. CAS 操作
   - 插入空位置时使用 CAS
   - 无锁竞争，性能高

3. synchronized 锁桶
   - 哈希冲突时锁住头节点
   - 锁粒度细，只影响当前桶

4. 原子类计数
   - 使用 LongAdder 机制
   - 分段累加，减少竞争
```

### 扩展 2：ConcurrentHashMap 的 size() 方法准确吗？

**答：** 不一定完全准确，是近似值。

```java
// size() 方法实现
public int size() {
    long sum = sumCount();
    return (sum >= 0L) ? (int) sum : Integer.MAX_VALUE;
}

// sumCount() 方法
final long sumCount() {
    CounterCell[] as = counterCells;
    long sum = baseCount;  // 基础计数

    // 累加分段计数
    if (as != null) {
        for (CounterCell a : as) {
            if (a != null)
                sum += a.value;
        }
    }
    return sum;
}
```

**原理：**
- 使用 LongAdder 思想，分段计数
- 低竞争时累加 baseCount
- 高竞争时使用 CounterCell 数组分散累加
- 返回时累加所有分段，是**近似值**

### 扩展 3：ConcurrentHashMap 允许 null 值吗？

**答：** 不允许。key 和 value 都不能为 null。

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null)
        throw new NullPointerException();  // 直接抛异常
    // ...
}
```

**原因：**
- get() 返回 null 可能表示两种情况：key 不存在 或 value 为 null
- 在并发环境下无法区分这两种情况
- 对比：HashMap 的 get() 返回 null 可以用 containsKey() 判断

### 扩展 4：ConcurrentHashMap 和 Hashtable 的区别？

| 对比项 | ConcurrentHashMap | Hashtable |
|--------|-------------------|-----------|
| **锁机制** | CAS + synchronized | synchronized（全表锁） |
| **锁粒度** | 桶级别 | 整个表 |
| **并发度** | 高 | 低 |
| **null 值** | 不允许 | 不允许 |
| **迭代器** | 弱一致性，不抛 CME | 快速失败，抛 CME |
| **性能** | 高 | 低 |
| **推荐度** | ✅ 推荐 | ❌ 已过时 |

### 扩展 5：ConcurrentHashMap 在 JDK 1.7 和 1.8 的区别？

| 对比项 | JDK 1.7 | JDK 1.8 |
|--------|---------|---------|
| 数据结构 | Segment + HashEntry | Node + TreeNode |
| 锁类型 | ReentrantLock | synchronized |
| 锁粒度 | Segment（段） | Node（桶） |
| 并发度 | 最多 16 | 更高 |
| 插入方式 | 头插法 | 尾插法 |
| 扩容方式 | 锁 Segment | 多线程协同 |
| 查询优化 | 无锁 | 无锁 + CAS |

---

## 六、面试答题话术

**面试官问：请说说 ConcurrentHashMap 的底层实现？**

**标准回答：**

> JDK 1.8 中，ConcurrentHashMap 的底层实现有以下特点：
>
> 1. **数据结构**：采用数组 + 链表 + 红黑树，和 HashMap 类似
> 2. **锁机制**：使用 CAS + synchronized 保证线程安全
> 3. **锁粒度**：锁的是数组的每个桶（头节点），而不是整个表
> 4. **读操作**：无锁，通过 volatile 保证可见性
> 5. **写操作**：哈希冲突时用 synchronized 锁住头节点
>
> 相比 JDK 1.7 的分段锁（Segment），JDK 1.8 的锁粒度更细，并发度更高。

**加分回答：**

> 补充几点：
> 1. ConcurrentHashMap 不允许 key 和 value 为 null，因为并发环境下无法区分 null 的含义
> 2. size() 方法返回的是近似值，使用 LongAdder 思想分段计数
> 3. 扩容支持多线程协同，每个线程处理一部分桶，完成后设置 ForwardingNode 标记
> 4. get 方法全程无锁，性能非常高；put 方法只有在哈希冲突时才加锁
> 5. JDK 1.8 废弃了 Segment，改用 Node 数组 + CAS + synchronized，性能提升明显

---

## 七、速记表格

| 特性 | JDK 1.7 | JDK 1.8 |
|------|---------|---------|
| 数据结构 | Segment + 链表 | Node + 链表 + 红黑树 |
| 锁机制 | ReentrantLock | synchronized + CAS |
| 锁粒度 | Segment | Node（桶） |
| null 支持 | ❌ | ❌ |
| get 无锁 | ✅ | ✅ |
| 树化支持 | ❌ | ✅ |
| 扩容方式 | 锁 Segment | 多线程协同 |
