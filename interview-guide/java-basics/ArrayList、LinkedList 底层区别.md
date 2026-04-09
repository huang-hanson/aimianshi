# [Java 基础] ArrayList、LinkedList 底层区别

## 一、题目分析

### 考察点
- ArrayList 和 LinkedList 的底层数据结构
- 两种集合的增删改查性能差异
- 内存占用对比
- 实际场景中的选型建议

### 难度等级
⭐⭐ (2/5 星) - 基础高频题

### 适用岗位
- 初级/中级/高级 Java 开发工程师
- 所有 Java 相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> **ArrayList 基于动态数组**，支持随机访问，查询快（O(1)），但中间插入/删除慢（O(n)）；**LinkedList 基于双向链表**，插入/删除快（O(1)），但查询慢（O(n)）。

---

## 三、深度剖析

### 3.1 底层数据结构对比

```
┌─────────────────────────────────────────────────────────┐
│                    ArrayList                             │
│  底层：Object[] 数组（动态扩容）                          │
│  ┌─────┬─────┬─────┬─────┬─────┬─────┐                 │
│  │  0  │  1  │  2  │  3  │  4  │  5  │  ...            │
│  ├─────┼─────┼─────┼─────┼─────┼─────┤                 │
│  │ A   │ B   │ C   │ D   │ E   │ F   │                 │
│  └─────┴─────┴─────┴─────┴─────┴─────┘                 │
│  特点：内存连续，支持下标随机访问                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    LinkedList                            │
│  底层：双向链表（Node 节点）                              │
│  head → [A] ↔ [B] ↔ [C] ↔ [D] ↔ [E] ↔ [F] ← tail        │
│         ↑    ↑    ↑    ↑    ↑    ↑                       │
│        prev next prev next prev next                    │
│  特点：内存不连续，只能顺序访问                           │
└─────────────────────────────────────────────────────────┘
```

### 3.2 性能对比表

| 操作 | ArrayList | LinkedList | 说明 |
|------|-----------|------------|------|
| **get(index)** | O(1) | O(n) | ArrayList 支持随机访问 |
| **add(E)** | O(1) | O(1) | 尾部添加都很快 |
| **add(index, E)** | O(n) | O(1)* | LinkedList 需先找到位置 |
| **remove(index)** | O(n) | O(1)* | LinkedList 需先找到位置 |
| **set(index, E)** | O(1) | O(n) | ArrayList 直接覆盖 |
| **contains(E)** | O(n) | O(n) | 都需要遍历 |
| **内存占用** | 低 | 高 | LinkedList 每个节点有 prev/next 指针 |

> *注：LinkedList 的 O(1) 前提是已定位到节点，实际需要先 O(n) 查找位置

### 3.3 源码关键片段

**ArrayList 核心字段：**
```java
public class ArrayList<E> extends AbstractList<E> {
    private static final int DEFAULT_CAPACITY = 10;  // 默认容量
    private Object[] elementData;                     // 底层数组
    private int size;                                 // 实际大小

    // 扩容机制：1.5 倍
    private void grow(int minCapacity) {
        int oldCapacity = elementData.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1);  // 1.5 倍
        elementData = Arrays.copyOf(elementData, newCapacity);
    }
}
```

**LinkedList 核心字段：**
```java
public class LinkedList<E> extends AbstractSequentialList<E> {
    transient int size = 0;           // 链表大小
    transient Node<E> first;          // 头节点
    transient Node<E> last;           // 尾节点

    // 节点结构
    private static class Node<E> {
        E item;                       // 数据
        Node<E> next;                 // 后继指针
        Node<E> prev;                 // 前驱指针

        Node(Node<E> prev, E element, Node<E> next) {
            this.item = element;
            this.next = next;
            this.prev = prev;
        }
    }
}
```

---

## 四、代码示例

### 4.1 性能对比测试

```java
import java.util.*;

public class ArrayListVsLinkedListTest {
    public static void main(String[] args) {
        int size = 10000;

        // 1. 随机访问性能测试
        System.out.println("=== 随机访问测试 ===");
        testRandomAccess(size);

        // 2. 尾部添加性能测试
        System.out.println("\n=== 尾部添加测试 ===");
        testAddAtEnd(size);

        // 3. 中间插入性能测试
        System.out.println("\n=== 中间插入测试 ===");
        testInsertAtMiddle(size);

        // 4. 中间删除性能测试
        System.out.println("\n=== 中间删除测试 ===");
        testDeleteAtMiddle(size);
    }

    private static void testRandomAccess(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            arrayList.get(i);
        }
        System.out.println("ArrayList get: " + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            linkedList.get(i);
        }
        System.out.println("LinkedList get: " + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static void testInsertAtMiddle(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            arrayList.add(size / 2, i);
        }
        System.out.println("ArrayList 中间插入： " + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            linkedList.add(size / 2, i);
        }
        System.out.println("LinkedList 中间插入： " + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static void testDeleteAtMiddle(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            arrayList.remove(size / 2);
        }
        System.out.println("ArrayList 中间删除：" + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            linkedList.remove(size / 2);
        }
        System.out.println("LinkedList 中间删除：" + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static void testAddAtEnd(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        long start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            arrayList.add(i);
        }
        System.out.println("ArrayList 尾部添加：" + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            linkedList.add(i);
        }
        System.out.println("LinkedList 尾部添加：" + (System.nanoTime() - start) / 1_000_000 + "ms");
    }
}
```

### 4.2 典型使用场景

```java
public class UsageScenarios {

    // 场景 1：频繁随机访问 → 选 ArrayList
    public void scenario1() {
        List<String> list = new ArrayList<>();
        list.add("A");
        list.add("B");
        list.add("C");

        // 频繁通过下标访问
        String first = list.get(0);
        String second = list.get(1);
    }

    // 场景 2：频繁头部/中间插入删除 → 选 LinkedList
    public void scenario2() {
        List<String> list = new LinkedList<>();

        // 频繁头部插入（如实现栈或队列）
        list.add(0, "first");
        list.add(0, "new first");

        // 或使用迭代器在遍历时删除
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            if (shouldRemove(it.next())) {
                it.remove();  // LinkedList 删除更高效
            }
        }
    }

    // 场景 3：作为栈使用 → 两者都可以，ArrayList 性能更好
    public void scenario3() {
        Deque<String> stack = new ArrayDeque<>();  // 推荐
        stack.push("A");
        stack.push("B");
        String top = stack.pop();
    }

    // 场景 4：作为队列使用 → LinkedList 或 ArrayDeque
    public void scenario4() {
        Queue<String> queue = new LinkedList<>();  // 或 ArrayDeque
        queue.offer("A");
        String head = queue.poll();
    }

    private boolean shouldRemove(String s) {
        return false;
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：ArrayList 的扩容机制？

**答：** ArrayList 默认初始容量为 10，当容量不足时触发扩容：
```java
// 扩容为原来的 1.5 倍
int newCapacity = oldCapacity + (oldCapacity >> 1);
```

**扩容步骤：**
1. 检查是否需要扩容
2. 计算新容量（1.5 倍）
3. 使用 `Arrays.copyOf()` 复制原数组到新数组
4. 更新 elementData 引用

**注意：** 扩容涉及数组复制，性能开销较大，初始化时指定合适容量可避免频繁扩容。

### 扩展 2：LinkedList 可以用作随机访问吗？

**答：** 不推荐。虽然 LinkedList 实现了 `RandomAccess` 接口（实际上没有），但它的 `get(index)` 方法需要从头或尾遍历到目标位置，时间复杂度为 O(n)。

```java
// LinkedList 的 get 方法源码
public E get(int index) {
    checkElementIndex(index);
    return node(index).item;
}

// node 方法：从头或尾就近查找
Node<E> node(int index) {
    if (index < (size >> 1)) {
        Node<E> x = first;
        for (int i = 0; i < index; i++)
            x = x.next;
        return x;
    } else {
        Node<E> x = last;
        for (int i = size - 1; i > index; i--)
            x = x.prev;
        return x;
    }
}
```

### 扩展 3：ArrayList 和 LinkedList 是线程安全的吗？

**答：** 都不是线程安全的。多线程环境下需要使用同步包装或并发集合：

```java
// 方式 1：Collections 同步包装
List<String> syncList = Collections.synchronizedList(new ArrayList<>());

// 方式 2：CopyOnWriteArrayList（读多写少场景）
List<String> cowList = new CopyOnWriteArrayList<>();

// 方式 3：Vector（已过时，不推荐）
List<String> vector = new Vector<>();
```

---

## 六、面试答题话术

**面试官问：ArrayList 和 LinkedList 有什么区别？**

**标准回答：**

> ArrayList 和 LinkedList 都实现了 List 接口，但底层数据结构不同：
>
> 1. **数据结构**：ArrayList 基于动态数组，LinkedList 基于双向链表
> 2. **查询性能**：ArrayList 支持随机访问，get(index) 是 O(1)；LinkedList 需要遍历，get(index) 是 O(n)
> 3. **增删性能**：ArrayList 中间插入/删除需要移动元素，效率低；LinkedList 只需要修改指针，但前提是已定位到位置
> 4. **内存占用**：ArrayList 内存紧凑；LinkedList 每个节点需要额外存储 prev 和 next 指针
>
> **选型建议**：
> - 读多写少、需要随机访问 → ArrayList
> - 频繁头部/中间插入删除、不关心随机访问 → LinkedList
> - 实际开发中 ArrayList 使用更广泛

**加分回答：**

> 补充一点，LinkedList 虽然理论上中间插入删除是 O(1)，但实际需要先 O(n) 找到位置，所以整体还是 O(n)。
>
> 另外，如果需要线程安全的 List，可以用 `Collections.synchronizedList()` 包装，或者用 `CopyOnWriteArrayList`（适合读多写少场景）。
>
> 还有，JDK 8 之后，如果频繁头部插入，可以用 `ArrayDeque` 代替 LinkedList，性能更好。

---

## 七、速记表格

| 特性 | ArrayList | LinkedList |
|------|-----------|------------|
| 底层结构 | 数组 | 双向链表 |
| 随机访问 | ✅ O(1) | ❌ O(n) |
| 尾部添加 | ✅ O(1) | ✅ O(1) |
| 中间插入 | ❌ O(n) | ⚠️ O(n) |
| 内存占用 | 低 | 高 |
| 适用场景 | 查询频繁 | 增删频繁 |
