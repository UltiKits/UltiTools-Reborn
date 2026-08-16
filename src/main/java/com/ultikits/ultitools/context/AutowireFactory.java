package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Autowired;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Simple autowire factory to replace Spring's AutowireCapableBeanFactory.
 * <br>
 * 简单的自动装配工厂，用于替换Spring的AutowireCapableBeanFactory。
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Autowiring requires reflection
@ApiStatus.Internal
public class AutowireFactory {
    private static final Logger LOGGER = Logger.getLogger(AutowireFactory.class.getName());

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
                Autowired autowired = field.getAnnotation(Autowired.class);
                if (autowired != null) {
                    try {
                        field.setAccessible(true);
                        Object dependency = container.getBean(field.getType());
                        if (dependency != null) {
                            field.set(bean, dependency);
                        } else if (autowired.required()) {
                            warnUnresolvedRequiredDependency(field);
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Failed to autowire field: " + field.getName(), e);
                    }
                }
            }

            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Warn that a {@code required = true} dependency could not be resolved.
     * <br>
     * 一个 {@code required = true} 的依赖没解析出来时发出警告。
     * <p>
     * 在此之前 {@code required()} 从来没被读过：解析不到就把字段留成 null，插件照常启动，
     * NPE 在之后某处才炸，而模块作者分不清是「bean 没注册」「不在扫描范围」「宿主是 new 出来
     * 的所以根本没走注入」还是「自己忘了写注解」——四种原因同一个症状。见 issue #182。
     * <p>
     * 本版只警告，不抛。可能已经有下游模块正带着 null 字段跑在生产上，需要一个版本的观察期；
     * 改抛留给 6.3.0。构造器注入路径（{@code SimpleContainer#createBeanWithConstructorInjection}）
     * 一直是直接抛的，两者的不一致也留到那时一起收。
     *
     * @param field the unresolved field <br> 没解析出来的字段
     */
    private void warnUnresolvedRequiredDependency(Field field) {
        // 这个 Throwable 不是被抛出来的，它只负责把「哪条路径触发了这次注入」记进日志：
        // 容器建 bean、命令注册、监听器注册、插件对象注入是四个不同的入口，
        // 而模块作者拿到 NPE 的时候现场早就离开这里了。
        Throwable site = new Throwable("@Autowired injection site (not a thrown exception; "
                + "the stack below shows which code path triggered this injection)");
        LOGGER.log(Level.WARNING, "[UltiTools-API] @Autowired could not resolve "
                + field.getType().getName() + " for field " + field.getDeclaringClass().getName()
                + "." + field.getName() + " - the field stays null, so the NullPointerException will "
                + "surface somewhere else, not here. Check that the target class is annotated with "
                + "@Service/@Component, that it is inside the module's scanBasePackages, and that the "
                + "owning object was created by the container rather than with 'new'. "
                + "Use @Autowired(required = false) if the dependency really is optional.", site);
    }
}
