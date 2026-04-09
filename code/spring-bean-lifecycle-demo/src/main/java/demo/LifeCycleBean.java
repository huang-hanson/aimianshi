package demo;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 演示完整的 Bean 生命周期
 */
@Component
public class LifeCycleBean implements InitializingBean, DisposableBean {

    private String name = "LifeCycleBean";

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    /**
     * 1. 构造方法
     */
    public LifeCycleBean() {
        System.out.println(repeat('★', 70));
        System.out.println("[1] 构造方法：创建 Bean 实例");
        System.out.println("    → new LifeCycleBean()");
        System.out.println(repeat('★', 70));
    }

    /**
     * 2. @PostConstruct 注解（JSR-250）
     */
    @PostConstruct
    public void postConstruct() {
        System.out.println(repeat('★', 70));
        System.out.println("[2] @PostConstruct：JSR-250 标准注解");
        System.out.println("    → 属性填充完成后执行");
        System.out.println("    → Spring 推荐使用的初始化方式");
        System.out.println(repeat('★', 70));
    }

    /**
     * 3. InitializingBean 接口（Spring 原生）
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println(repeat('★', 70));
        System.out.println("[3] InitializingBean.afterPropertiesSet()");
        System.out.println("    → 所有属性设置完成后执行");
        System.out.println("    → Spring 原生的初始化接口");
        System.out.println(repeat('★', 70));
    }

    /**
     * 4. init-method（XML 或@Bean 配置）
     */
    public void initMethod() {
        System.out.println(repeat('★', 70));
        System.out.println("[4] init-method：自定义初始化方法");
        System.out.println("    → @Bean(initMethod=\"initMethod\") 配置");
        System.out.println("    → 或者 XML 中的 init-method 属性");
        System.out.println(repeat('★', 70));
    }

    /**
     * 5. 业务方法
     */
    public void doBusiness() {
        System.out.println();
        System.out.println("▶▶▶ 执行业务方法：doBusiness()");
        System.out.println("    → Bean 已经初始化完成，可以正常使用了！");
        System.out.println();
    }

    /**
     * 6. @PreDestroy 注解（JSR-250）
     */
    @PreDestroy
    public void preDestroy() {
        System.out.println(repeat('★', 70));
        System.out.println("[5] @PreDestroy：JSR-250 标准注解");
        System.out.println("    → 容器关闭时执行");
        System.out.println("    → 销毁前的回调");
        System.out.println(repeat('★', 70));
    }

    /**
     * 7. DisposableBean 接口（Spring 原生）
     */
    @Override
    public void destroy() throws Exception {
        System.out.println(repeat('★', 70));
        System.out.println("[6] DisposableBean.destroy()");
        System.out.println("    → Spring 原生的销毁接口");
        System.out.println("    → 释放资源、关闭连接等");
        System.out.println(repeat('★', 70));
    }

    /**
     * 8. destroy-method（XML 或@Bean 配置）
     */
    public void destroyMethod() {
        System.out.println(repeat('★', 70));
        System.out.println("[7] destroy-method：自定义销毁方法");
        System.out.println("    → @Bean(destroyMethod=\"destroyMethod\") 配置");
        System.out.println("    → 或者 XML 中的 destroy-method 属性");
        System.out.println("    → 最后一个执行的销毁回调");
        System.out.println(repeat('★', 70));
    }
}
