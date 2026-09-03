package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.exceptions.ContainerException;

import java.lang.reflect.Field;
import org.jetbrains.annotations.ApiStatus;

/**
 * Simple autowire factory to replace Spring's AutowireCapableBeanFactory.
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
     * <p>
     * An unresolvable {@code @Autowired(required = true)} dependency aborts the owning module:
     * this method throws {@link ContainerException} instead of leaving the field {@code null}.
     * Other modules are unaffected, because each module owns its own {@link SimpleContainer}.
     * {@code @Autowired(required = false)} is the documented escape for a genuinely optional
     * dependency, which still resolves to {@code null} silently. Autowiring stops at the first
     * unresolvable required field rather than continuing and reporting only the last one. See
     * issue #201 (D-08).
     *
     * @param bean the bean to autowire
     * @throws ContainerException if a {@code required = true} dependency cannot be resolved
     */
    public void autowireBean(Object bean) {
        Class<?> clazz = bean.getClass();

        // Process fields from current class and all superclasses
        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();

            for (Field field : fields) {
                Autowired autowired = field.getAnnotation(Autowired.class);
                if (autowired != null) {
                    try {
                        field.setAccessible(true);
                        Object dependency = container.getBean(field.getType());
                        if (dependency != null) {
                            field.set(bean, dependency);
                        } else if (autowired.required()) {
                            throw ContainerException.requiredDependencyUnresolved(field);
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
