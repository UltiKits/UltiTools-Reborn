package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Autowired;

import java.lang.reflect.Field;
import org.jetbrains.annotations.ApiStatus;

/**
 * Simple autowire factory to replace Spring's AutowireCapableBeanFactory.
 * <br>
 * 简单的自动装配工厂，用于替换Spring的AutowireCapableBeanFactory。
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Autowiring requires reflection
@ApiStatus.Internal
public class AutowireFactory {
    private final SimpleContainer container;

    public AutowireFactory(SimpleContainer container) {
        this.container = container;
    }

    /**
     * Autowire bean dependencies.
     * <br>
     * 自动装配Bean依赖。
     *
     * @param bean the bean to autowire <br> 要自动装配的Bean
     */
    public void autowireBean(Object bean) {
        Class<?> clazz = bean.getClass();
        
        // Process fields from current class and all superclasses
        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();
            
            for (Field field : fields) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    try {
                        field.setAccessible(true);
                        Object dependency = container.getBean(field.getType());
                        if (dependency != null) {
                            field.set(bean, dependency);
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Failed to autowire field: " + field.getName(), e);
                    }
                }
            }
            
            clazz = clazz.getSuperclass();
        }
    }
}
