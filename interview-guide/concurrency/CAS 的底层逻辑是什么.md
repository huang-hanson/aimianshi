# [并发] CAS 的底层逻辑是什么？

## 一、题目分析

### 考察点
- CAS 的定义和工作原理
- CAS 的硬件实现（CPU 指令）
- CAS 的三大问题（ABA、自旋开销、只能保一个变量）
- 原子类的底层实现
- LongAdder 优化原理

### 难度等级
⭐⭐⭐⭐ (4/5 星) - 高频核心题

### 适用岗位
- 中级/高级 Java 开发工程师
- 所有 Java 并发相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> CAS（Compare And Swap）是一种**无锁并发**机制，通过**硬件原子指令**实现。它包含三个操作数：**内存位置 V、预期值 A、新值 B**。只有当内存位置的值等于预期值时，才将内存位置的值更新为新值，否则不更新。整个过程是原子的，由 CPU 保证。

---

## 三、深度剖析

### 3.1 CAS 工作原理

```
┌─────────────────────────────────────────────────────────┐
│              CAS 操作流程图                               │
│                                                          │
│  线程想要更新变量 V                                       │
│           │                                              │
│           ↓                                              │
│  ┌─────────────────────────────────────────┐            │
│  │  CAS(V, A, B)                            │            │
│  │  V = 内存中的实际值                       │            │
│  │  A = 线程期望的值（工作内存中的值）         │            │
│  │  B = 要更新的新值                         │            │
│  └─────────────────────────────────────────┘            │
│           │                                              │
│           ↓ 比较                                         │
│     ┌─────┴─────┐                                       │
│     │           │                                       │
│   V == A      V != A                                    │
│     │           │                                       │
│     ↓           ↓                                       │
│  ┌─────┐   ┌─────────┐                                 │
│  │ V=B │   │ 不更新   │                                 │
│  │成功 │   │ 失败重试 │                                 │
│  └─────┘   └─────────┘                                 │
│                                                          │
│  核心：比较和交换是原子操作，由 CPU 指令保证                  │
└─────────────────────────────────────────────────────────┘
```

### 3.2 CAS 伪代码实现

```java
// CAS 伪代码
boolean compareAndSwap(int V, int A, int B) {
    if (V == A) {
        V = B;  // 原子操作
        return true;
    }
    return false;
}

// 使用 CAS 实现自旋加锁
void spinLock() {
    while (!compareAndSwap(lock, 0, 1)) {
        // 自旋等待
    }
}

void unlock() {
    compareAndSwap(lock, 1, 0);
}
```

### 3.3 Java 中的 CAS 实现

```
┌─────────────────────────────────────────────────────────┐
│          Java CAS 实现层次结构                            │
│                                                          │
│  应用层：Atomic 原子类                                     │
│  ┌─────────────────────────────────────────────────┐    │
│  │ AtomicInteger、AtomicLong、AtomicReference       │    │
│  │ AtomicStampedReference（解决 ABA）               │    │
│  └─────────────────────────────────────────────────┘    │
│                          ↓ 封装                          │
│  核心层：Unsafe 类                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │ compareAndSwapInt()                              │    │
│  │ compareAndSwapLong()                             │    │
│  │ compareAndSwapObject()                           │    │
│  └─────────────────────────────────────────────────┘    │
│                          ↓ 调用                          │
│  硬件层：CPU 原子指令                                        │
│  ┌─────────────────────────────────────────────────┐    │
│  │ x86: CMPXCHG + LOCK 前缀                          │    │
│  │ ARM: LL/SC (Load-Linked / Store-Conditional)    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 3.4 核心源码分析

**AtomicInteger 核心代码：**
```java
public class AtomicInteger extends Number {

    // volatile 保证可见性
    private volatile int value;

    // 获取 unsafe 实例
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long valueOffset;

    // 获取 value 字段的内存偏移量
    static {
        try {
            valueOffset = unsafe.objectFieldOffset
                (AtomicInteger.class.getDeclaredField("value"));
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    // CAS 操作核心方法
    public final boolean compareAndSet(int expect, int update) {
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }

    // 原子自增（核心方法）
    public final int incrementAndGet() {
        for (;;) {
            int current = get();          // 获取当前值
            int next = current + 1;       // 计算新值
            if (compareAndSet(current, next))  // CAS 更新
                return next;
            // 失败则自旋重试
        }
    }

    // 优化版本（使用 intrinsic）
    public final int incrementAndGet() {
        return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
    }
}
```

**Unsafe 类（native 方法）：**
```java
public class Unsafe {

    // CAS 核心 native 方法
    public final native boolean compareAndSwapInt(
        Object var1, long var2, int var4, int var5
    );

    public final native boolean compareAndSwapLong(
        Object var1, long var2, long var4, long var6
    );

    public final native boolean compareAndSwapObject(
        Object var1, long var2, Object var4, Object var5
    );
}
```

### 3.5 CPU 指令实现（x86 架构）

```
┌─────────────────────────────────────────────────────────┐
│          x86 CPU 的 CAS 指令实现                           │
│                                                          │
│  指令：LOCK CMPXCHG                                       │
│                                                          │
│  执行过程：                                                │
│  1. LOCK 前缀锁定总线或缓存行                              │
│  2. CMPXCHG 比较并交换                                     │
│  3. 整个过程不可中断                                       │
│                                                          │
│  汇编代码：                                                │
│  ┌─────────────────────────────────────────────────┐    │
│  │ lock cmpxchg [edx], eax                         │    │
│  │ - lock: 锁定总线/缓存行                          │    │
│  │ - cmpxchg: 比较并交换                            │    │
│  │ - [edx]: 内存位置 V                              │    │
│  │ - eax: 新值 B                                    │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  多核同步机制：                                            │
│  - MESI 缓存一致性协议                                    │
│  - 锁缓存行，其他 CPU 核心等待                               │
│  - 保证多核环境下的原子性                                  │
└─────────────────────────────────────────────────────────┘
```

### 3.6 CAS 三大问题

```
┌─────────────────────────────────────────────────────────┐
│  问题 1：ABA 问题                                          │
│                                                          │
│  场景：                                                   │
│  线程 1: 读取 A → 被挂起                                   │
│  线程 2: A → B → A（值变回 A）                            │
│  线程 1: 恢复，CAS 成功（实际值已变过）                     │
│                                                          │
│  解决：添加版本号/时间戳                                   │
│  - AtomicStampedReference<int, stamp>                     │
│  - 每次更新增加版本号                                     │
│  - CAS 时比较值和版本号                                    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  问题 2：自旋开销大                                        │
│                                                          │
│  场景：                                                   │
│  - CAS 失败后不断重试                                      │
│  - 长时间自旋消耗 CPU 资源                                  │
│  - 竞争激烈时性能下降                                     │
│                                                          │
│  解决：                                                   │
│  - 限制自旋次数                                           │
│  - 自旋 + 阻塞结合                                        │
│  - LongAdder 分段累加                                     │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  问题 3：只能保证一个变量的原子操作                         │
│                                                          │
│  场景：                                                   │
│  - 需要同时更新多个变量                                    │
│  - CAS 无法保证多个变量的原子性                             │
│                                                          │
│  解决：                                                   │
│  - 使用锁（synchronized/ReentrantLock）                  │
│  - AtomicReference 封装多个变量为一个对象                   │
└─────────────────────────────────────────────────────────┘
```

### 3.7 ABA 问题解决

```java
// 1. 普通 AtomicReference（有 ABA 问题）
AtomicReference<Integer> ref = new AtomicReference<>(0);

// 线程 1
int expected = ref.get();  // 0
// 被挂起...

// 线程 2
ref.set(1);  // 0 → 1
ref.set(0);  // 1 → 0

// 线程 1 恢复
ref.compareAndSet(expected, 2);  // 成功！但值已经变过

// 2. AtomicStampedReference（解决 ABA）
AtomicStampedReference<Integer> stampedRef =
    new AtomicStampedReference<>(0, 0);

// 获取当前引用和版本号
int[] stampHolder = new int[1];
Integer expected = stampedRef.get(stampHolder);
int expectedStamp = stampHolder[0];

// 线程 2 修改后
stampedRef.set(1, 1);  // 值变 1，版本号变 1
stampedRef.set(0, 2);  // 值变 0，版本号变 2

// 线程 1 恢复
stampedRef.compareAndSet(
    expected, 2,
    expectedStamp, expectedStamp + 1
);  // 失败！版本号不匹配
```

### 3.8 LongAdder 优化原理

```
┌─────────────────────────────────────────────────────────┐
│          LongAdder 分段累加原理                           │
│                                                          │
│  传统 AtomicLong（高竞争时性能差）                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  所有线程竞争同一个 value                          │    │
│  │  value = 0                                       │    │
│  │     ↑ ↑ ↑ ↑ ↑                                    │    │
│  │  T1 T2 T3 T4 T5  (大量 CAS 失败)                    │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  LongAdder（分段累加，减少竞争）                            │
│  ┌─────────────────────────────────────────────────┐    │
│  │  base = 0（无竞争时累加）                          │    │
│  │     ↑                                            │    │
│  │  竞争时分散到 Cell 数组                             │    │
│  │  ┌─────┬─────┬─────┬─────┐                       │    │
│  │  │Cell0│Cell1│Cell2│Cell3│                       │    │
│  │  │ =5  │ =3  │ =4  │ =2  │                       │    │
│  │  └─────┴─────┴─────┴─────┘                       │    │
│  │     ↑     ↑     ↑     ↑                          │    │
│  │    T1    T2    T3    T4  (各自累加，无竞争)           │    │
│  │                                                   │    │
│  │  sum() = base + Cell0 + Cell1 + Cell2 + Cell3    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

---

## 四、代码示例

### 4.1 AtomicInteger 基本使用

```java
import java.util.concurrent.atomic.*;

public class AtomicIntegerExample {
    public static void main(String[] args) {
        AtomicInteger atomicInt = new AtomicInteger(0);

        // 1. 原子自增
        atomicInt.incrementAndGet();  // 1
        atomicInt.getAndIncrement();  // 返回 1，之后变为 2

        // 2. 原子加法
        atomicInt.addAndGet(5);  // 7
        atomicInt.getAndAdd(3);  // 返回 7，之后变为 10

        // 3. CAS 操作
        boolean success = atomicInt.compareAndSet(10, 20);
        System.out.println("CAS 成功：" + success);  // true
        System.out.println("当前值：" + atomicInt.get());  // 20

        // 4. 失败场景
        success = atomicInt.compareAndSet(10, 30);
        System.out.println("CAS 成功：" + success);  // false（期望值不匹配）
    }
}
```

### 4.2 多线程并发测试

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CASConcurrencyTest {
    // 使用 AtomicInteger
    private static AtomicInteger atomicCount = new AtomicInteger(0);
    // 使用普通 int（线程不安全）
    private static int unsafeCount = 0;

    public static void main(String[] args) throws Exception {
        int threadCount = 100;
        int incrementCount = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // AtomicInteger 测试
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < incrementCount; j++) {
                    atomicCount.incrementAndGet();
                }
                latch.countDown();
            });
        }
        latch.await();
        System.out.println("AtomicInteger 最终结果：" + atomicCount.get());
        // 预期：100000（100 * 1000）

        // 重置
        atomicCount = new AtomicInteger(0);
        unsafeCount = 0;
        latch = new CountDownLatch(threadCount);

        // 普通 int 测试（线程不安全）
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < incrementCount; j++) {
                    unsafeCount++;  // 非原子操作
                }
                latch.countDown();
            });
        }
        latch.await();
        System.out.println("unsafe int 最终结果：" + unsafeCount);
        // 预期：小于 100000（有数据丢失）

        executor.shutdown();
    }
}
```

### 4.3 ABA 问题演示

```java
import java.util.concurrent.atomic.*;

public class ABADemo {
    public static void main(String[] args) throws InterruptedException {
        // 1. 普通 AtomicReference（有 ABA 问题）
        AtomicReference<Integer> ref = new AtomicReference<>(0);

        Thread t1 = new Thread(() -> {
            int expected = ref.get();  // 0
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            // 期望 0 → 1，但中间可能被改过
            boolean success = ref.compareAndSet(expected, 1);
            System.out.println("t1 CAS 成功：" + success);
        });

        Thread t2 = new Thread(() -> {
            ref.set(1);  // 0 → 1
            ref.set(0);  // 1 → 0（变回原值）
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // 2. AtomicStampedReference（解决 ABA）
        AtomicStampedReference<Integer> stampedRef =
            new AtomicStampedReference<>(0, 0);

        int[] stampHolder = new int[1];
        Integer expected = stampedRef.get(stampHolder);
        int expectedStamp = stampHolder[0];

        // 模拟 ABA
        stampedRef.set(1, 1);  // 0 → 1，版本 0 → 1
        stampedRef.set(0, 2);  // 1 → 0，版本 1 → 2

        // CAS 会失败，因为版本号不匹配
        boolean success = stampedRef.compareAndSet(
            expected, 1,
            expectedStamp, expectedStamp + 1
        );
        System.out.println("StampedReference CAS 成功：" + success);  // false
    }
}
```

### 4.4 LongAdder 性能测试

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LongAdderVsAtomicLong {
    public static void main(String[] args) throws Exception {
        int threadCount = 10;
        long incrementCount = 100000L;

        // AtomicLong 测试
        long atomicTime = testAtomicLong(threadCount, incrementCount);
        System.out.println("AtomicLong 耗时：" + atomicTime + "ms");

        // LongAdder 测试
        long adderTime = testLongAdder(threadCount, incrementCount);
        System.out.println("LongAdder 耗时：" + adderTime + "ms");

        // 高并发下 LongAdder 性能更优
    }

    private static long testAtomicLong(int threads, long count) throws Exception {
        AtomicLong atomicLong = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long start = System.currentTimeMillis();
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                for (long j = 0; j < count; j++) {
                    atomicLong.incrementAndGet();
                }
                latch.countDown();
            });
        }
        latch.await();
        long end = System.currentTimeMillis();
        executor.shutdown();
        return end - start;
    }

    private static long testLongAdder(int threads, long count) throws Exception {
        LongAdder adder = new LongAdder();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long start = System.currentTimeMillis();
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                for (long j = 0; j < count; j++) {
                    adder.increment();
                }
                latch.countDown();
            });
        }
        latch.await();
        long end = System.currentTimeMillis();
        executor.shutdown();
        return end - start;
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：CAS 和 synchronized 的区别？

**答：**

| 对比项 | CAS | synchronized |
|--------|-----|--------------|
| **实现方式** | 乐观锁（无锁） | 悲观锁 |
| **底层原理** | CPU 原子指令 | Monitor 对象监视器 |
| **线程阻塞** | 不阻塞（自旋） | 阻塞 |
| **适用场景** | 低竞争、短操作 | 高竞争、长操作 |
| **开销** | 自旋消耗 CPU | 上下文切换开销 |
| **原子性保证** | CPU 指令保证 | JVM 保证 |

**选择建议：**
- 低竞争、简单操作：用 CAS（Atomic 类）
- 高竞争、复杂操作：用 synchronized

### 扩展 2：volatile 和 CAS 的关系？

**答：**

```
volatile 的作用：
1. 保证可见性（一个线程修改，其他线程立即可见）
2. 禁止指令重排序
3. 不保证原子性

CAS 需要 volatile 配合：
- CAS 操作需要读取最新值（可见性）
- volatile 保证读取的是主内存最新值
- CAS 保证更新的原子性

示例：
volatile int value;  // volatile 保证可见性

// CAS 保证原子性
unsafe.compareAndSwapInt(this, valueOffset, expect, update);

结论：volatile + CAS = 无锁并发方案
```

### 扩展 3：什么是自旋锁？CAS 如何实现自旋锁？

**答：**

```java
// 自旋锁实现
public class SpinLock {
    // 使用 AtomicReference 持有锁的线程
    private AtomicReference<Thread> owner = new AtomicReference<>();

    // 加锁
    public void lock() {
        Thread current = Thread.currentThread();
        // 自旋直到获取锁
        while (!owner.compareAndSet(null, current)) {
            // 空循环，自旋等待
        }
    }

    // 解锁
    public void unlock() {
        Thread current = Thread.currentThread();
        owner.compareAndSet(current, null);
    }
}

// 使用
SpinLock lock = new SpinLock();
lock.lock();
try {
    // 临界区
} finally {
    lock.unlock();
}
```

**特点：**
- 不阻塞线程，减少上下文切换
- 适合临界区代码执行时间短的场景
- 长时间自旋会浪费 CPU

### 扩展 4：JDK 1.8 中 AtomicInteger 的 incrementAndGet 优化？

**答：**

```java
// JDK 1.7 及之前
public final int incrementAndGet() {
    for (;;) {
        int current = get();
        int next = current + 1;
        if (compareAndSet(current, next))
            return next;
    }
}

// JDK 1.8 优化（使用 intrinsic）
public final int incrementAndGet() {
    return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
}

// Unsafe 的 getAndAddInt（JDK 1.8）
public final int getAndAddInt(Object var1, long var2, int var4) {
    int var5;
    do {
        var5 = this.getIntVolatile(var1, var2);  // 获取 volatile 值
    } while (!this.compareAndSwapInt(var1, var2, var5, var5 + var4));
    return var5;
}
```

**优化点：**
- 使用 `getAndAddInt` 内部方法
- 减少方法调用开销
- JVM 内置 intrinsic 优化

### 扩展 5：什么时候用 LongAdder 而不是 AtomicLong？

**答：**

```
使用 AtomicLong 的场景：
- 低并发场景
- 需要实时准确的值
- 代码简单，兼容性好

使用 LongAdder 的场景：
- 高并发场景（线程数>10）
- 频繁累加操作
- 可以接受最终一致性（sum() 是近似值）

性能对比：
- 低并发：两者性能接近
- 高并发：LongAdder 性能优于 AtomicLong（2-10 倍）

原因：
- AtomicLong：所有线程竞争一个 value，大量 CAS 失败
- LongAdder：分段累加，减少竞争
```

---

## 六、面试答题话术

**面试官问：请说说 CAS 的底层逻辑？**

**标准回答：**

> CAS（Compare And Swap）是一种无锁并发机制，包含三个操作数：内存位置 V、预期值 A、新值 B。只有当内存位置的值等于预期值时，才将值更新为新值，否则不更新。
>
> **底层实现**：
> 1. Java 层面通过 Unsafe 类的 native 方法实现
> 2. 硬件层面通过 CPU 的原子指令实现（x86 是 LOCK CMPXCHG 指令）
> 3. LOCK 前缀锁定总线或缓存行，保证多核环境下的原子性
>
> **常见问题**：
> 1. ABA 问题：用 AtomicStampedReference 添加版本号解决
> 2. 自旋开销：限制自旋次数或用 LongAdder 分段累加
> 3. 单变量限制：多变量用锁或 AtomicReference 封装

**加分回答：**

> 补充几点：
> 1. CAS 需要 volatile 配合，保证可见性
> 2. JDK 1.8 的 AtomicInteger 使用 getAndAddInt 优化，减少方法调用
> 3. 高并发场景下 LongAdder 性能优于 AtomicLong，因为分段累加减少竞争
> 4. 自旋锁适合临界区短的场景，长时间自旋浪费 CPU
> 5. CAS 是乐观锁思想，synchronized 是悲观锁，两者适用场景不同

---

## 七、速记表格

| 特性 | 说明 |
|------|------|
| 全称 | Compare And Swap |
| 操作数 | V（内存值）、A（期望值）、B（新值） |
| 底层指令 | x86: LOCK CMPXCHG |
| Java 实现 | Unsafe.compareAndSwapInt() |
| 原子性保证 | CPU 指令 |
| 可见性保证 | volatile |
| 三大问题 | ABA、自旋开销、单变量限制 |
| 解决方案 | 版本号、LongAdder、锁 |
