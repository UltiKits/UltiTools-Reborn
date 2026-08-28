package com.ultikits.ultitools.context.isolationfixture.broken;

/**
 * Deliberately not annotated {@code @Service}/{@code @Component}, so component scanning never
 * registers a bean definition for it and {@link BrokenService}'s required {@code @Autowired}
 * field can never resolve.
 * <br>
 * 故意不带 {@code @Service}/{@code @Component} 注解，因此组件扫描永远不会为它注册 bean 定义，
 * {@link BrokenService} 的必需 {@code @Autowired} 字段也就永远无法解析。
 */
public class UnregisteredDependency {
}
