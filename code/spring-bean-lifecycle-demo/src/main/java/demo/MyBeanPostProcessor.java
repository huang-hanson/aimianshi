package demo;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * BeanPostProcessor - 在 Bean 实例化前后进行扩展处理
 */
@Component
public class MyBeanPostProcessor implements BeanPostProcessor {

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    public MyBeanPostProcessor() {
        System.out.println("[BPP] 构造方法");
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        if ("lifeCycleBean".equals(beanName)) {
            System.out.println(repeat('-', 70));
            System.out.println("[BPP] postProcessBeforeInitialization()");
            System.out.println("       → 初始化之前（@PostConstruct 之前）");
            System.out.println(repeat('-', 70));
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if ("lifeCycleBean".equals(beanName)) {
            System.out.println(repeat('-', 70));
            System.out.println("[BPP] postProcessAfterInitialization()");
            System.out.println("       → 初始化之后（所有初始化方法完成）");
            System.out.println("       → AOP 代理通常在这里返回代理对象！");
            System.out.println(repeat('-', 70));
        }
        return bean;
    }
}
