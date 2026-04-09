# [并发] ThreadLocal 原理及内存泄漏问题

## 一、题目分析

### 考察点
- ThreadLocal 的作用和应用场景
- ThreadLocalMap 的底层结构
- ThreadLocal 的 get/set 原理
- 内存泄漏的原因分析
- 内存泄漏的解决方案

### 难度等级
⭐⭐⭐⭐⭐ (5/5 星) - 高频核心难题

### 适用岗位
- 中级/高级 Java 开发工程师
- 所有 Java 并发相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> ThreadLocal 用于实现**线程隔离**，每个线程拥有独立的变量副本。底层通过**ThreadLocalMap**实现，key 是**弱引用**的 ThreadLocal 对象，value 是存储的值。**内存泄漏原因**：ThreadLocalMap 的 key 是弱引用，但 value 是强引用，当 ThreadLocal 对象被 GC 回收后，value 无法被回收。**解决方案**：使用完后调用 `remove()` 方法清理。

---

## 三、深度剖析

### 3.1 ThreadLocal 应用场景

```
┌─────────────────────────────────────────────────────────┐
│          ThreadLocal 典型应用场景                        │
│                                                          │
│  1. 线程安全的日期格式化                                 │
│     SimpleDateFormat 是线程不安全的                       │
│     使用 ThreadLocal 为每个线程创建独立实例                │
│                                                          │
│  2. 数据库连接管理                                       │
│     每个线程持有独立的 Connection                         │
│     避免连接共享带来的线程安全问题                         │
│                                                          │
│  3. Session 管理                                         │
│     Web 请求中，同一请求的多个方法共享 User 信息             │
│     使用 ThreadLocal 存储当前登录用户                      │
│                                                          │
│  4. 事务管理                                             │
│     同一线程的多个操作共享同一个事务                      │
│     Spring 事务管理器使用 ThreadLocal 实现                  │
│                                                          │
│  5. 链路追踪                                             │
│     分布式系统中，TraceId 在调用链中传递                   │
│     使用 ThreadLocal 存储当前请求的追踪信息                │
└─────────────────────────────────────────────────────────┘
```

### 3.2 ThreadLocal 整体架构

```
┌─────────────────────────────────────────────────────────┐
│          ThreadLocal 架构全景图                          │
│                                                          │
│  Thread 线程                                              │
│  ┌─────────────────────────────────────────────────┐    │
│  │ threadLocals                                     │    │
│  │    ↓                                             │    │
│  │ ┌─────────────────────────────────────────────┐ │    │
│  │ │         ThreadLocalMap                       │ │    │
│  │ │  ┌───────────────┬───────────────┐          │ │    │
│  │ │  │ Entry[0]      │ Entry[1]      │ ...      │ │    │
│  │ │  │ ┌───────────┐ │ ┌───────────┐ │          │ │    │
│  │ │  │ │key(弱引用)│ │ │key(弱引用)│ │          │ │    │
│  │ │  │ │ ThreadLocal│ │ │ ThreadLocal│ │         │ │    │
│  │ │  │ ├───────────┤ │ ├───────────┤ │          │ │    │
│  │ │  │ │value(强)  │ │ │value(强)  │ │          │ │    │
│  │ │  │ │  Object   │ │ │  Object   │ │          │ │    │
│  │ │  │ └───────────┘ │ └───────────┘ │          │ │    │
│  │ │  └───────────────┴───────────────┘          │ │    │
│  │ └─────────────────────────────────────────────┘ │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  核心关系：                                               │
│  - 每个 Thread 持有一个 ThreadLocalMap                     │
│  - ThreadLocalMap 存储多个 Entry（ThreadLocal→value）     │
│  - Entry 的 key 是弱引用，value 是强引用                    │
└─────────────────────────────────────────────────────────┘
```

### 3.3 ThreadLocalMap 数据结构

```
┌─────────────────────────────────────────────────────────┐
│          ThreadLocalMap 内部结构                         │
│                                                          │
│  public class ThreadLocalMap {                           │
│                                                          │
│      // Entry 继承 WeakReference                         │
│      static class Entry extends WeakReference<ThreadLocal<?>> {
│          Object value;  // 存储的值（强引用）              │
│                                                          │
│          Entry(ThreadLocal<?> k, Object v) {             │
│              super(k);  // key 是弱引用                    │
│              value = v; // value 是强引用                  │
│          }                                               │
│      }                                                   │
│                                                          │
│      // 哈希表数组                                       │
│      private Entry[] table;                              │
│                                                          │
│      // 数组大小                                         │
│      private int size;                                   │
│                                                          │
│      // 阈值（size >= threshold * 2/3 时扩容）             │
│      private int threshold;                              │
│  }                                                       │
│                                                          │
│  特点：                                                   │
│  - 类似 HashMap 的数组结构                               │
│  - 哈希冲突用线性探测法解决                              │
│  - 没有链表，只有数组                                    │
└─────────────────────────────────────────────────────────┘
```

### 3.4 核心源码分析

**Thread 类中的 threadLocals 字段：**
```java
// Thread 类
public class Thread {
    // 每个 Thread 持有独立的 ThreadLocalMap
    ThreadLocal.ThreadLocalMap threadLocals = null;
}
```

**set 方法源码：**
```java
// ThreadLocal 的 set 方法
public void set(T value) {
    // 1. 获取当前线程
    Thread t = Thread.currentThread();

    // 2. 获取线程的 ThreadLocalMap
    ThreadLocalMap map = getMap(t);

    // 3. Map 存在则设置值，否则创建 Map
    if (map != null)
        map.set(this, value);
    else
        createMap(t, value);
}

// 获取 ThreadLocalMap
ThreadLocalMap getMap(Thread t) {
    return t.threadLocals;
}

// 创建 ThreadLocalMap
void createMap(Thread t, T firstValue) {
    t.threadLocals = new ThreadLocalMap(this, firstValue);
}
```

**ThreadLocalMap 的 set 方法：**
```java
private void set(ThreadLocal<?> key, Object value) {
    Entry[] tab = table;
    int len = tab.length;

    // 1. 计算哈希索引
    int i = key.threadLocalHashCode & (len - 1);

    // 2. 线性探测查找
    for (Entry e = tab[i];
         e != null;
         e = tab[i = nextIndex(i, len)]) {

        ThreadLocal<?> k = e.get();  // 获取 key（弱引用）

        // key 相同，覆盖 value
        if (k == this) {
            e.value = value;
            return;
        }

        // key 为 null（ThreadLocal 被 GC），替换该 Entry
        if (k == null) {
            replaceStaleEntry(key, value, i);
            return;
        }
    }

    // 3. 找到空位，插入新 Entry
    tab[i] = new Entry(key, value);
    int sz = ++size;

    // 4. 清理过期 Entry 并判断是否扩容
    cleanSomeSlots(i, sz);
    if (!expungeStaleEntry(i) && sz >= threshold)
        resize();
}
```

**get 方法源码：**
```java
public T get() {
    // 1. 获取当前线程
    Thread t = Thread.currentThread();

    // 2. 获取 ThreadLocalMap
    ThreadLocalMap map = getMap(t);

    // 3. Map 存在则获取值，否则初始化
    if (map != null) {
        ThreadLocalMap.Entry entry = map.getEntry(this);
        if (entry != null)
            return (T) entry.value;
    }

    // 4. Map 不存在，初始化并返回默认值
    return setInitialValue();
}

private ThreadLocalMap.Entry getEntry(ThreadLocal<?> key) {
    int i = key.threadLocalHashCode & (table.length - 1);
    Entry e = table[i];

    // 线性探测查找
    if (e != null && e.get() == key)
        return e;
    else
        return getEntryAfterMiss(key, i, e);
}
```

**remove 方法源码：**
```java
public void remove() {
    // 1. 获取当前线程的 ThreadLocalMap
    ThreadLocalMap m = getMap(Thread.currentThread());

    // 2. Map 存在则删除 Entry
    if (m != null)
        m.remove(this);
}

// ThreadLocalMap 的 remove 方法
private void remove(ThreadLocal<?> key) {
    Entry[] tab = table;
    int len = tab.length;
    int i = key.threadLocalHashCode & (len - 1);

    // 线性探测查找并删除
    for (Entry e = tab[i];
         e != null;
         e = tab[i = nextIndex(i, len)]) {

        if (e.get() == key) {
            e.value = null;  // 清空 value
            tab[i] = null;   // 清空 Entry
            size--;
            expungeStaleEntry(i);  // 清理过期 Entry
            return;
        }
    }
}
```

### 3.5 内存泄漏问题分析

```
┌─────────────────────────────────────────────────────────┐
│          内存泄漏原因分析                                 │
│                                                          │
│  正常情况：                                               │
│  ┌─────────────────────────────────────────────────┐    │
│  │  ThreadLocal 对象 → 强引用 → 外部引用             │    │
│  │       ↓ (弱引用)                                │    │
│  │  ThreadLocalMap.Entry.key                       │    │
│  │       ↓ (强引用)                                │    │
│  │  ThreadLocalMap.Entry.value → Object           │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  内存泄漏情况（ThreadLocal 被 GC 后）：                       │
│  ┌─────────────────────────────────────────────────┐    │
│  │  ThreadLocal 对象 → 已回收                        │    │
│  │       ↓ (弱引用，自动变 null)                    │    │
│  │  ThreadLocalMap.Entry.key = null                │    │
│  │       ↓ (强引用)                                │    │ │
│  │  ThreadLocalMap.Entry.value → Object ← 无法回收！ │    │
│  │                                    ↑             │    │
│  │                                    │             │    │
│  │                            Thread 持有 Map         │    │
│  │                            Thread 线程未结束       │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  泄漏原因：                                               │
│  1. key 是弱引用，ThreadLocal 对象被 GC 后变 null           │
│  2. value 是强引用，只要 Thread 存活，value 就无法回收      │
│  3. 线程池场景：线程长期存活，value 一直无法回收          │
│  4. 大对象泄漏：value 是大对象时，内存泄漏严重            │
└─────────────────────────────────────────────────────────┘
```

### 3.6 为什么 key 设计为弱引用？

```
┌─────────────────────────────────────────────────────────┐
│          为什么 key 是弱引用而不是强引用？                  │
│                                                          │
│  如果 key 是强引用：                                      │
│  ┌─────────────────────────────────────────────────┐    │
│  │  ThreadLocal 对象 → 强引用 → Entry.key           │    │
│  │       ↓                                          │    │
│  │  外部引用丢失后，ThreadLocal 无法被 GC 回收！        │    │
│  │  原因：ThreadLocalMap 持有 key 的强引用              │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  key 是弱引用：                                           │
│  ┌─────────────────────────────────────────────────┐    │
│  │  ThreadLocal 对象 → 弱引用 → Entry.key           │    │
│  │       ↓                                          │    │
│  │  外部引用丢失后，ThreadLocal 可以被 GC 回收          │    │
│  │  但 value 仍然泄漏（需要手动 remove）               │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  设计权衡：                                               │
│  - 弱引用 key：至少 ThreadLocal 对象可以被回收             │
│  - 但 value 仍泄漏：需要开发者手动 remove                  │
│  - 这是折中方案，不能完全避免泄漏                          │
└─────────────────────────────────────────────────────────┘
```

---

## 四、代码示例

### 4.1 ThreadLocal 基本使用

```java
public class ThreadLocalExample {

    // 1. 创建 ThreadLocal
    private static final ThreadLocal<Integer> threadLocalInt =
        new ThreadLocal<>();

    // 2. 线程安全的 SimpleDateFormat
    private static final ThreadLocal<SimpleDateFormat> dateFormat =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    // 3. 存储用户信息
    private static final ThreadLocal<User> currentUser =
        new ThreadLocal<>();

    public static void main(String[] args) {
        // 4. set 值
        threadLocalInt.set(123);
        currentUser.set(new User("Alice", 25));

        // 5. get 值
        Integer value = threadLocalInt.get();
        System.out.println("value: " + value);

        User user = currentUser.get();
        System.out.println("user: " + user.getName());

        // 6. 格式化日期
        String dateStr = dateFormat.get().format(new Date());
        System.out.println("date: " + dateStr);

        // 7. remove 清理（重要！）
        threadLocalInt.remove();
        currentUser.remove();
    }

    static class User {
        private String name;
        private int age;
        // 构造方法、getter、setter
    }
}
```

### 4.2 内存泄漏演示

```java
import java.util.concurrent.*;

public class MemoryLeakDemo {

    // 大对象
    static class LargeObject {
        private byte[] data = new byte[1024 * 1024 * 10];  // 10MB
    }

    private static final ThreadLocal<LargeObject> threadLocal =
        new ThreadLocal<>();

    public static void main(String[] args) throws InterruptedException {
        // 使用线程池模拟内存泄漏
        ExecutorService executor = Executors.newFixedThreadPool(5);

        // 提交 100 个任务
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                // 设置大对象
                threadLocal.set(new LargeObject());

                // 模拟业务逻辑
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {}

                // 忘记 remove，导致内存泄漏！
                // threadLocal.remove();  // 没有调用

                System.out.println("任务完成");
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        // 查看内存使用情况
        Runtime runtime = Runtime.getRuntime();
        System.out.println("已用内存：" +
            (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + "MB");
    }
}
```

### 4.3 正确的 ThreadLocal 使用方式

```java
public class CorrectThreadLocalUsage {

    // 方式 1：try-finally 清理（推荐）
    public void method1() {
        threadLocal.set(value);
        try {
            // 业务逻辑
        } finally {
            threadLocal.remove();  // 必须清理
        }
    }

    // 方式 2：Web 拦截器清理（Spring 场景）
    public class UserInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            User user = getUserFromRequest(request);
            currentUser.set(user);
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler,
                                    Exception ex) {
            currentUser.remove();  // 请求完成后清理
        }
    }

    // 方式 3：使用 withInitial 提供初始值
    private static final ThreadLocal<List<String>> listLocal =
        ThreadLocal.withInitial(ArrayList::new);

    public void method3() {
        // 不需要判断 null，直接使用
        listLocal.get().add("item");
        try {
            // 业务逻辑
        } finally {
            listLocal.remove();
        }
    }

    private static final ThreadLocal<Integer> threadLocal = new ThreadLocal<>();
    private static final ThreadLocal<User> currentUser = new ThreadLocal<>();
    private Object value;
}
```

### 4.4 ThreadLocal 在 Spring 中的应用

```java
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class UserContext {

    // 存储当前用户 ID
    private static final ThreadLocal<Long> userIdContext = new ThreadLocal<>();

    // 设置用户 ID
    public static void setUserId(Long userId) {
        userIdContext.set(userId);
    }

    // 获取用户 ID
    public static Long getUserId() {
        return userIdContext.get();
    }

    // 清理
    public static void clear() {
        userIdContext.remove();
    }

    // Spring MVC 拦截器
    @Component
    public class UserContextInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            // 从 Token 中解析用户 ID
            String token = request.getHeader("Authorization");
            Long userId = parseUserIdFromToken(token);
            UserContext.setUserId(userId);
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler,
                                    Exception ex) {
            // 请求完成后清理
            UserContext.clear();
        }

        private Long parseUserIdFromToken(String token) {
            // 解析 Token
            return 123456L;
        }
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：ThreadLocal 的 key 为什么会是弱引用？

**答：**

```
原因分析：
1. 避免 ThreadLocal 对象无法回收
   - 如果 key 是强引用，外部引用丢失后
   - ThreadLocalMap 仍持有 key 的强引用
   - ThreadLocal 对象永远无法被 GC

2. 弱引用的好处
   - 外部引用丢失后，key 可以被 GC
   - Entry.key 自动变为 null
   - ThreadLocal 对象可以回收

3. 但 value 仍泄漏
   - value 是强引用
   - 只要 Thread 存活，value 无法回收
   - 需要手动 remove 清理

结论：
- 弱引用是折中方案，减少泄漏风险
- 但不能完全避免泄漏
- 开发者必须手动调用 remove()
```

### 扩展 2：ThreadLocalMap 如何解决哈希冲突？

**答：**

```java
// ThreadLocalMap 使用线性探测法解决哈希冲突

private Entry getEntry(ThreadLocal<?> key) {
    int i = key.threadLocalHashCode & (table.length - 1);
    Entry e = table[i];

    // 如果冲突，向后线性探测
    if (e != null && e.get() == key)
        return e;
    else
        return getEntryAfterMiss(key, i, e);
}

private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
    Entry[] tab = table;
    int len = tab.length;

    // 线性探测：向后查找
    while (e != null) {
        ThreadLocal<?> k = e.get();
        if (k == key)
            return e;
        if (k == null)
            // 遇到过期 Entry，清理并继续
            expungeStaleEntry(i);
        else
            // 向后移动
            i = nextIndex(i, len);
        e = tab[i];
    }
    return null;
}

// 线性探测 vs 链表法
线性探测（ThreadLocalMap）：
- 优点：结构简单，无额外对象开销
- 缺点：哈希冲突多时性能下降（聚集问题）
- 适用：ThreadLocal 数量少的场景

链表法（HashMap）：
- 优点：冲突多时性能稳定
- 缺点：需要额外对象（Node）
- 适用：通用场景
```

### 扩展 3：ThreadLocal 的哈希算法有什么特点？

**答：**

```java
// ThreadLocal 的哈希算法

// 1. 每个 ThreadLocal 有唯一的 hashCode
private final int threadLocalHashCode = nextHashCode();

// 2. 使用魔数生成 hashCode（斐波那契散列）
private static AtomicInteger nextHashCode = new AtomicInteger();
private static final int HASH_INCREMENT = 0x61c88647;  // 魔数

private static int nextHashCode() {
    return nextHashCode.getAndAdd(HASH_INCREMENT);
}

// 3. 计算数组索引
int index = threadLocalHashCode & (table.length - 1);

// 特点分析：
1. 魔数 0x61c88647 是斐波那契数列的散列值
   - 黄金比例：(√5 - 1) / 2 ≈ 0.618
   - 0x61c88647 = 2^32 * 0.618

2. 均匀分布
   - 每次增加魔数，hash 值均匀分布
   - 减少哈希冲突

3. 不需要 rehash
   - HashMap 扩容时需要 rehash
   - ThreadLocalMap 依赖魔数均匀分布
   - 扩容后仍然均匀分布
```

### 扩展 4：线程池场景下 ThreadLocal 有什么特殊问题？

**答：**

```
问题：线程复用导致 ThreadLocal 值污染

场景描述：
1. 线程 A 执行任务 1，设置 ThreadLocal 值
2. 任务 1 完成，忘记 remove
3. 线程 A 回到线程池
4. 线程 A 执行任务 2（另一个请求）
5. 任务 2 获取 ThreadLocal，拿到任务 1 的值！

后果：
- 数据污染：任务 2 拿到任务 1 的数据
- 内存泄漏：value 一直无法回收
- 安全隐患：用户数据可能泄露

解决方案：
1. 必须在 finally 块中 remove
   try {
       threadLocal.set(value);
       // 业务逻辑
   } finally {
       threadLocal.remove();
   }

2. 使用拦截器/过滤器统一清理
   @Override
   public void afterCompletion(...) {
       UserContext.clear();
   }

3. 阿里巴巴规范：强制要求 remove
   《阿里巴巴 Java 开发手册》
   - 【强制】必须调用 remove()
   - 【推荐】使用 try-finally 保证清理
```

### 扩展 5：InheritableThreadLocal 是什么？

**答：**

```java
// InheritableThreadLocal：子线程可以继承父线程的 ThreadLocal 值

// 1. 基本使用
InheritableThreadLocal<String> inheritableLocal =
    new InheritableThreadLocal<>();

// 父线程设置值
inheritableLocal.set("parent value");

// 子线程可以获取到父线程的值
new Thread(() -> {
    String value = inheritableLocal.get();  // "parent value"
    System.out.println(value);
}).start();

// 2. 实现原理
// Thread 类中有两个 ThreadLocalMap
public class Thread {
    ThreadLocal.ThreadLocalMap threadLocals;              // 自己的
    ThreadLocal.ThreadLocalMap inheritableThreadLocals;   // 继承的
}

// 3. 子线程创建时复制父线程的值
private void init(ThreadGroup g, Runnable target, String name, ...) {
    // ...
    if (parent.inheritableThreadLocals != null) {
        // 深拷贝父线程的 inheritableThreadLocals
        this.inheritableThreadLocals =
            ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
    }
}

// 4. 注意事项
// - 线程池场景不适用（线程复用，不是父子关系）
// - 需要使用 TransmittableThreadLocal（阿里开源）
```

---

## 六、面试答题话术

**面试官问：请说说 ThreadLocal 的原理和内存泄漏问题？**

**标准回答：**

> ThreadLocal 用于实现线程隔离，每个线程拥有独立的变量副本。
>
> **底层原理**：
> - 每个 Thread 持有独立的 ThreadLocalMap
> - ThreadLocalMap 是数组结构，存储 Entry（key-value）
> - key 是弱引用的 ThreadLocal 对象，value 是强引用的存储值
> - get/set 操作通过哈希计算索引，线性探测解决冲突
>
> **内存泄漏原因**：
> - key 是弱引用，ThreadLocal 对象被 GC 后变 null
> - value 是强引用，只要 Thread 存活，value 无法回收
> - 线程池场景下线程长期存活，value 一直无法回收
>
> **解决方案**：
> - 使用完后必须调用 remove() 方法清理
> - 建议在 finally 块中调用，保证清理

**加分回答：**

> 补充几点：
> 1. key 设计为弱引用是折中方案，避免 ThreadLocal 对象无法回收
> 2. ThreadLocal 使用魔数 0x61c88647 生成 hashCode，减少哈希冲突
> 3. ThreadLocalMap 用线性探测法解决哈希冲突，不是链表法
> 4. 线程池场景下，线程复用会导致值污染，必须手动 remove
> 5. InheritableThreadLocal 允许子线程继承父线程的值，但线程池场景需要用 TransmittableThreadLocal
> 6. Spring 的 RequestContextHolder、事务管理器都用了 ThreadLocal

---

## 七、速记表格

| 组件 | 说明 |
|------|------|
| Thread | 持有 ThreadLocalMap |
| ThreadLocalMap | 存储 Entry 数组 |
| Entry | key(弱引用) + value(强引用) |
| 哈希冲突 | 线性探测法 |
| 哈希算法 | 魔数 0x61c88647 |
| 内存泄漏 | key=null 后 value 无法回收 |
| 解决方案 | 使用完后调用 remove() |

| 使用场景 | 注意事项 |
|----------|----------|
| 线程安全对象 | 每个线程独立实例 |
| 用户上下文 | 请求结束必须清理 |
| 数据库连接 | 避免连接泄漏 |
| 事务管理 | Spring 已封装好 |
