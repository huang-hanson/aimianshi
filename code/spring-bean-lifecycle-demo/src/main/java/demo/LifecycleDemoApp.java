package demo;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Spring Bean 生命周期完整演示
 */
public class LifecycleDemoApp {

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(repeat('=', 70));
        System.out.println("           Spring Bean 生命周期演示");
        System.out.println(repeat('=', 70));
        System.out.println();

        // 创建 Spring 容器
        AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext();

        // 注册配置类
        context.register(AppConfig.class);

        System.out.println(">>> 容器刷新开始...");
        System.out.println();

        // 刷新容器（触发 Bean 创建和初始化）
        context.refresh();

        System.out.println();
        System.out.println(">>> 容器刷新完成！");
        System.out.println();
        System.out.println(repeat('-', 70));
        System.out.println();

        // 获取 Bean 并使用
        System.out.println(">>> 获取 Bean 并执行业务方法...");
        System.out.println();
        LifeCycleBean bean = context.getBean(LifeCycleBean.class);
        bean.doBusiness();

        System.out.println();
        System.out.println(repeat('-', 70));
        System.out.println();

        // 关闭容器（触发销毁）
        System.out.println(">>> 关闭容器，触发销毁回调...");
        System.out.println();
        context.close();

        System.out.println();
        System.out.println(repeat('=', 70));
        System.out.println("           演示结束！");
        System.out.println(repeat('=', 70));
    }
}
