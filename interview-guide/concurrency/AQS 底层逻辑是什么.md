# [并发] AQS 底层逻辑是什么？

## 一、题目分析

### 考察点
- AQS 的定义和作用
- AQS 的数据结构（state + CLH 队列）
- AQS 的工作原理（资源共享、线程排队、阻塞/唤醒）
- AQS 实现的同步组件
- ReentrantLock 基于 AQS 的实现

### 难度等级
⭐⭐⭐⭐⭐ (5/5 星) - 高频核心难题

### 适用岗位
- 中级/高级 Java 开发工程师
- 所有 Java 并发相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> AQS（AbstractQueuedSynchronizer）是 Java 并发包的**核心基础框架**，用于构建锁和同步器。它通过**state 变量**表示同步状态，通过**FIFO 双向队列**管理等待线程，使用**CAS + LockSupport**实现线程的阻塞和唤醒。ReentrantLock、CountDownLatch、Semaphore 等都基于 AQS 实现。

---

## 三、深度剖析

### 3.1 AQS 整体架构

```
┌─────────────────────────────────────────────────────────┐
│              AQS 架构全景图                               │
│                                                          │
│  上层同步组件（基于 AQS 实现）                              │
│  ┌─────────────┬─────────────┬─────────────┐            │
│  │ReentrantLock│CountDownLatch│ Semaphore   │            │
│  └─────────────┴─────────────┴─────────────┘            │
│                          ↓ 继承/组合                       │
│  ┌─────────────────────────────────────────────────┐    │
│  │           AQS（AbstractQueuedSynchronizer）      │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │  volatile int state   // 同步状态        │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  │  ┌─────────────────────────────────────────┐    │    │
│  │  │           CLH 双向队列                    │    │    │
│  │  │  ┌─────┐    ┌─────┐    ┌─────┐         │    │    │
│  │  │  │Head │ ←→ │Node1│ ←→ │Node2│ → ...  │    │    │
│  │  │  └─────┘    └─────┘    └─────┘         │    │    │
│  │  └─────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────┘    │
│                          ↓ 底层支撑                        │
│  ┌─────────────────────────────────────────────────┐    │
│  │  CAS + LockSupport.park()/unpark()              │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  核心思想：state=0 无锁，state>0 有锁，值=重入次数/资源数      │
└─────────────────────────────────────────────────────────┘
```

### 3.2 AQS 核心数据结构

```
┌─────────────────────────────────────────────────────────┐
│  1. state 同步状态                                       │
│                                                          │
│  private volatile int state;                             │
│                                                          │
│  含义（根据不同同步组件变化）：                             │
│  - ReentrantLock: state=0 无锁，state>0 持有锁，值=重入次数  │
│  - CountDownLatch: state=剩余次数，state=0 时放行          │
│  - Semaphore: state=可用许可数                            │
│  - ReentrantReadWriteLock: 高 16 位读锁，低 16 位写锁         │
│                                                          │
│  操作方式：                                                │
│  - getState(): 获取 state                                 │
│  - setState(int): 设置 state                             │
│  - compareAndSetState(int, int): CAS 更新 state           │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  2. CLH 双向队列（等待队列）                               │
│                                                          │
│  队列结构：                                               │
│  ┌─────┐    ┌───────────────┐    ┌───────────────┐     │
│  │Head │ ←→ │    Node 1     │ ←→ │    Node 2     │ ... │
│  │null │    │  thread: T1   │    │  thread: T2   │     │
│  └─────┘    │waitStatus: 0  │    │waitStatus: 0  │     │
│             └───────────────┘    └───────────────┘     │
│                                                          │
│  Node 核心字段：                                          │
│  - Thread thread: 等待的线程                              │
│  - int waitStatus: 等待状态                              │
│  - Node prev/next: 前后节点指针                          │
│  - Node nextWaiter: 条件队列中的下一个节点                │
│                                                          │
│  waitStatus 状态值：                                      │
│  - 0: 初始状态                                           │
│  - 1 (CANCELLED): 线程已取消/超时/中断                    │
│  - -1 (SIGNAL): 后继节点需要被唤醒                        │
│  - -2 (CONDITION): 条件队列等待                          │
│  - -3 (PROPAGATE): 释放操作需要传递                       │
└─────────────────────────────────────────────────────────┘
```

### 3.3 AQS 两种同步模式

```
┌─────────────────────────────────────────────────────────┐
│  独占模式（Exclusive）                                   │
│  只有一个线程能获取同步状态                               │
│                                                          │
│  示例：ReentrantLock、ReentrantReadWriteLock 的写锁         │
│                                                          │
│  获取锁流程：                                            │
│  1. tryAcquire(1) 尝试获取                               │
│  2. 失败则 addWaiter(Node.EXCLUSIVE) 入队               │
│  3. acquireQueued() 自旋等待                             │
│  4. 前驱节点是 head 且 tryAcquire 成功，出队              │
│  5. LockSupport.park() 阻塞                              │
│                                                          │
│  释放锁流程：                                            │
│  1. tryRelease(1) 释放                                   │
│  2. state=0，完全释放                                    │
│  3. unparkSuccessor() 唤醒后继                           │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  共享模式（Shared）                                      │
│  多个线程可以同时获取同步状态                             │
│                                                          │
│  示例：CountDownLatch、Semaphore、ReentrantReadWriteLock 读锁 │
│                                                          │
│  获取锁流程：                                            │
│  1. tryAcquireShared(1) 尝试获取                         │
│  2. 返回值>=0 表示成功，<0 表示失败                        │
│  3. 失败则 doAcquireShared() 入队等待                    │
│  4. 成功则 doReleaseShared() 唤醒后继                    │
│                                                          │
│  释放锁流程：                                            │
│  1. tryReleaseShared(1) 释放                             │
│  2. doReleaseShared() 传播释放                           │
│  3. 唤醒所有等待节点（头节点传播）                         │
└─────────────────────────────────────────────────────────┘
```

### 3.4 AQS 核心方法模板

```java
// AQS 提供的模板方法（子类重写）

// 1. 独占模式
protected boolean tryAcquire(int arg) {
    throw new UnsupportedOperationException();
}

protected boolean tryRelease(int arg) {
    throw new UnsupportedOperationException();
}

// 2. 共享模式
protected int tryAcquireShared(int arg) {
    throw new UnsupportedOperationException();
}

protected boolean tryReleaseShared(int arg) {
    throw new UnsupportedOperationException();
}

// 3. 查询当前线程是否持有同步状态
protected boolean isHeldExclusively() {
    throw new UnsupportedOperationException();
}
```

**AQS 设计思想：**
- AQS 定义了同步器的**通用框架**（队列管理、阻塞/唤醒）
- 子类只需实现**tryAcquire/tryRelease**等核心逻辑
- 这就是**模板方法模式**的典型应用

### 3.5 ReentrantLock 基于 AQS 的实现

```java
// ReentrantLock 内部同步器
abstract class Sync extends AbstractQueuedSynchronizer {
    // 独占获取锁
    abstract void lock();

    // 尝试获取锁（非公平实现）
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

    // 尝试释放锁
    protected final boolean tryRelease(int releases) {
        int c = getState() - releases;
        if (Thread.currentThread() != getExclusiveOwnerThread())
            throw new IllegalMonitorStateException();
        boolean free = false;
        if (c == 0) {
            free = true;
            setExclusiveOwnerThread(null);  // 完全释放
        }
        setState(c);
        return free;
    }
}

// 非公平锁
static final class NonfairSync extends Sync {
    final void lock() {
        if (compareAndSetState(0, 1))
            setExclusiveOwnerThread(Thread.currentThread());
        else
            acquire(1);  // AQS 的 acquire 方法
    }

    protected final boolean tryAcquire(int acquires) {
        return nonfairTryAcquire(acquires);
    }
}

// 公平锁
static final class FairSync extends Sync {
    final void lock() {
        acquire(1);
    }

    protected final boolean tryAcquire(int acquires) {
        final Thread current = Thread.currentThread();
        int c = getState();
        if (c == 0) {
            // 公平锁：检查队列是否有等待节点
            if (!hasQueuedPredecessors() &&
                compareAndSetState(0, acquires)) {
                setExclusiveOwnerThread(current);
                return true;
            }
        } else if (current == getExclusiveOwnerThread()) {
            int nextc = c + acquires;
            setState(nextc);
            return true;
        }
        return false;
    }
}
```

### 3.6 AQS 加锁/解锁完整流程

```
┌─────────────────────────────────────────────────────────┐
│              AQS 加锁流程（独占模式）                      │
│                                                          │
│  acquire(int arg)                                        │
│       │                                                  │
│       ↓                                                  │
│  ┌─────────────────┐                                    │
│  │ tryAcquire(arg) │ 尝试获取锁                          │
│  └────────┬────────┘                                    │
│           │                                              │
│     ┌─────┴─────┐                                       │
│     │           │                                       │
│   成功        失败                                      │
│     │           │                                       │
│     │           ↓                                       │
│     │    ┌─────────────────┐                           │
│     │    │ addWaiter()     │ 构造 Node，加入队列尾部      │
│     │    └────────┬────────┘                           │
│     │             │                                     │
│     │             ↓                                     │
│     │    ┌─────────────────┐                           │
│     │    │acquireQueued()  │ 自旋尝试获取锁               │
│     │    └────────┬────────┘                           │
│     │             │                                     │
│     │       ┌─────┴─────┐                               │
│     │       │           │                               │
│     │     成功        失败                              │
│     │       │           │                               │
│     │       │           ↓                               │
│     │       │    ┌─────────────────┐                   │
│     │       │    │parkAndCheck()   │ 阻塞线程            │
│     │       │    └─────────────────┘                   │
│     │       │           │                               │
│     │       │           ↓ 被唤醒                        │
│     │       └───────←───┘                               │
│     │                                                   │
│     ↓                                                   │
│  获取锁成功                                             │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              AQS 解锁流程（独占模式）                      │
│                                                          │
│  release(int arg)                                        │
│       │                                                  │
│       ↓                                                  │
│  ┌─────────────────┐                                    │
│  │ tryRelease(arg) │ 尝试释放锁                          │
│  └────────┬────────┘                                    │
│           │                                              │
│           ↓                                              │
│  state = state - arg                                     │
│           │                                              │
│           ↓                                              │
│      state == 0 ?                                        │
│           │                                              │
│     ┌─────┴─────┐                                       │
│     │           │                                       │
│   是          否                                        │
│     │           │                                       │
│     ↓           ↓                                       │
│  ┌─────────┐   返回（未完全释放）                         │
│  │unpark...│                                            │
│  │唤醒后继 │                                            │
│  └─────────┘                                            │
└─────────────────────────────────────────────────────────┘
```

---

## 四、代码示例

### 4.1 自定义同步器（基于 AQS）

```java
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 自定义独占锁（不可重入）
 */
public class OneShotLock {
    private static class Sync extends AbstractQueuedSynchronizer {
        @Override
        protected boolean tryAcquire(int acquires) {
            // CAS 获取锁，state 从 0 → 1
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        @Override
        protected boolean tryRelease(int releases) {
            if (getState() == 0) {
                throw new IllegalMonitorStateException();
            }
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        @Override
        protected boolean isHeldExclusively() {
            return getState() == 1;
        }
    }

    private final Sync sync = new Sync();

    public void lock() {
        sync.acquire(1);
    }

    public void unlock() {
        sync.release(1);
    }

    public boolean isLocked() {
        return sync.isHeldExclusively();
    }

    // 测试
    public static void main(String[] args) {
        OneShotLock lock = new OneShotLock();

        Runnable task = () -> {
            lock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 获取锁");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
                System.out.println(Thread.currentThread().getName() + " 释放锁");
            }
        };

        for (int i = 0; i < 3; i++) {
            new Thread(task, "Thread-" + i).start();
        }
    }
}
```

### 4.2 自定义 CountDownLatch

```java
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 自定义 CountDownLatch（基于 AQS 共享模式）
 */
public class MyCountDownLatch {
    private static class Sync extends AbstractQueuedSynchronizer {
        Sync(int count) {
            setState(count);
        }

        int getCount() {
            return getState();
        }

        @Override
        protected int tryAcquireShared(int acquires) {
            // state=0 时获取成功，返回 1
            // state>0 时获取失败，返回 -1
            return getState() == 0 ? 1 : -1;
        }

        @Override
        protected boolean tryReleaseShared(int releases) {
            for (;;) {
                int c = getState();
                if (c == 0) {
                    return false;  // 已经释放完毕
                }
                int nextc = c - 1;
                if (compareAndSetState(c, nextc)) {
                    // 释放到 0 时，返回 true 唤醒等待线程
                    return nextc == 0;
                }
            }
        }
    }

    private final Sync sync;

    public MyCountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }

    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    public void countDown() {
        sync.releaseShared(1);
    }

    public long getCount() {
        return sync.getCount();
    }

    // 测试
    public static void main(String[] args) throws InterruptedException {
        MyCountDownLatch latch = new MyCountDownLatch(3);

        // 3 个工作线程
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                System.out.println(Thread.currentThread().getName() + " 开始工作");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                latch.countDown();
                System.out.println(Thread.currentThread().getName() + " 完成工作");
            }).start();
        }

        // 主线程等待
        System.out.println("主线程等待所有工作完成...");
        latch.await();
        System.out.println("所有工作完成，主线程继续执行");
    }
}
```

### 4.3 自定义 Semaphore

```java
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 自定义 Semaphore（基于 AQS 共享模式）
 */
public class MySemaphore {
    private static class Sync extends AbstractQueuedSynchronizer {
        Sync(int permits) {
            setState(permits);
        }

        int getPermits() {
            return getState();
        }

        @Override
        protected int tryAcquireShared(int acquires) {
            for (;;) {
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 || compareAndSetState(available, remaining)) {
                    return remaining;  // 返回值>=0 表示成功，<0 表示失败
                }
            }
        }

        @Override
        protected boolean tryReleaseShared(int releases) {
            for (;;) {
                int current = getState();
                int next = current + releases;
                if (compareAndSetState(current, next)) {
                    return true;
                }
            }
        }
    }

    private final Sync sync;

    public MySemaphore(int permits) {
        this.sync = new Sync(permits);
    }

    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    public void acquire(int permits) throws InterruptedException {
        sync.acquireSharedInterruptibly(permits);
    }

    public void release() {
        sync.releaseShared(1);
    }

    public void release(int permits) {
        sync.releaseShared(permits);
    }

    public int availablePermits() {
        return sync.getPermits();
    }

    // 测试
    public static void main(String[] args) {
        MySemaphore semaphore = new MySemaphore(2);  // 同时允许 2 个线程

        Runnable task = () -> {
            try {
                semaphore.acquire();
                System.out.println(Thread.currentThread().getName() + " 获取许可，剩余："
                    + semaphore.availablePermits());
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                semaphore.release();
                System.out.println(Thread.currentThread().getName() + " 释放许可，剩余："
                    + semaphore.availablePermits());
            }
        };

        for (int i = 0; i < 5; i++) {
            new Thread(task, "Thread-" + i).start();
        }
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：AQS 为什么使用双向队列而不是单向队列？

**答：**

```
双向队列的优势：
1. 方便从头尾同时操作
   - head 节点出队时，需要访问后继节点
   - tail 节点入队时，需要访问前驱节点

2. 取消节点时方便
   - 节点取消时，需要断开前后节点的连接
   - 双向链表可以直接找到前驱和后继

3. 等待超时时方便
   - 可以从队列中间移除节点
   - 单向链表需要遍历找到前驱

4. 传播唤醒时方便
   - 共享模式下，唤醒信号需要向后传播
   - 双向链表可以向后遍历
```

### 扩展 2：AQS 中的 Condition 是如何实现的？

**答：**

```java
// Condition 实现原理
public class ConditionObject implements Condition {
    // 条件队列（独立于同步队列）
    private Node firstWaiter;
    private Node lastWaiter;

    // await() 方法
    public final void await() throws InterruptedException {
        // 1. 添加节点到条件队列
        Node node = addConditionWaiter();
        // 2. 释放锁
        int savedState = fullyRelease(node);
        // 3. 阻塞线程
        LockSupport.park(this);
        // 4. 被唤醒后，重新竞争锁
        acquireQueued(node, savedState);
    }

    // signal() 方法
    public final void signal() {
        // 1. 从条件队列移除首节点
        Node node = firstWaiter;
        // 2. 转移到同步队列
        transferForSignal(node);
        // 3. 唤醒线程
        LockSupport.unpark(node.thread);
    }
}

// 条件队列 vs 同步队列
条件队列：
- 等待 Condition 的线程
- 单向链表结构
- signal() 后转移到同步队列

同步队列：
- 竞争锁失败的线程
- 双向链表结构
- 获取锁后出队
```

### 扩展 3：公平锁和非公平锁在 AQS 中的区别？

**答：**

```java
// 公平锁：tryAcquire 时检查队列
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    if (c == 0) {
        // 关键：检查是否有等待节点
        if (!hasQueuedPredecessors() &&
            compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    // ...
}

// 非公平锁：直接 CAS 尝试
final boolean nonfairTryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    if (c == 0) {
        // 不检查队列，直接 CAS
        if (compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    // ...
}

// 区别总结
公平锁：
- 新来的线程先检查队列，有等待节点则排队
- 按照 FIFO 顺序获取锁
- 避免饥饿，但吞吐量低

非公平锁：
- 新来的线程直接 CAS 尝试，不管队列
- 可能"插队"成功
- 减少上下文切换，吞吐量高
```

### 扩展 4：AQS 中线程是如何被唤醒的？

**答：**

```
1. 释放锁时唤醒后继
   release(int arg)
       ↓
   tryRelease(arg)
       ↓
   state = 0（完全释放）
       ↓
   unparkSuccessor(node)  // 唤醒后继节点
       ↓
   LockSupport.unpark(successor.thread)

2. 共享模式下传播唤醒
   doReleaseShared()
       ↓
   唤醒头节点的后继
       ↓
   后继获取成功后，继续唤醒下一个
       ↓
   形成链式唤醒

3. Condition.signal() 唤醒
   signal()
       ↓
   从条件队列转移到同步队列
       ↓
   LockSupport.unpark(node.thread)

LockSupport 原理：
- 底层调用 Unsafe.park()/unpark()
- park() 阻塞线程，unpark() 唤醒线程
- unpark() 可以在 park() 之前调用（有许可计数）
```

### 扩展 5：AQS 有哪些应用场景？

**答：**

```
1. ReentrantLock（独占锁）
   - 互斥访问共享资源
   - 可重入、可中断、公平/非公平

2. ReentrantReadWriteLock（读写锁）
   - 读多写少场景
   - 读锁共享，写锁独占

3. CountDownLatch（倒计时门闩）
   - 等待多个任务完成
   - 一次性使用

4. CyclicBarrier（循环屏障）
   - 多个线程相互等待
   - 可重复使用

5. Semaphore（信号量）
   - 限流场景
   - 控制同时访问的线程数

6. 自定义同步器
   - 继承 AQS，实现 tryAcquire/tryRelease
   - 满足特定业务需求
```

---

## 六、面试答题话术

**面试官问：请说说 AQS 的底层原理？**

**标准回答：**

> AQS（AbstractQueuedSynchronizer）是 Java 并发包的核心框架，用于构建锁和同步器。
>
> **核心数据结构**：
> 1. **state 变量**：volatile 修饰，表示同步状态（0=无锁，>0=有锁）
> 2. **CLH 双向队列**：管理等待线程，FIFO 顺序
>
> **工作原理**：
> 1. 线程尝试获取锁，失败后加入队列尾部
> 2. 线程在队列中自旋，前驱节点是 head 时尝试获取
> 3. 获取失败则 LockSupport.park() 阻塞
> 4. 释放锁时唤醒后继节点
>
> **应用组件**：ReentrantLock、CountDownLatch、Semaphore、ReentrantReadWriteLock 等都基于 AQS 实现。

**加分回答：**

> 补充几点：
> 1. AQS 支持两种模式：独占模式（ReentrantLock）和共享模式（CountDownLatch、Semaphore）
> 2. AQS 使用模板方法模式，子类只需实现 tryAcquire/tryRelease 等核心方法
> 3. 公平锁和非公平锁的区别在于：公平锁 tryAcquire 时检查队列，非公平锁直接 CAS
> 4. Condition 有独立的条件队列，signal() 时将线程从条件队列转移到同步队列
> 5. AQS 的 state 是 volatile 的，保证可见性；CAS 操作保证原子性
> 6. ReentrantLock 的 state 记录重入次数，CountDownLatch 的 state 记录剩余次数

---

## 七、速记表格

| 组件 | 说明 |
|------|------|
| 全称 | AbstractQueuedSynchronizer |
| 核心字段 | volatile int state + CLH 队列 |
| 同步模式 | 独占模式、共享模式 |
| 阻塞方式 | LockSupport.park()/unpark() |
| 原子操作 | CAS（compareAndSetState） |
| 可见性 | volatile |
| 应用组件 | ReentrantLock、CountDownLatch、Semaphore 等 |
| 设计模式 | 模板方法模式 |
