package com.ultikits.ultitools.context;

import org.jetbrains.annotations.ApiStatus;

/**
 * Simple bean factory to replace Spring's BeanFactory.
 */
@ApiStatus.Internal
public class BeanFactory {
    private final SimpleContainer container;

    public BeanFactory(SimpleContainer container) {
        this.container = container;
    }

    /**
     * Register singleton.
     *
     * @param name singleton name
     * @param instance singleton instance
     */
    public void registerSingleton(String name, Object instance) {
        container.registerSingleton(name, instance);
    }

    /**
     * Get bean by name.
     *
     * @param name bean name
     * @return bean instance
     */
    public Object getBean(String name) {
        return container.getBean(name);
    }

    /**
     * Get bean by type.
     *
     * @param type bean type
     * @return bean instance
     */
    public <T> T getBean(Class<T> type) {
        return container.getBean(type);
    }
}
