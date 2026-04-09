# [Spring] Spring Bean 的生命周期

## 一、题目分析

### 考察点
- Bean 的定义和注册
- Bean 的实例化过程
- 属性填充和依赖注入
- 初始化前后的扩展点
- 销毁过程
- BeanPostProcessor 的作用

### 难度等级
⭐⭐⭐⭐⭐ (5/5 星) - 高频核心难题

### 适用岗位
- 中级/高级 Java 开发工程师
- Spring 框架相关岗位必考题

---

## 二、核心答案

**一句话回答：**
> Spring Bean 的生命周期分为：**实例化前 → 实例化 → 属性填充 → 初始化前 → 初始化 → 初始化后 → 使用 → 销毁**。核心扩展点包括 `BeanPostProcessor`、`InitializingBean`、`@PostConstruct` 等。Spring 通过 `BeanFactory` 管理整个生命周期。

---

## 三、深度剖析

### 3.1 Bean 生命周期整体流程

```
┌─────────────────────────────────────────────────────────┐
│          Spring Bean 生命周期完整流程                     │
│                                                          │
│  1. 实例化前（Instantiation Before）                      │
│     └─> BeanPostProcessor.postProcessBeforeInstantiation │
│                                                          │
│  2. 实例化（Instantiation）                               │
│     └─> 创建 Bean 实例（反射/CGLIB）                       │
│                                                          │
│  3. 属性填充（Populate Bean）                             │
│     └─> 依赖注入（@Autowired、@Value）                    │
│                                                          │
│  4. 初始化前（Initialization Before）                     │
│     └─> BeanPostProcessor.postProcessBeforeInitialization│
│     └─> @PostConstruct                                   │
│                                                          │
│  5. 初始化（Initialization）                              │
│     └─> InitializingBean.afterPropertiesSet()            │
│     └─> init-method（XML 配置）                            │
│                                                          │
│  6. 初始化后（Initialization After）                      │
│     └─> BeanPostProcessor.postProcessAfterInitialization │
│     └─> AOP 代理（如果需要）                               │
│                                                          │
│  7. 使用（In Use）                                        │
│     └─> 业务调用                                          │
│                                                          │
│  8. 销毁（Destruction）                                   │
│     └─> @PreDestroy                                      │
│     └─> DisposableBean.destroy()                         │
│     └─> destroy-method（XML 配置）                        │
└─────────────────────────────────────────────────────────┘
```

### 3.2 生命周期流程图

```
┌─────────────────────────────────────────────────────────┐
│              Bean 生命周期详细流程                        │
│                                                          │
│                    开始                                   │
│                     │                                     │
│                     ↓                                     │
│          ┌─────────────────────┐                         │
│          │  Bean 定义扫描/注册    │                         │
│          └───────────┬─────────┘                         │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │ postProcessBefore   │  ← BeanPostProcessor   │
│          │ Instantiation       │                         │
│          └───────────┬─────────┘                         │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │   实例化 (create)    │  ← 反射/CGLIB           │
│          └───────────┬─────────┘                         │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │   属性填充 (populate) │  ← @Autowired/@Value  │
│          └───────────┬─────────┘                         │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │ postProcessBefore   │  ← BeanPostProcessor   │
│          │ Initialization      │                         │
│          └───────────┬─────────┘                         │
│                      │                                    │
│          ┌───────────┴───────────┐                       │
│          │  @PostConstruct       │  ← JSR-250           │
│          └───────────┬───────────┘                       │
│                      │                                    │
│          ┌───────────┴───────────┐                       │
│          │  InitializingBean     │  ← afterPropertiesSet│
│          │  init-method          │  ← XML 配置            │
│          └───────────┬───────────┘                       │
│                      │                                    │
│          ┌───────────┴───────────┐                       │
│          │ postProcessAfter      │  ← BeanPostProcessor │
│          │ Initialization        │  ← AOP 代理在此         │
│          └───────────┬───────────┘                       │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │    Bean 就绪可用     │                         │
│          └───────────┬─────────┘                         │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │    业务方法调用      │                         │
│          └───────────┬─────────┘                         │
│                      │                                    │
│                      ↓                                    │
│          ┌─────────────────────┐                         │
│          │  容器关闭时销毁      │                         │
│          │  @PreDestroy        │                         │
│          │  DisposableBean     │                         │
│          │  destroy-method     │                         │
│          └─────────────────────┘                         │
└─────────────────────────────────────────────────────────┘
```

### 3.3 核心接口和注解

```
┌─────────────────────────────────────────────────────────┐
│          Bean 生命周期扩展点                              │
│                                                          │
│  1. BeanPostProcessor（核心扩展点）                       │
│     ┌─────────────────────────────────────────────────┐ │
│     │ public interface BeanPostProcessor {             │ │
│     │     // 实例化后，初始化前                         │ │
│     │     Object postProcessBeforeInitialization(      │ │
│     │         Object bean, String beanName);           │ │
│     │                                                  │ │
│     │     // 初始化后                                   │ │
│     │     Object postProcessAfterInitialization(       │ │
│     │         Object bean, String beanName);           │ │
│     │ }                                                │ │
│     └─────────────────────────────────────────────────┘ │
│     应用：AOP 代理、自定义处理                            │
│                                                          │
│  2. InitializingBean（初始化回调）                        │
│     ┌─────────────────────────────────────────────────┐ │
│     │ public interface InitializingBean {              │ │
│     │     void afterPropertiesSet() throws Exception;  │ │
│     │ }                                                │ │
│     └─────────────────────────────────────────────────┘ │
│     应用：初始化逻辑、资源加载                           │
│                                                          │
│  3. DisposableBean（销毁回调）                            │
│     ┌─────────────────────────────────────────────────┐ │
│     │ public interface DisposableBean {                │ │
│     │     void destroy() throws Exception;             │ │
│     │ }                                                │ │
│     └─────────────────────────────────────────────────┘ │
│     应用：资源释放、连接关闭                            │
│                                                          │
│  4. JSR-250 注解                                         │
│     ┌─────────────────────────────────────────────────┐ │
│     │ @PostConstruct  // 初始化后执行                   │ │
│     │ @PreDestroy     // 销毁前执行                     │ │
│     └─────────────────────────────────────────────────┘ │
│     应用：生命周期回调                                  │
│                                                          │
│  5. init-method / destroy-method（XML 配置）              │
│     ┌─────────────────────────────────────────────────┐ │
│     │ <bean class="..."                               │ │
│     │       init-method="init"                        │ │
│     │       destroy-method="cleanup"/>                │ │
│     └─────────────────────────────────────────────────┘ │
│     应用：自定义初始化和销毁方法                        │
└─────────────────────────────────────────────────────────┘
```

### 3.4 执行顺序对比表

| 扩展点 | 执行时机 | 所属接口/注解 | 应用场景 |
|--------|----------|--------------|----------|
| **postProcessBeforeInstantiation** | 实例化前 | BeanPostProcessor | 返回代理对象 |
| **postProcessAfterInstantiation** | 实例化后，属性填充前 | SmartInstantiationAwareBeanPostProcessor | 控制属性填充 |
| **postProcessProperties** | 属性填充时 | InstantiationAwareBeanPostProcessor | 自定义依赖注入 |
| **@PostConstruct** | 初始化前 | JSR-250 | 初始化逻辑 |
| **afterPropertiesSet** | 初始化中 | InitializingBean | 初始化逻辑 |
| **init-method** | 初始化中 | XML 配置 | 初始化逻辑 |
| **postProcessBeforeInitialization** | 初始化前 | BeanPostProcessor | 自定义处理 |
| **postProcessAfterInitialization** | 初始化后 | BeanPostProcessor | AOP 代理 |
| **@PreDestroy** | 销毁前 | JSR-250 | 资源释放 |
| **destroy** | 销毁中 | DisposableBean | 资源释放 |
| **destroy-method** | 销毁中 | XML 配置 | 资源释放 |

---

## 四、代码示例

### 4.1 完整 Bean 生命周期示例

```java
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;
import javax.annotation.*;

@Component
@Scope("singleton")
public class LifeCycleBean implements InitializingBean, DisposableBean {

    private String name;

    // ========== 构造方法 ==========
    public LifeCycleBean() {
        System.out.println("1. 构造方法：创建 Bean 实例");
    }

    // ========== 属性填充（依赖注入）==========
    @Autowired
    public void setName(String name) {
        this.name = name;
        System.out.println("2. 属性填充：注入 name = " + name);
    }

    // ========== @PostConstruct ==========
    @PostConstruct
    public void postConstruct() {
        System.out.println("3. @PostConstruct：初始化前回调");
    }

    // ========== InitializingBean ==========
    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println("4. afterPropertiesSet：属性设置完成后初始化");
    }

    // ========== init-method（XML 配置或@Bean 指定）==========
    public void initMethod() {
        System.out.println("5. init-method：自定义初始化方法");
    }

    // ========== 业务方法 ==========
    public void doSomething() {
        System.out.println("6. 业务方法：Bean 正在使用...");
    }

    // ========== @PreDestroy ==========
    @PreDestroy
    public void preDestroy() {
        System.out.println("7. @PreDestroy：销毁前回调");
    }

    // ========== DisposableBean ==========
    @Override
    public void destroy() throws Exception {
        System.out.println("8. destroy：DisposableBean 销毁回调");
    }

    // ========== destroy-method ==========
    public void destroyMethod() {
        System.out.println("9. destroy-method：自定义销毁方法");
    }
}
```

### 4.2 BeanPostProcessor 示例

```java
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class MyBeanPostProcessor implements BeanPostProcessor {

    // ========== 实例化前 ==========
    @Override
    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName)
            throws BeansException {
        System.out.println("BeanPostProcessor.postProcessBeforeInstantiation: " + beanName);
        return null;  // 返回 null 表示继续创建 Bean
    }

    // ========== 实例化后，初始化前 ==========
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        System.out.println("BeanPostProcessor.postProcessBeforeInitialization: " + beanName);
        return bean;
    }

    // ========== 初始化后 ==========
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        System.out.println("BeanPostProcessor.postProcessAfterInitialization: " + beanName);
        // AOP 代理通常在这里返回代理对象
        return bean;
    }
}
```

### 4.3 测试类

```java
import org.springframework.context.*;
import org.springframework.context.annotation.*;

@Configuration
@ComponentScan
public class BeanLifeCycleTest {

    @Bean(initMethod = "initMethod", destroyMethod = "destroyMethod")
    public LifeCycleBean lifeCycleBean() {
        return new LifeCycleBean();
    }

    public static void main(String[] args) {
        // 创建 Spring 容器
        AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext(BeanLifeCycleTest.class);

        // 获取 Bean 并使用
        LifeCycleBean bean = context.getBean(LifeCycleBean.class);
        bean.doSomething();

        // 关闭容器，触发销毁
        context.close();
    }
}
```

**输出结果：**
```
BeanPostProcessor.postProcessBeforeInstantiation: lifeCycleBean
1. 构造方法：创建 Bean 实例
2. 属性填充：注入 name = test
BeanPostProcessor.postProcessBeforeInitialization: lifeCycleBean
3. @PostConstruct：初始化前回调
4. afterPropertiesSet：属性设置完成后初始化
5. init-method：自定义初始化方法
BeanPostProcessor.postProcessAfterInitialization: lifeCycleBean
6. 业务方法：Bean 正在使用...
7. @PreDestroy：销毁前回调
8. destroy：DisposableBean 销毁回调
9. destroy-method：自定义销毁方法
```

### 4.4 AOP 代理在生命周期中的位置

```java
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class AopBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        // 如果 Bean 需要 AOP，在这里创建代理
        if (bean instanceof LifeCycleBean) {
            System.out.println("创建 AOP 代理：" + beanName);

            ProxyFactory factory = new ProxyFactory();
            factory.setTarget(bean);
            factory.addAdvice(new MyInterceptor());

            return factory.getProxy();  // 返回代理对象
        }
        return bean;
    }

    static class MyInterceptor implements org.aopalliance.intercept.MethodInterceptor {
        @Override
        public Object invoke(org.aopalliance.intercept.MethodInvocation invocation)
                throws Throwable {
            System.out.println("前置通知");
            Object result = invocation.proceed();
            System.out.println("后置通知");
            return result;
        }
    }
}
```

---

## 五、常见面试题扩展

### 扩展 1：BeanPostProcessor 和 BeanFactoryPostProcessor 的区别？

**答：**

| 对比项 | BeanPostProcessor | BeanFactoryPostProcessor |
|--------|-------------------|--------------------------|
| **作用对象** | Bean 实例 | Bean 定义（BeanDefinition） |
| **执行时机** | 实例化后 | 实例化前 |
| **主要用途** | 修改 Bean 实例（AOP 代理） | 修改 Bean 定义（占位符替换） |
| **典型应用** | @Async、@Transactional | @PropertySourcesPlaceholderConfigurer |
| **接口方法** | postProcessBefore/AfterInitialization | postProcessBeanFactory |

**BeanFactoryPostProcessor 示例：**
```java
@Component
public class MyBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        System.out.println("BeanFactoryPostProcessor：修改 Bean 定义");
        // 可以修改 BeanDefinition，如修改 scope、lazy-init 等
    }
}
```

### 扩展 2：@PostConstruct、afterPropertiesSet、init-method 的执行顺序？

**答：**

```
执行顺序：
1. @PostConstruct（JSR-250 注解）
2. afterPropertiesSet（InitializingBean 接口）
3. init-method（@Bean 或 XML 配置）

原因：
- @PostConstruct 是规范注解，优先执行
- afterPropertiesSet 是 Spring 接口，其次执行
- init-method 是配置方法，最后执行

建议：
- 优先使用 @PostConstruct（标准规范）
- 避免同时使用多个初始化方式
- 代码更清晰，维护更方便
```

### 扩展 3：Spring 如何保证 Bean 的线程安全？

**答：**

```
Spring 不保证 Bean 的线程安全！

默认情况：
- Bean 是单例的（singleton）
- 多个线程共享同一个 Bean 实例
- 如果 Bean 有状态（成员变量），可能线程不安全

解决方案：

1. 无状态 Bean（推荐）
   - 不使用成员变量
   - 只使用局部变量
   - Controller、Service 通常是无状态的

2. 设置 Bean 作用域
   @Scope("prototype")  // 每次请求创建新实例
   @Scope("request")    // 每个 HTTP 请求一个实例
   @Scope("session")    // 每个 Session 一个实例

3. 使用 ThreadLocal
   private ThreadLocal<User> userContext = new ThreadLocal<>();

4. 同步控制
   synchronized、Lock、并发集合

5. 不可变对象
   final 字段、构造器注入
```

### 扩展 4：Bean 的作用域有哪些？

**答：**

| 作用域 | 说明 | 生命周期 | 线程安全 |
|--------|------|----------|----------|
| **singleton** | 单例（默认） | 容器启动到关闭 | ❌ 共享 |
| **prototype** | 多例 | 每次请求新实例 | ✅ 独立 |
| **request** | HTTP 请求 | 一次 HTTP 请求 | ✅ 请求内共享 |
| **session** | HTTP Session | 一个 Session | ✅ Session 内共享 |
| **application** | ServletContext | 应用生命周期 | ❌ 全局共享 |
| **websocket** | WebSocket | WebSocket 会话 | ✅ 会话内共享 |

**使用方式：**
```java
// 注解方式
@Component
@Scope("prototype")
public class MyBean { }

// @Bean 方式
@Bean
@Scope("prototype")
public MyBean myBean() {
    return new MyBean();
}
```

### 扩展 5：循环依赖如何解决？

**答：**

```
循环依赖场景：
A → 依赖 → B
B → 依赖 → A

Spring 解决方案（仅单例、setter 注入）：

1. 三级缓存机制
   - singletonObjects：成品缓存（完全初始化的 Bean）
   - earlySingletonObjects：早期引用缓存（未完全初始化）
   - singletonFactories：工厂缓存（创建早期引用）

2. 解决流程
   A 创建 → 实例化 → 放入三级缓存 → 注入 B
   B 创建 → 实例化 → 注入 A（从三级缓存获取早期引用）→ 完成
   A 完成注入 → 完成

3. 无法解决的情况
   - 构造器注入循环依赖
   - prototype 作用域循环依赖
   - 解决方法：@Lazy 延迟加载

4. @Lazy 解决
   @Component
   public class A {
       @Lazy
       @Autowired
       private B b;  // 注入代理对象，打破循环
   }
```

---

## 六、面试答题话术

**面试官问：请说说 Spring Bean 的生命周期？**

**标准回答：**

> Spring Bean 的生命周期主要分为 8 个阶段：
>
> 1. **实例化前**：BeanPostProcessor.postProcessBeforeInstantiation
> 2. **实例化**：通过反射或 CGLIB 创建 Bean 实例
> 3. **属性填充**：依赖注入（@Autowired、@Value）
> 4. **初始化前**：BeanPostProcessor.postProcessBeforeInitialization、@PostConstruct
> 5. **初始化**：InitializingBean.afterPropertiesSet、init-method
> 6. **初始化后**：BeanPostProcessor.postProcessAfterInitialization（AOP 代理在此）
> 7. **使用**：业务方法调用
> 8. **销毁**：@PreDestroy、DisposableBean.destroy、destroy-method
>
> 其中 BeanPostProcessor 是最重要的扩展点，AOP 代理就是在初始化后完成的。

**加分回答：**

> 补充几点：
> 1. 初始化方法的执行顺序：@PostConstruct → afterPropertiesSet → init-method
> 2. BeanPostProcessor 是 Spring AOP 的核心，代理对象在 postProcessAfterInitialization 返回
> 3. 循环依赖通过三级缓存解决：singletonObjects、earlySingletonObjects、singletonFactories
> 4. 构造器注入的循环依赖无法解决，需要用@Lazy 延迟加载
> 5. Bean 默认是单例的，多个线程共享，需要注意线程安全问题
> 6. BeanFactoryPostProcessor 在 Bean 实例化前修改 BeanDefinition，如占位符替换

---

## 七、速记表格

| 阶段 | 扩展点 | 接口/注解 |
|------|--------|-----------|
| 实例化前 | postProcessBeforeInstantiation | BeanPostProcessor |
| 实例化 | createBeanInstance | 反射/CGLIB |
| 属性填充 | populateBean | @Autowired/@Value |
| 初始化前 | @PostConstruct | JSR-250 |
| 初始化前 | postProcessBeforeInitialization | BeanPostProcessor |
| 初始化 | afterPropertiesSet | InitializingBean |
| 初始化 | init-method | XML/@Bean |
| 初始化后 | postProcessAfterInitialization | BeanPostProcessor |
| 销毁 | @PreDestroy | JSR-250 |
| 销毁 | destroy | DisposableBean |
| 销毁 | destroy-method | XML/@Bean |
