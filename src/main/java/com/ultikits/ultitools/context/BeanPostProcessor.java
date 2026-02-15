package com.ultikits.ultitools.context;

/**
 * Bean post processor interface to customize bean initialization.
 * <br>
 * Bean后处理器接口，用于自定义Bean初始化。
 */
public interface BeanPostProcessor {
    
    /**
     * Apply this BeanPostProcessor to the given new bean instance before any bean
     * initialization callbacks.
     * <br>
     * 在任何Bean初始化回调之前，将此BeanPostProcessor应用于给定的新Bean实例。
     *
     * @param bean the new bean instance <br> 新的Bean实例
     * @param beanName the name of the bean <br> Bean的名称
     * @return the bean instance to use <br> 要使用的Bean实例
     */
    default Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    /**
     * Apply this BeanPostProcessor to the given new bean instance after any bean
     * initialization callbacks.
     * <br>
     * 在任何Bean初始化回调之后，将此BeanPostProcessor应用于给定的新Bean实例。
     *
     * @param bean the new bean instance <br> 新的Bean实例
     * @param beanName the name of the bean <br> Bean的名称
     * @return the bean instance to use <br> 要使用的Bean实例
     */
    default Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
