package com.ultikits.ultitools.context;

import org.jetbrains.annotations.ApiStatus;

/**
 * Bean post processor interface to customize bean initialization.
 */
@ApiStatus.Internal
public interface BeanPostProcessor {
    
    /**
     * Apply this BeanPostProcessor to the given new bean instance before any bean
     * initialization callbacks.
     *
     * @param bean the new bean instance
     * @param beanName the name of the bean
     * @return the bean instance to use
     */
    default Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    /**
     * Apply this BeanPostProcessor to the given new bean instance after any bean
     * initialization callbacks.
     *
     * @param bean the new bean instance
     * @param beanName the name of the bean
     * @return the bean instance to use
     */
    default Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
