# [并发] Synchronized、ReentrantLock 的实现原理和区别

## 一、题目分析

### 考察点
- synchronized 的底层实现原理
- ReentrantLock 的底层实现原理
- 两者的区别对比
- 锁升级过程（偏向锁→轻量级锁→重量级锁）
- AQS 队列同步器原理

### 难度等级
⭐⭐⭐⭐⭐ (5/5 星) - 高频核心难题

### 适用岗位
- 中级/高级 Java 开发工程师
- 所有 Java 并发相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> **synchronized** 是 JVM 层面的关键字，通过**对象监视器（Monitor）**实现，锁升级过程为**偏向锁→轻量级锁→重量级锁**；**ReentrantLock** 是 API 层面的类，基于**AQS（队列同步器）+ CAS**实现。两者都是可重入锁，但 ReentrantLock 功能更灵活（支持公平锁、可中断、多条件变量）。

---

## 三、深度剖析

### 3.1 整体对比表

| 对比项 | synchronized | ReentrantLock |
|--------|--------------|---------------|
| **实现层面** | JVM 层面（关键字） | API 层面（类） |
| **底层原理** | Monitor（对象头） | AQS + CAS |
| **锁释放** | 自动释放 | 手动释放（unlock()） |
| **公平锁** | ❌ 只支持非公平 | ✅ 支持公平/非公平 |
| **可中断** | ❌ 不可中断 | ✅ 支持可中断 |
| **多条件变量** | ❌ 单一条件 | ✅ 多个 Condition |
| **尝试获取锁** | ❌ 不支持 | ✅ tryLock() |
| **绑定线程** | ❌ 任意线程 | ✅ getOwner() |
| **性能** | JDK 1.6+ 优化后接近 | 略高（竞争激烈时） |
| **适用场景** | 一般同步场景 | 需要灵活控制的场景 |

### 3.2 synchronized 实现原理

```
┌─────────────────────────────────────────────────────────┐
│          synchronized 锁升级过程                          │
│                                                          │
│  无锁 → 偏向锁 → 轻量级锁 → 重量级锁                       │
│   │      │         │          │                         │
│   │      │         │          └─> 多线程竞争              │
│   │      │         │              自旋等待                │
│   │      │         │              阻塞队列                │
│   │      │         │                                      │
│   │      │         └─> CAS 竞争失败                         │
│   │      │             自旋等待                           │
│   │      │                                                │
│   │      └─> 有线程使用，记录线程 ID                        │
│   │          偏向该线程，无竞争                            │
│   │                                                        │
│   └─> 对象头 Mark Word 记录锁信息                           │
│                                                          │
│  目的：减少锁操作开销，提升性能                             │
└─────────────────────────────────────────────────────────┘
```

### 3.3 Java 对象头结构

```
┌─────────────────────────────────────────────────────────┐
│              Java 对象头（64 位 JVM）                       │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Mark Word (64 位)                                  │    │
│  │ ┌─────────────────────────────────────────────┐  │    │
│  │ │ 25 位：对象哈希码                            │  │    │
│  │ │ 4 位：分代年龄                               │  │    │
│  │ │ 1 位：偏向锁标志                             │  │    │
│  │ │ 2 位：锁标志位                              │  │    │
│  │ │ 32 位：线程 ID（偏向锁）/ 指针（锁）          │  │    │
│  │ └─────────────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Klass Pointer (32/64 位)                          │    │
│  │ 指向类元数据的指针                                 │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  锁信息存储在 Mark Word 中，随锁状态变化                     │
└─────────────────────────────────────────────────────────┘
```

### 3.4 锁升级详解

```
┌─────────────────────────────────────────────────────────┐
│  1. 偏向锁（无竞争）                                      │
│                                                          │
│  Mark Word: [线程 ID | 偏向时间戳 | 锁标志：01 | 偏向：1]   │
│                                                          │
│  特点：                                                   │
│  - 第一个获取锁的线程，记录线程 ID                          │
│  - 之后该线程进入/退出同步块无需 CAS                         │
│  - 只有遇到其他线程竞争，才撤销偏向                         │
│                                                          │
│  适用：单线程访问同步块                                    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  2. 轻量级锁（轻微竞争）                                   │
│                                                          │
│  线程栈帧：                                               │
│  ┌─────────────────┐                                     │
│  │ Lock Record     │ → 存储对象头副本                     │
│  └─────────────────┘                                     │
│           ↓ CAS                                           │
│  对象头：[Lock Record 指针 | 锁标志：00]                    │
│                                                          │
│  特点：                                                   │
│  - 线程通过 CAS 尝试获取锁                                  │
│  - 失败则自旋等待（不阻塞）                                │
│  - 自旋一定次数后升级为重量级锁                            │
│                                                          │
│  适用：线程交替执行，不激烈竞争                            │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  3. 重量级锁（激烈竞争）                                   │
│                                                          │
│  对象头：[Monitor 指针 | 锁标志：10]                        │
│           ↓                                               │
│  ObjectMonitor（C++ 实现）                                │
│  ┌─────────────────────────────────────────┐            │
│  │ _owner       : 持有锁的线程              │            │
│  │ _count       : 重入次数                  │            │
│  │ _recursions  : 重入次数记录              │            │
│  │ _WaitSet     : 等待队列（wait）          │            │
│  │ _EntryList   : 竞争队列（阻塞）           │            │
│  └─────────────────────────────────────────┘            │
│                                                          │
│  特点：                                                   │
│  - 线程阻塞，交给操作系统处理                              │
│  - 开销大，性能低                                         │
│  - 适合竞争激烈的场景                                    │
└─────────────────────────────────────────────────────────┘
```

### 3.5 ReentrantLock 实现原理（AQS）

```
┌─────────────────────────────────────────────────────────┐
│              AQS（AbstractQueuedSynchronizer）结构        │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ volatile int state      // 同步状态（0=无锁）     │    │
│  └─────────────────────────────────────────────────┘    │
│                          ↓                               │
│  ┌─────────────────────────────────────────────────┐    │
│  │              CLH 双向队列                         │    │
│  │  ┌─────┐    ┌─────┐    ┌─────┐                  │    │
│  │  │ Head│ ←→ │Node1│ ←→ │Node2│ → ...           │    │
│  │  │     │    │     │    │     │                  │    │
│  │  └─────┘    └─────┘    └─────┘                  │    │
│  │             ↓          ↓                         │    │
│  │          等待线程   等待线程                      │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  Node 结构：                                              │
│  - thread: 等待的线程                                     │
│  - waitStatus: 等待状态（CANCELLED/SIGNAL/CONDITION）     │
│  - prev/next: 前后节点指针                                │
│                                                          │
│  核心思想：state=0 时无锁，state>0 时持有锁，值=重入次数       │
└─────────────────────────────────────────────────────────┘
```

### 3.6 ReentrantLock 加锁/解锁流程

```
┌─────────────────────────────────────────────────────────┐
│              加锁流程（非公平锁）                          │
│                                                          │
│  1. 尝试 CAS 获取锁（state=0 → state=1）                   │
│     ├─> 成功：设置 owner 为当前线程，返回                  │
│     └─> 失败：进入下一步                                  │
│                                                          │
│  2. 判断是否是当前线程（可重入）                           │
│     ├─> 是：state++，返回                                 │
│     └─> 否：进入下一步                                    │
│                                                          │
│  3. 构造 Node 节点，加入 CLH 队列尾部                        │
│                                                          │
│  4. 阻塞线程（LockSupport.park()）                        │
│                                                          │
│  5. 被唤醒后，重新尝试获取锁                              │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              解锁流程                                     │
│                                                          │
│  1. 判断是否是当前线程持有锁                              │
│     ├─> 否：抛 IllegalMonitorStateException              │
│     └─> 是：进入下一步                                    │
│                                                          │
│  2. state--                                              │
│     ├─> state > 0：重入锁，返回                           │
│     └─> state = 0：完全释放，进入下一步                   │
│                                                          │
│  3. 唤醒后继节点（LockSupport.unpark()）                  │
│                                                          │
│  4. 设置 owner = null                                    │
└─────────────────────────────────────────────────────────┘
```

### 3.7 源码核心片段

**synchronized 字节码：**
```java
// Java 代码
public synchronized void method() {
    // ...
}

// 字节码
public synchronized void method();
  flags: ACC_SYNCHRONIZED  // 标志位，JVM 识别

// 同步代码块
public void method() {
    synchronized (obj) {
        // ...
    }
}

// 字节码
monitorenter  // 进入监视器
// ...
monitorexit   // 退出监视器
```

**ReentrantLock 核心代码：**
```java
// ReentrantLock 内部类
static final class NonfairSync extends Sync {
    final void lock() {
        // 1. CAS 尝试获取锁
        if (compareAndSetState(0, 1))
            setExclusiveOwnerThread(Thread.currentThread());
        else
            // 2. 失败则入队
            acquire(1);
    }
}

// AQS 的 acquire 方法
public final void acquire(int arg) {
    if (!tryAcquire(arg) &&
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
        selfInterrupt();
}

// 尝试获取锁（非公平）
final boolean nonfairTryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    if (c == 0) {
        // CAS 获取锁
        if (compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    } else if (current == getExclusiveOwnerThread()) {
        // 可重入
        int nextc = c + acquires;
        setState(nextc);
        return true;
    }
    return false;
}
```

---

## 四、代码示例

### 4.1 synchronized 基本使用

```java
public class SynchronizedExample {

    // 1. 同步实例方法（锁 this）
    public synchronized void instanceMethod() {
        System.out.println("同步实例方法");
    }

    // 2. 同步静态方法（锁 Class 对象）
    public static synchronized void staticMethod() {
        System.out.println("同步静态方法");
    }

    // 3. 同步代码块（锁指定对象）
    private final Object lock = new Object();

    public void blockMethod() {
        synchronized (lock) {
            System.out.println("同步代码块");
        }
    }

    // 4. 锁升级演示
    public void lockUpgradeDemo() {
        // 偏向锁：单线程访问
        synchronized (this) {
            // 代码
        }

        // 轻量级锁：多线程交替访问
        // 重量级锁：多线程激烈竞争
    }
}
```

### 4.2 ReentrantLock 基本使用

```java
import java.util.concurrent.locks.*;

public class ReentrantLockExample {
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantLock fairLock = new ReentrantLock(true);  // 公平锁

    // 1. 基本加锁/解锁
    public void basicLock() {
        lock.lock();
        try {
            System.out.println("获取锁成功");
        } finally {
            lock.unlock();  // 必须手动释放
        }
    }

    // 2. 可中断锁
    public void interruptibleLock() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            System.out.println("获取锁成功（可中断）");
        } finally {
            lock.unlock();
        }
    }

    // 3. 尝试获取锁
    public void tryLock() {
        if (lock.tryLock()) {
            try {
                System.out.println("获取锁成功");
            } finally {
                lock.unlock();
            }
        } else {
            System.out.println("获取锁失败，执行其他逻辑");
        }
    }

    // 4. 带超时尝试获取锁
    public void tryLockWithTimeout() throws InterruptedException {
        if (lock.tryLock(1, TimeUnit.SECONDS)) {
            try {
                System.out.println("获取锁成功");
            } finally {
                lock.unlock();
            }
        } else {
            System.out.println("超时未获取到锁");
        }
    }
}
```

### 4.3 Condition 多条件变量

```java
import java.util.concurrent.locks.*;

public class ConditionExample {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Object[] items = new Object[10];
    private int count = 0;

    // 生产者
    public void produce(Object item) throws InterruptedException {
        lock.lock();
        try {
            // 等待不满条件
            while (count == items.length) {
                notFull.await();  // 释放锁，进入等待
            }
            items[count++] = item;
            // 唤醒消费者
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    // 消费者
    public Object consume() throws InterruptedException {
        lock.lock();
        try {
            // 等待不空条件
            while (count == 0) {
                notEmpty.await();
            }
            Object item = items[--count];
            // 唤醒生产者
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }
}
```

### 4.4 公平锁 vs 非公平锁

```java
public class FairLockTest {
    public static void main(String[] args) {
        // 1. 非公平锁（默认）
        ReentrantLock nonFairLock = new ReentrantLock(false);

        // 2. 公平锁
        ReentrantLock fairLock = new ReentrantLock(true);

        // 公平锁测试
        testFairLock(fairLock);
    }

    private static void testFairLock(ReentrantLock lock) {
        Runnable task = () -> {
            lock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 获取锁");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        };

        // 创建多个线程
        for (int i = 0; i < 5; i++) {
            new Thread(task, "Thread-" + i).start();
        }
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：synchronized 和 ReentrantLock 性能对比？

**答：**

```
JDK 1.6 之前：
- synchronized 性能较差（只有重量级锁）
- ReentrantLock 性能明显优于 synchronized

JDK 1.6 及之后：
- synchronized 引入锁升级（偏向锁→轻量级→重量级）
- 两者性能接近，低竞争下 synchronized 略优
- 高竞争下 ReentrantLock 略优

建议：
- 一般场景：优先用 synchronized（代码简洁）
- 需要灵活控制：用 ReentrantLock
```

### 扩展 2：什么是锁饥饿？公平锁能解决吗？

**答：**

```
锁饥饿：
- 某些线程长时间无法获取到锁
- 非公平锁可能导致后请求的线程先获取锁

公平锁解决：
- 按照请求顺序分配锁
- 避免饥饿，但吞吐量下降

性能对比：
- 公平锁：吞吐量低（频繁切换）
- 非公平锁：吞吐量高（减少切换）

原因：
- 公平锁每次都要检查队列
- 非公平锁允许插队，减少上下文切换
```

### 扩展 3：synchronized 是可重入锁吗？如何证明？

**答：** 是可重入锁。

```java
// 证明 synchronized 可重入
public class ReentrantDemo {
    public synchronized void methodA() {
        System.out.println("methodA");
        methodB();  // 同一线程可以再次获取锁
    }

    public synchronized void methodB() {
        System.out.println("methodB");
    }

    // 证明 ReentrantLock 可重入
    private ReentrantLock lock = new ReentrantLock();

    public void lockA() {
        lock.lock();
        try {
            System.out.println("lockA");
            lockB();  // 同一线程可以再次获取锁
        } finally {
            lock.unlock();
        }
    }

    public void lockB() {
        lock.lock();
        try {
            System.out.println("lockB");
        } finally {
            lock.unlock();  // 需要 unlock 两次
        }
    }
}
```

**原理：**
- synchronized：对象头记录重入次数
- ReentrantLock：state 记录重入次数，owner 记录持有线程

### 扩展 4：什么是 AQS？还有哪些类基于 AQS 实现？

**答：**

```
AQS（AbstractQueuedSynchronizer）：
- Java 并发包的核心基础组件
- 提供了一套通用的锁和同步器框架
- 核心：state（状态）+ CLH 队列（等待队列）

基于 AQS 实现的类：
1. ReentrantLock（独占锁）
2. ReentrantReadWriteLock（读写锁）
3. CountDownLatch（倒计时门闩）
4. CyclicBarrier（循环屏障）
5. Semaphore（信号量）
6. SynchronousQueue（同步队列）
```

### 扩展 5：synchronized 锁升级的触发条件？

**答：**

```
1. 无锁 → 偏向锁
   - 第一次有线程获取锁
   - 记录线程 ID 到对象头

2. 偏向锁 → 轻量级锁
   - 其他线程尝试获取锁
   - 撤销偏向，CAS 竞争

3. 轻量级锁 → 重量级锁
   - CAS 竞争失败
   - 自旋一定次数（默认 10 次）
   - 或竞争激烈（自适应自旋）

JVM 参数：
- -XX:+UseBiasedLocking：启用偏向锁
- -XX:BiasedLockingStartupDelay=0：无延迟
- -XX:+PrintGCDetails：查看锁信息
```

---

## 六、面试答题话术

**面试官问：请说说 synchronized 和 ReentrantLock 的区别？**

**标准回答：**

> 两者都是可重入锁，但有以下区别：
>
> 1. **实现层面**：synchronized 是 JVM 关键字，ReentrantLock 是 API 类
> 2. **底层原理**：synchronized 通过对象监视器（Monitor）实现，有偏向锁→轻量级锁→重量级锁的升级过程；ReentrantLock 基于 AQS + CAS 实现
> 3. **锁释放**：synchronized 自动释放，ReentrantLock 需要手动 unlock()
> 4. **功能特性**：ReentrantLock 支持公平锁、可中断、多条件变量、尝试获取锁；synchronized 只支持非公平锁
> 5. **性能**：JDK 1.6+ 后两者性能接近
>
> **使用建议**：一般场景用 synchronized（代码简洁），需要灵活控制用 ReentrantLock。

**加分回答：**

> 补充几点底层细节：
> 1. synchronized 的锁信息存在对象头的 Mark Word 中，随锁状态变化
> 2. ReentrantLock 的 state 变量记录重入次数，owner 记录持有线程
> 3. ReentrantLock 的公平锁按 FIFO 顺序，非公平锁允许插队，吞吐量更高
> 4. ReentrantLock 可以绑定多个 Condition，实现精细的线程通信
> 5. synchronized 在字节码层面是 monitorenter 和 monitorexit 指令
> 6. AQS 是并发包的核心，CountDownLatch、Semaphore 等都基于它实现

---

## 七、速记表格

| 特性 | synchronized | ReentrantLock |
|------|--------------|---------------|
| 实现层面 | JVM | API |
| 底层原理 | Monitor | AQS + CAS |
| 锁释放 | 自动 | 手动 |
| 公平锁 | ❌ | ✅ |
| 可中断 | ❌ | ✅ |
| 多 Condition | ❌ | ✅ |
| tryLock | ❌ | ✅ |
| 锁升级 | ✅ | ❌ |
| 性能 | 接近 | 接近 |
