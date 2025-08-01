package com.ultikits.ultitools.context;

/**
 * Simple bean factory to replace Spring's BeanFactory.
 * <br>
 * 简单的Bean工厂，用于替换Spring的BeanFactory。
 */
public class BeanFactory {
    private final SimpleContainer container;

    public BeanFactory(SimpleContainer container) {
        this.container = container;
    }

    /**
     * Register singleton.
     * <br>
     * 注册单例。
     *
     * @param name singleton name <br> 单例名称
     * @param instance singleton instance <br> 单例实例
     */
    public void registerSingleton(String name, Object instance) {
        container.registerSingleton(name, instance);
    }

    /**
     * Get bean by name.
     * <br>
     * 通过名称获取Bean。
     *
     * @param name bean name <br> Bean名称
     * @return bean instance <br> Bean实例
     */
    public Object getBean(String name) {
        return container.getBean(name);
    }

    /**
     * Get bean by type.
     * <br>
     * 通过类型获取Bean。
     *
     * @param type bean type <br> Bean类型
     * @return bean instance <br> Bean实例
     */
    public <T> T getBean(Class<T> type) {
        return container.getBean(type);
    }
}
