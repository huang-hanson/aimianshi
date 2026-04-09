# [Java 基础] Java 的基本数据类型，int 占几个字节？

## 一、题目分析

### 考察点
- Java 8 种基本数据类型及其内存占用
- int 类型的字节数、位数、取值范围
- 基本数据类型与包装类的区别

### 难度等级
⭐ (1/5 星) - 基础送分题

### 适用岗位
- 初级/中级/高级 Java 开发工程师
- 所有 Java 相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> Java 有 **8 种基本数据类型**，其中 **int 占 4 字节（32 位）**，取值范围是 -2³¹ 到 2³¹-1（约 -21 亿 到 +21 亿）。

---

## 三、深度剖析

### 3.1 Java 8 种基本数据类型详解

| 类型 | 关键字 | 字节数 | 位数 | 取值范围 | 默认值 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **布尔型** | `boolean` | 1 字节* | 8 位 | `true` / `false` | `false` |
| **字节型** | `byte` | 1 字节 | 8 位 | -128 ~ 127 | `0` |
| **短整型** | `short` | 2 字节 | 16 位 | -32768 ~ 32767 | `0` |
| **整型** | `int` | **4 字节** | **32 位** | -2³¹ ~ 2³¹-1 | `0` |
| **长整型** | `long` | 8 字节 | 64 位 | -2⁶³ ~ 2⁶³-1 | `0L` |
| **浮点型** | `float` | 4 字节 | 32 位 | 3.4E-38 ~ 3.4E+38 | `0.0f` |
| **双精度** | `double` | 8 字节 | 64 位 | 4.9E-324 ~ 1.8E+308 | `0.0d` |
| **字符型** | `char` | 2 字节 | 16 位 | `\u0000` ~ `\uffff` | `\u0000` |

> *注：`boolean` 在 JVM 规范中没有明确定义字节数，实际实现中单个 `boolean` 变量通常占 1 字节，但 `boolean[]` 数组中每个元素占 1 字节。

### 3.2 int 类型详解

```
┌─────────────────────────────────────────────────────────┐
│                    int (32 位 / 4 字节)                   │
├─────────────────────────────────────────────────────────┤
│  符号位 (1 位)  │  数值位 (31 位)                        │
│      0/1       │      000...000 ~ 111...111            │
│   0=正数，1=负数 │         表示数值大小                   │
└─────────────────────────────────────────────────────────┘

最小值：-2³¹ = -2,147,483,648
最大值：2³¹ - 1 = 2,147,483,647
```

**代码验证：**
```java
public class IntSizeTest {
    public static void main(String[] args) {
        // 1. int 占几个字节
        System.out.println("int 字节数：" + Integer.BYTES);  // 输出：4

        // 2. int 占几个位
        System.out.println("int 位数：" + Integer.SIZE);    // 输出：32

        // 3. int 最小值
        System.out.println("int 最小值：" + Integer.MIN_VALUE);
        // 输出：-2147483648

        // 4. int 最大值
        System.out.println("int 最大值：" + Integer.MAX_VALUE);
        // 输出：2147483647
    }
}
```

### 3.3 记忆口诀

```
布尔 1，字节 1，
短整 2，整 4，长 8，
浮点 4，双精 8，
字符 2，记心间。
```

---

## 四、代码示例

### 4.1 完整测试代码

```java
/**
 * Java 8 种基本数据类型测试
 */
public class PrimitiveTypesTest {
    public static void main(String[] args) {
        // 1. boolean - 布尔型
        boolean flag = true;
        System.out.println("boolean 默认值：" + flag);

        // 2. byte - 字节型 (1 字节)
        byte b = 100;
        System.out.println("byte 范围：-128 ~ 127，当前值：" + b);

        // 3. short - 短整型 (2 字节)
        short s = 10000;
        System.out.println("short 范围：-32768 ~ 32767，当前值：" + s);

        // 4. int - 整型 (4 字节)
        int i = 1000000;
        System.out.println("int 字节数：" + Integer.BYTES);
        System.out.println("int 位数：" + Integer.SIZE);
        System.out.println("int 最小值：" + Integer.MIN_VALUE);
        System.out.println("int 最大值：" + Integer.MAX_VALUE);

        // 5. long - 长整型 (8 字节)
        long l = 10000000000L;  // 注意：long 字面量要加 L
        System.out.println("long 字节数：" + Long.BYTES);

        // 6. float - 浮点型 (4 字节)
        float f = 3.14f;  // 注意：float 字面量要加 f
        System.out.println("float 字节数：" + Float.BYTES);

        // 7. double - 双精度 (8 字节)
        double d = 3.1415926535;
        System.out.println("double 字节数：" + Double.BYTES);

        // 8. char - 字符型 (2 字节)
        char c = 'A';
        System.out.println("char 字节数：" + Character.BYTES);
        System.out.println("char 范围：0 ~ " + (int) Character.MAX_VALUE);
    }
}
```

### 4.2 int 溢出示例

```java
/**
 * int 溢出演示
 */
public class IntOverflowTest {
    public static void main(String[] args) {
        int max = Integer.MAX_VALUE;
        int min = Integer.MIN_VALUE;

        System.out.println("int 最大值：" + max);
        System.out.println("最大值 + 1：" + (max + 1));  // 溢出！变成 min

        System.out.println("int 最小值：" + min);
        System.out.println("最小值 - 1：" + (min - 1));  // 溢出！变成 max

        // 大数计算用 long
        long bigMax = Long.MAX_VALUE;
        System.out.println("long 最大值：" + bigMax);
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：为什么 int 是 4 字节？

**答：** 这是由 Java 语言规范（JLS）规定的，目的是保证**跨平台一致性**。

```
Java 的设计原则：Write Once, Run Anywhere

无论在什么平台（Windows、Linux、Mac）：
- int 永远是 4 字节
- long 永远是 8 字节
- char 永远是 2 字节

对比 C/C++：
- C 语言的 int 大小依赖于编译器和平台
- 在 16 位系统中，int 可能是 2 字节
- 在 32/64 位系统中，int 通常是 4 字节
```

### 扩展 2：int 和 Integer 的区别？

| 对比维度 | `int` | `Integer` |
| :--- | :--- | :--- |
| **类型** | 基本数据类型 | 引用类型（包装类） |
| **字节数** | 4 字节 | 对象头 (12-16 字节) + int(4 字节) ≈ 16-24 字节 |
| **默认值** | `0` | `null` |
| **存储位置** | 栈上（局部变量）或堆上（成员变量） | 堆上 |
| **比较** | `==` 比较值 | `==` 比较引用，`equals()` 比较值 |
| **泛型支持** | 不支持 | 支持 |
| **null 值** | 不支持 | 支持 |

**代码示例：**
```java
int a = 128;
Integer b = 128;
Integer c = 128;

System.out.println(a == b);      // true（自动拆箱）
System.out.println(b == c);      // false（超过缓存范围 -128~127）
System.out.println(b.equals(c)); // true（值比较）

Integer d = 100;
Integer e = 100;
System.out.println(d == e);      // true（在缓存范围内）
```

### 扩展 3：数据类型选择建议

| 场景 | 推荐类型 | 理由 |
| :--- | :--- | :--- |
| 一般整数计算 | `int` | 最常用，性能最好 |
| 超过 21 亿的数值 | `long` | 如订单 ID、用户 ID |
| 金额计算 | `BigDecimal` | 避免浮点数精度丢失 |
| 布尔判断 | `boolean` | 语义清晰 |
| 字符处理 | `char` | 单个字符 |
| 网络传输/文件存储 | `byte` | 节省空间 |
| 短整数（如年龄） | `byte`/`short` | 节省内存 |

---

## 六、面试答题话术

**面试官问：Java 有哪些基本数据类型？int 占几个字节？**

**标准回答：**

> Java 有 8 种基本数据类型，分别是：
>
> 1. **boolean**（布尔型）
> 2. **byte**（字节型，1 字节）
> 3. **short**（短整型，2 字节）
> 4. **int**（整型，4 字节）
> 5. **long**（长整型，8 字节）
> 6. **float**（浮点型，4 字节）
> 7. **double**（双精度，8 字节）
> 8. **char**（字符型，2 字节）
>
> 其中 **int 占 4 字节（32 位）**，取值范围是 -2³¹ 到 2³¹-1，也就是约 -21 亿 到 +21 亿。
>
> Java 规定基本数据类型的大小是固定的，这是为了保证跨平台一致性，无论在什么操作系统上，int 永远是 4 字节。

**加分回答：**

> 另外值得一提的是，`int` 的包装类是 `Integer`，`Integer` 对象由于有对象头开销，实际占用约 16-24 字节。
>
> 还有 `Integer` 有缓存机制，-128 到 127 之间的整数会缓存到 `IntegerCache` 中，所以这个范围内的 `Integer` 对象用 `==` 比较会返回 `true`，超出范围就会返回 `false`，实际开发中建议用 `equals()` 比较值。

---

## 七、记忆表格（考前速记）

| 类型分类 | 类型 | 字节 | 范围 |
| :--- | :--- | :--- | :--- |
| **整数型** | byte | 1 | -128 ~ 127 |
| | short | 2 | -3 万 ~ 3 万 |
| | **int** | **4** | **-21 亿 ~ 21 亿** |
| | long | 8 | 很大 |
| **浮点型** | float | 4 | 7 位精度 |
| | double | 8 | 15 位精度 |
| **其他** | char | 2 | 0 ~ 65535 |
| | boolean | 1 | true/false |
