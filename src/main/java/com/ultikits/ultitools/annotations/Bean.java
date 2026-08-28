package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean annotation to replace Spring's @Bean.
 * <p>
 * {@code name()} and {@code value()} are mutual aliases (D-06): declaring both with different
 * content is a malformed declaration and fails the module's load, naming the declaring method
 * and both declared values; declaring both with identical content is legal. An empty or absent
 * {@code name()}/{@code value()} falls back to the factory method's own name, exactly as before
 * this attribute took effect. When more than one name is declared, the <b>first</b> element is
 * the bean's registered name and the rest are aliases sharing the same fully-assembled
 * instance -- Spring's own {@code @Bean} convention. A declared element that is blank or
 * whitespace-only also fails the module's load, naming the offending method: a name that cannot
 * name anything is not a usable third state between "declared" and "absent".
 * <p>
 * {@code @Target} also includes {@link ElementType#ANNOTATION_TYPE}, but no code path in this
 * framework acts on a {@code @Bean} placed there -- that gap is tracked as a separate issue
 * (03-CONTEXT.md &sect; Deferred Ideas) and is not implemented by this attribute's own fix.
 * <br>
 * Bean注解，用于替换Spring的@Bean。
 * <p>
 * {@code name()} 与 {@code value()} 互为别名（D-06）：两者都非空且内容不同即为畸形声明，会导致
 * 模块加载失败，错误信息同时指出声明该属性的方法与两个已声明的值；两者内容相同则合法。
 * {@code name()}/{@code value()} 为空或缺省时回退到工厂方法自身的名称，与该属性生效前的行为一致。
 * 声明多个名称时，<b>第一个</b>元素是该 Bean 的注册名，其余元素是共享同一个完整装配实例的别名——
 * 这是 Spring 自身 {@code @Bean} 的约定。已声明的元素若为空白或仅由空白字符组成，同样会导致
 * 模块加载失败，并指出出问题的方法：一个无法命名任何东西的名称，不是"已声明"与"缺省"之间可用的
 * 第三种状态。
 * <p>
 * {@code @Target} 中还包含 {@link ElementType#ANNOTATION_TYPE}，但本框架中没有任何代码路径会
 * 处理放置在那里的 {@code @Bean}——该缺口作为独立 issue 追踪（见 03-CONTEXT.md 的
 * "Deferred Ideas" 一节），本属性自身的修复不实现它。
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
    /**
     * Bean name.
     * <br>
     * Bean名称。
     *
     * @return bean name <br> Bean名称
     */
    String[] name() default {};

    /**
     * Bean value (alias for name).
     * <br>
     * Bean值（名称的别名）。
     *
     * @return bean value <br> Bean值
     */
    String[] value() default {};
}
