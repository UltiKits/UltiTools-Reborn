package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * Enable auto register annotation.
 * <p>
 * 启用自动注册注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/advanced/auto-register.html">Auto Register</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface EnableAutoRegister {
    /**
     * @return scan package <br> 扫描包
     */
    String scanPackage() default "";

    /**
     * @return whether auto register event listener <br> 是否自动注册事件监听器
     */
    boolean eventListener() default true;

    /**
     * @return whether auto register command executor <br> 是否自动注册命令执行器
     */
    boolean cmdExecutor() default true;

    /**
     * @return whether auto register config entity <br> 是否自动注册配置实体
     */
    boolean config() default true;
}
