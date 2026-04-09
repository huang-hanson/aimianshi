package demo;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * BeanFactoryPostProcessor - 在 Bean 实例化之前修改 Bean 定义
 */
@Component
public class MyBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    public MyBeanFactoryPostProcessor() {
        System.out.println("[BFPP] 构造方法");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        System.out.println(repeat('=', 70));
        System.out.println("[BFPP] BeanFactoryPostProcessor.postProcessBeanFactory()");
        System.out.println("       → 修改 Bean 定义（在实例化之前）");
        System.out.println(repeat('=', 70));
    }
}
