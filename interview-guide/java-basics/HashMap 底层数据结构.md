# [Java 基础] HashMap 底层数据结构

## 一、题目分析

### 考察点
- HashMap 的底层数据结构（数组 + 链表 + 红黑树）
- JDK 1.7 与 JDK 1.8 的区别
- put/get 方法的执行流程
- 哈希冲突解决方案
- 扩容机制

### 难度等级
⭐⭐⭐⭐ (4/5 星) - 高频核心题

### 适用岗位
- 初级/中级/高级 Java 开发工程师
- 所有 Java 相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> JDK 1.8 中，HashMap 底层采用 **数组 + 链表 + 红黑树** 的混合结构。数组是主体，链表用于解决哈希冲突，当链表长度超过 8 且数组长度超过 64 时，链表转为红黑树以提高查询效率。

---

## 三、深度剖析

### 3.1 底层结构演变

```
┌─────────────────────────────────────────────────────────┐
│              JDK 1.7：数组 + 链表                         │
│                                                          │
│  ┌─────┐    ┌──────┐    ┌──────┐                        │
│  │  0  │ →  │ Node │ →  │ Node │ →  ...                │
│  ├─────┤    └──────┘    └──────┘                        │
│  │  1  │                                                 │
│  ├─────┤    ┌──────┐    ┌──────┐                        │
│  │  2  │ →  │ Node │ →  │ Node │                        │
│  ├─────┤    └──────┘    └──────┘                        │
│  │ ... │                                                 │
│  └─────┘                                                 │
│  头插法插入，多线程扩容可能死循环                           │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│         JDK 1.8：数组 + 链表 + 红黑树                      │
│                                                          │
│  ┌─────┐    ┌──────┐    ┌──────┐                        │
│  │  0  │ →  │ Node │ →  │ Node │ →  ...                │
│  ├─────┤    └──────┘    └──────┘                        │
│  │  1  │                    ↓                            │
│  ├─────┤              ┌───────────────┐                 │
│  │  2  │ →  TreeNode → TreeNode → ...  (链表转红黑树)    │
│  ├─────┤              └───────────────┘                 │
│  │ ... │                                                 │
│  └─────┘                                                 │
│  尾插法插入，链表长度>8 且数组>64 时转红黑树                    │
└─────────────────────────────────────────────────────────┘
```

### 3.2 核心字段详解

```java
public class HashMap<K,V> extends AbstractMap<K,V> {

    // 默认初始容量：16
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;

    // 最大容量：2^30
    static final int MAXIMUM_CAPACITY = 1 << 30;

    // 默认负载因子：0.75
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    // 树化阈值：链表长度>=8 时转红黑树
    static final int TREEIFY_THRESHOLD = 8;

    // 去树化阈值：链表长度<=6 时转回链表
    static final int UNTREEIFY_THRESHOLD = 6;

    // 最小树化容量：数组长度>=64 才树化
    static final int MIN_TREEIFY_CAPACITY = 64;

    // 存储数据的数组
    transient Node<K,V>[] table;

    // 实际元素个数
    transient int size;

    // 修改次数（用于 fail-fast）
    transient int modCount;

    // 扩容阈值 = 容量 * 负载因子
    int threshold;

    // 负载因子
    final float loadFactor;
}

// 节点结构（JDK 1.8）
static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    V value;
    Node<K,V> next;  // 链表下一个节点
}

// 红黑树节点
static final class TreeNode<K,V> extends LinkedHashMap.Entry<K,V> {
    TreeNode<K,V> parent;   // 父节点
    TreeNode<K,V> left;     // 左子节点
    TreeNode<K,V> right;    // 右子节点
    TreeNode<K,V> prev;     // 链表前驱节点
    boolean red;            // 颜色：红/黑
}
```

### 3.3 关键参数速记表

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 初始容量 | 16 | 必须是 2 的幂次方 |
| 负载因子 | 0.75 | 扩容时机：size > capacity * 0.75 |
| 树化阈值 | 8 | 链表长度≥8 转红黑树 |
| 去树化阈值 | 6 | 链表长度≤6 转回链表 |
| 最小树化容量 | 64 | 数组长度<64 时优先扩容而非树化 |

### 3.4 put 方法执行流程

```
1. 计算 key 的 hash 值
   └─> hash = (h = key.hashCode()) ^ (h >>> 16)

2. 计算数组索引位置
   └─> index = (n - 1) & hash   (n 为数组长度)

3. 判断该位置是否为空
   ├─> 为空：创建新节点放入
   └─> 不为空：遍历链表/红黑树
       ├─> key 已存在：覆盖 value
       └─> key 不存在：插入链表尾部（尾插法）

4. 判断是否需要树化
   └─> 链表长度 >= 8 且 数组长度 >= 64：转红黑树

5. 判断是否需要扩容
   └─> size > threshold：扩容为原来 2 倍
```

### 3.5 扩容机制

```java
// 扩容方法（简化版）
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;

    if (oldCap > 0) {
        // 达到最大容量，不再扩容
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return oldTab;
        }
        // 容量翻倍
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                 oldCap >= DEFAULT_INITIAL_CAPACITY)
            newThr = oldThr << 1;
    }

    // 创建新数组
    Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
    table = newTab;
    threshold = newThr;

    // 重新分配元素（rehash）
    if (oldTab != null) {
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e = oldTab[j];
            if (e != null) {
                oldTab[j] = null;
                if (e.next == null)
                    newTab[e.hash & (newCap - 1)] = e;
                else {
                    // 链表重新分配：高位和低位
                    // JDK 1.8 优化：不需要重新计算 hash
                }
            }
        }
    }
    return newTab;
}
```

**扩容示意图：**
```
扩容前（容量 16）：
┌─────┐
│  0  │ → NodeA(hash=0) → NodeB(hash=16)
└─────┘

扩容后（容量 32）：
┌─────┐
│  0  │ → NodeA(hash=0)    // 0 & 31 = 0
├─────┤
│ ... │
├─────┤
│ 16  │ → NodeB(hash=16)   // 16 & 31 = 16
└─────┘

关键：扩容后，元素要么在原位置，要么在「原位置 + 原容量」位置
```

---

## 四、代码示例

### 4.1 HashMap 基本使用

```java
import java.util.HashMap;
import java.util.Map;

public class HashMapExample {
    public static void main(String[] args) {
        // 1. 创建 HashMap
        Map<String, Integer> map = new HashMap<>();

        // 2. 添加元素
        map.put("Alice", 25);
        map.put("Bob", 30);
        map.put("Charlie", 35);

        // 3. 获取元素
        Integer age = map.get("Alice");
        System.out.println("Alice's age: " + age);  // 25

        // 4. 判断是否包含
        boolean contains = map.containsKey("Bob");
        System.out.println("Contains Bob: " + contains);  // true

        // 5. 遍历 HashMap
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }

        // 6. 指定初始容量和负载因子
        Map<String, Integer> customMap = new HashMap<>(32, 0.75f);
    }
}
```

### 4.2 自定义 Key 的注意事项

```java
import java.util.HashMap;
import java.util.Objects;

class Person {
    private String name;
    private int age;

    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    // 必须重写 equals 和 hashCode
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Person person = (Person) o;
        return age == person.age && Objects.equals(name, person.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, age);
    }
}

public class CustomKeyTest {
    public static void main(String[] args) {
        Map<Person, String> map = new HashMap<>();

        Person p1 = new Person("Alice", 25);
        Person p2 = new Person("Alice", 25);  // 与 p1 相等

        map.put(p1, "value1");

        // 正确重写 equals/hashCode 后，p2 能获取到值
        System.out.println(map.get(p2));  // value1

        // 如果没重写，map.get(p2) 会返回 null
    }
}
```

### 4.3 容量设置最佳实践

```java
public class CapacityBestPractice {
    public static void main(String[] args) {
        // 场景 1：知道大概需要存储的元素个数
        // 建议：初始容量 = 预期元素数 / 负载因子 + 1
        int expectedSize = 100;
        int initialCapacity = (int) (expectedSize / 0.75f) + 1;  // 134
        Map<String, Integer> map1 = new HashMap<>(initialCapacity);

        // 场景 2：使用工具方法计算
        Map<String, Integer> map2 = new HashMap<>((int) (100 / 0.75f) + 1);

        // 场景 3：直接使用默认值（适合不确定大小的情况）
        Map<String, Integer> map3 = new HashMap<>();

        // 场景 4：使用 Java 9+ 的 of 方法创建不可变 Map
        Map<String, Integer> immutableMap = Map.of(
            "a", 1,
            "b", 2,
            "c", 3
        );
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：为什么 HashMap 的容量必须是 2 的幂次方？

**答：** 为了能让 hash 值均匀分布，并且让索引计算更高效。

```java
// 容量是 2 的幂次方时
index = (n - 1) & hash;  // 位运算，效率高

// 等价于
index = hash % n;  // 取模运算，效率低
```

**原因：**
- 当 n = 2^k 时，`(n-1) & hash` 等价于 `hash % n`
- 位运算比取模运算快得多
- 能保证低位均匀分布

### 扩展 2：JDK 1.7 和 JDK 1.8 的 HashMap 有哪些区别？

| 对比项 | JDK 1.7 | JDK 1.8 |
|--------|---------|---------|
| 数据结构 | 数组 + 链表 | 数组 + 链表 + 红黑树 |
| 插入方式 | 头插法 | 尾插法 |
| 扩容问题 | 可能死循环 | 避免死循环 |
| hash 计算 | 9 次扰动 | 4 次扰动（简化） |
| 索引计算 | 先 hash 再 & | 先 & 再判断 |
| 树化机制 | 无 | 链表长度>8 转红黑树 |

### 扩展 3：HashMap 是线程安全的吗？

**答：** 不是。多线程环境下会出现以下问题：
- JDK 1.7：扩容时可能形成环形链表，导致死循环
- JDK 1.8：可能丢失数据（覆盖问题）

**解决方案：**
```java
// 方案 1：Collections 同步包装
Map<String, Integer> syncMap = Collections.synchronizedMap(new HashMap<>());

// 方案 2：ConcurrentHashMap（推荐）
Map<String, Integer> concurrentMap = new ConcurrentHashMap<>();

// 方案 3：Hashtable（已过时，不推荐）
Map<String, Integer> hashtable = new Hashtable<>();
```

### 扩展 4：为什么链表长度超过 8 才转红黑树？

**答：** 这是根据泊松分布统计得出的最优阈值。

```
链表长度概率分布（负载因子 0.75）：
长度 0: 0.6065
长度 1: 0.3032
长度 2: 0.0758
长度 3: 0.0126
长度 4: 0.0015
长度 5: 0.0003
长度 6: 0.00004
长度 7: 0.000005
长度 8: 0.0000006  ← 极低概率
```

链表长度达到 8 的概率只有约 0.00006%，非常罕见。此时转红黑树的收益大于开销。

---

## 六、面试答题话术

**面试官问：请说说 HashMap 的底层数据结构？**

**标准回答：**

> JDK 1.8 中，HashMap 底层采用**数组 + 链表 + 红黑树**的混合结构：
>
> 1. **数组**是主体，用于存储数据，默认容量 16
> 2. **链表**用于解决哈希冲突，当多个 key 的 hash 值映射到同一数组位置时，用链表串联
> 3. **红黑树**是优化手段，当链表长度超过 8 且数组长度超过 64 时，链表转为红黑树，查询效率从 O(n) 提升到 O(log n)
>
> 另外，HashMap 的容量必须是 2 的幂次方，这样可以用位运算快速计算索引位置。负载因子默认 0.75，当元素个数超过容量×0.75 时触发扩容。

**加分回答：**

> 补充几点：
> 1. JDK 1.7 用的是头插法，扩容时可能形成环形链表导致死循环；JDK 1.8 改用尾插法，避免了这个问题
> 2. 链表转红黑树的阈值是 8，这是根据泊松分布统计得出的，因为链表长度达到 8 的概率极低（约 0.00006%）
> 3. 如果初始化时知道大概的元素个数，建议设置合适的初始容量，避免频繁扩容。公式是：`预期元素数 / 0.75 + 1`
> 4. HashMap 不是线程安全的，多线程环境建议用 `ConcurrentHashMap`

---

## 七、速记表格

| 特性 | 值/说明 |
|------|---------|
| 底层结构 | 数组 + 链表 + 红黑树 |
| 默认容量 | 16（2 的幂次方） |
| 负载因子 | 0.75 |
| 树化阈值 | 链表长度≥8 且数组≥64 |
| 去树化阈值 | 链表长度≤6 |
| 插入方式 | 尾插法（JDK 1.8） |
| 线程安全 | 否 |
| null 值支持 | key 和 value 都可为 null |
| 时间复杂度 | O(1) 平均，O(n) 最坏 |
| 扩容倍数 | 2 倍 |
