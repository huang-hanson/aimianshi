# [Java 基础] Error、Exception 的区别是什么

## 一、题目分析

### 考察点
- Java 异常体系结构
- Error 和 Exception 的区别
- 运行时异常和受检异常的区别
- 异常处理最佳实践

### 难度等级
⭐⭐ (2/5 星) - 基础高频题

### 适用岗位
- 初级/中级/高级 Java 开发工程师
- 所有 Java 相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> **Error** 是系统级错误（如 OOM、StackOverflowError），程序无法恢复；**Exception** 是程序级异常，可以被捕获和处理。Exception 又分为**受检异常**（必须处理）和**运行时异常**（可选处理）。

---

## 三、深度剖析

### 3.1 Java 异常体系结构

```
Throwable
    ├── Error (错误)
    │   ├── OutOfMemoryError (内存溢出)
    │   ├── StackOverflowError (栈溢出)
    │   ├── VirtualMachineError (虚拟机错误)
    │   ├── NoClassDefFoundError (类未找到)
    │   └── ...
    │
    └── Exception (异常)
        ├── RuntimeException (运行时异常)
        │   ├── NullPointerException (空指针)
        │   ├── ArrayIndexOutOfBoundsException (数组越界)
        │   ├── IllegalArgumentException (非法参数)
        │   ├── ClassCastException (类型转换)
        │   └── ...
        │
        └── 其他 Exception (受检异常)
            ├── IOException (IO 异常)
            ├── SQLException (SQL 异常)
            ├── ClassNotFoundException (类未找到)
            └── ...
```

### 3.2 Error 和 Exception 对比表

| 对比项 | Error | Exception |
|--------|-------|-----------|
| **定义** | 系统级错误 | 程序级异常 |
| **可恢复性** | ❌ 不可恢复 | ✅ 可恢复 |
| **是否必须捕获** | 否 | 受检异常必须捕获 |
| **常见类型** | OOM、StackOverflowError | NullPointerException、IOException |
| **产生原因** | JVM 资源耗尽/系统故障 | 程序逻辑问题/外部资源问题 |
| **处理方式** | 无法处理，只能预防 | try-catch 或 throws |
| **设计目的** | 表示严重问题，程序应终止 | 表示可处理的问题 |

### 3.3 常见 Error 类型

```java
// 1. OutOfMemoryError - 内存溢出
// 场景：堆内存不足
List<byte[]> list = new ArrayList<>();
while (true) {
    list.add(new byte[1024 * 1024]);  // 不断分配 1MB 内存
}
// 异常信息：Java heap space

// 2. StackOverflowError - 栈溢出
// 场景：递归调用没有出口
public void recursive() {
    recursive();  // 无限递归
}
// 异常信息：Stack overflow

// 3. NoClassDefFoundError - 类定义未找到
// 场景：运行时找不到某个类的定义
// 原因：类路径缺失、类加载失败

// 4. ExceptionInInitializerError - 静态初始化异常
// 场景：静态块或静态变量初始化失败
static {
    int x = 1 / 0;  // 抛出 ArithmeticException
}
```

### 3.4 Exception 分类详解

```
Exception
    │
    ├── 受检异常 (Checked Exception)
    │   特点：编译器强制要求处理
    │   场景：外部资源问题（IO、网络、数据库）
    │   处理：必须 try-catch 或 throws
    │
    └── 运行时异常 (RuntimeException / Unchecked Exception)
        特点：编译器不强制处理
        场景：程序逻辑错误
        处理：可选处理，建议修复代码
```

**常见受检异常：**

| 异常类型 | 触发场景 |
|----------|----------|
| IOException | 文件读写、网络 IO |
| SQLException | 数据库操作 |
| ClassNotFoundException | 类加载 |
| NoSuchMethodException | 反射调用方法 |
| InterruptedException | 线程中断 |

**常见运行时异常：**

| 异常类型 | 触发场景 |
|----------|----------|
| NullPointerException | 访问 null 对象的成员 |
| ArrayIndexOutOfBoundsException | 数组越界 |
| IllegalArgumentException | 方法参数不合法 |
| ClassCastException | 强制类型转换失败 |
| ArithmeticException | 算术错误（如除以 0） |
| NumberFormatException | 字符串转数字失败 |

---

## 四、代码示例

### 4.1 Error 示例（不可恢复）

```java
public class ErrorExample {

    // 1. StackOverflowError 演示
    public static void stackOverflow() {
        stackOverflow();  // 无限递归
    }

    // 2. OutOfMemoryError 演示
    public static void outOfMemory() {
        List<byte[]> list = new ArrayList<>();
        while (true) {
            list.add(new byte[1024 * 1024]);  // 每次分配 1MB
        }
    }

    // 3. 尝试捕获 Error（不推荐）
    public static void tryCatchError() {
        try {
            stackOverflow();
        } catch (Error e) {
            // 即使捕获了也无法恢复
            System.out.println("捕获到 Error: " + e.getMessage());
            // 程序仍然无法继续正常执行
        }
    }

    public static void main(String[] args) {
        // 运行任意一个方法都会导致程序终止
        // stackOverflow();
        // outOfMemory();
    }
}
```

### 4.2 Exception 示例（可恢复）

```java
import java.io.*;
import java.sql.*;

public class ExceptionExample {

    // 1. 受检异常 - 必须处理
    public void checkedException() throws IOException {
        // 方式 1：throws 抛出
        FileReader file = new FileReader("test.txt");
        file.read();
    }

    public void checkedExceptionWithTryCatch() {
        // 方式 2：try-catch 捕获
        try {
            FileReader file = new FileReader("test.txt");
            file.read();
        } catch (IOException e) {
            System.out.println("文件读取失败：" + e.getMessage());
        }
    }

    // 2. 运行时异常 - 可选处理
    public void uncheckedException() {
        String str = null;
        str.length();  // NullPointerException
    }

    public void uncheckedExceptionWithTryCatch() {
        try {
            String str = null;
            str.length();
        } catch (NullPointerException e) {
            System.out.println("空指针异常：" + e.getMessage());
        }
    }

    // 3. 多重捕获
    public void multiCatchException() {
        try {
            int result = 10 / 0;
            String str = null;
            str.length();
        } catch (ArithmeticException e) {
            System.out.println("算术异常：" + e.getMessage());
        } catch (NullPointerException e) {
            System.out.println("空指针异常：" + e.getMessage());
        }
    }

    // 4. try-with-resources（Java 7+）
    public void tryWithResources(String filePath) {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line = br.readLine();
            System.out.println(line);
        } catch (IOException e) {
            System.out.println("读取失败：" + e.getMessage());
        }
    }
}
```

### 4.3 自定义异常

```java
// 1. 自定义受检异常
class BusinessException extends Exception {
    private int code;

    public BusinessException(String message, int code) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

// 2. 自定义运行时异常
class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
        super(message);
    }
}

// 3. 使用示例
public class CustomExceptionDemo {

    public void businessMethod() throws BusinessException {
        // 模拟业务校验失败
        throw new BusinessException("业务异常", 400);
    }

    public void validateInput(String input) {
        if (input == null || input.isEmpty()) {
            throw new InvalidInputException("输入不能为空");
        }
    }

    public static void main(String[] args) {
        CustomExceptionDemo demo = new CustomExceptionDemo();

        try {
            demo.businessMethod();
        } catch (BusinessException e) {
            System.out.println("业务异常，错误码：" + e.getCode());
        }

        try {
            demo.validateInput("");
        } catch (InvalidInputException e) {
            System.out.println("输入校验失败：" + e.getMessage());
        }
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：受检异常和运行时异常应该如何选择？

**答：** 根据异常的性质选择：

```
选择受检异常的情况：
- 调用者能够合理恢复的情况
- 外部资源问题（IO、网络、数据库）
- 业务逻辑中的预期失败

选择运行时异常的情况：
- 编程错误，调用者无法恢复
- 参数校验失败
- 空指针、数组越界等
```

**最佳实践：**
- 优先使用运行时异常，减少代码冗余
- 只有在调用者能够合理处理时才用受检异常
- 不要滥用受检异常，避免过度包装

### 扩展 2：finally 块一定会执行吗？

**答：** 不一定。以下情况 finally 不会执行：

```java
// 1. 调用 System.exit()
try {
    System.exit(0);
} finally {
    // 不会执行
}

// 2. 线程被杀死
Thread thread = new Thread(() -> {
    try {
        while (true);
    } finally {
        // 不会执行
    }
});
thread.start();
thread.stop();  // 已废弃，但能说明问题

// 3. JVM 崩溃
try {
    Runtime.getRuntime().exit(0);
} finally {
    // 不会执行
}

// 4. 无限循环/死锁
try {
    while (true);
} finally {
    // 不会执行
}
```

### 扩展 3：return 和 finally 的执行顺序？

**答：** finally 先执行，但要注意返回值问题：

```java
public int testReturn() {
    int result = 1;
    try {
        result = 2;
        return result;  // 返回值先暂存
    } finally {
        result = 3;     // 修改 result，但不影响返回值
        return 99;      // 如果 finally 有 return，会覆盖
    }
}
// 返回 99（finally 的 return 覆盖了原返回值）

// 最佳实践：finally 中不要 return
public int bestPractice() {
    try {
        return 1;
    } finally {
        // 只做清理工作，不要 return
        System.out.println("清理资源");
    }
}
```

### 扩展 4：异常处理的最佳实践？

**答：**

```java
// ✅ 推荐做法

// 1. 捕获具体的异常，不要捕获 Exception
try {
    // 代码
} catch (IOException e) {
    // 处理
}

// 2. 不要吞掉异常
try {
    // 代码
} catch (IOException e) {
    logger.error("操作失败", e);  // 记录日志
    throw new CustomException("操作失败", e);  // 包装后抛出
}

// 3. 使用 try-with-resources
try (FileInputStream fis = new FileInputStream("file.txt")) {
    // 使用资源
} catch (IOException e) {
    // 处理
}

// 4. 不要使用异常控制流程
// ❌ 不推荐
try {
    return map.get(key);
} catch (NullPointerException e) {
    return defaultValue;
}

// ✅ 推荐
if (map.containsKey(key)) {
    return map.get(key);
} else {
    return defaultValue;
}
```

---

## 六、面试答题话术

**面试官问：Error 和 Exception 有什么区别？**

**标准回答：**

> Error 和 Exception 都是 Throwable 的子类，但有本质区别：
>
> 1. **定义不同**：Error 是系统级错误，Exception 是程序级异常
> 2. **可恢复性**：Error 不可恢复，Exception 可以捕获处理
> 3. **常见类型**：Error 如 OOM、StackOverflowError；Exception 如 IOException、NullPointerException
> 4. **处理方式**：Error 无法处理，只能预防；Exception 需要 try-catch 或 throws
>
> 另外，Exception 分为受检异常和运行时异常。受检异常编译器强制要求处理，如 IOException；运行时异常可选处理，如 NullPointerException。

**加分回答：**

> 补充一点，实际开发中要注意：
> 1. 不要捕获 Error，因为无法恢复
> 2. 不要捕获 Exception 这种泛型异常，要捕获具体类型
> 3. 不要在 finally 块中 return，会覆盖原返回值
> 4. 不要使用异常来控制流程，性能差且代码可读性低
> 5. 自定义异常时，能恢复的情况用受检异常，编程错误用运行时异常

---

## 七、速记表格

| 特性 | Error | Exception |
|------|-------|-----------|
| 级别 | 系统级 | 程序级 |
| 可恢复 | ❌ | ✅ |
| 必须捕获 | 否 | 受检异常必须 |
| 常见类型 | OOM、StackOverflow | IOException、NullPointerException |
| 处理建议 | 无法处理，只能预防 | try-catch 或 throws |
