package demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 配置类
 */
@Configuration
@ComponentScan(basePackages = "demo")
public class AppConfig {

    /**
     * 定义 LifeCycleBean，指定 init-method 和 destroy-method
     */
    @Bean(initMethod = "initMethod", destroyMethod = "destroyMethod")
    public LifeCycleBean lifeCycleBean() {
        return new LifeCycleBean();
    }

    /**
     * 注册 BeanPostProcessor
     */
    @Bean
    public MyBeanPostProcessor myBeanPostProcessor() {
        return new MyBeanPostProcessor();
    }

    /**
     * 注册 BeanFactoryPostProcessor
     */
    @Bean
    public MyBeanFactoryPostProcessor myBeanFactoryPostProcessor() {
        return new MyBeanFactoryPostProcessor();
    }
}
