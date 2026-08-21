package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Final;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enforces the {@link Final} contract when the framework loads a module's classes.
 * <p>
 * {@code @Final} replaces the {@code final} keyword for classes that also need AOP, so the JVM no
 * longer enforces it. This validator restores the constraint at class-loading time. Because the
 * framework loads every module, the check is effective across module boundaries, which a
 * compile-time annotation processor could not achieve.
 * <p>
 * <b>Known gaps.</b> The check is not compile-time, so an IDE will not flag a violation. Its
 * coverage is exactly the set of classes {@link ComponentScanner} walks, and for a plugin module
 * that set comes from {@code PluginManager#getPluginScanPackages}'s three-level fallback: a
 * module's declared {@code @UltiToolsModule(scanBasePackages)}, else a {@code @ComponentScan} on
 * the plugin class, else the plugin class's own package. A module that declares neither annotation
 * is still checked - it falls back to its own package rather than dropping out of scanning
 * entirely. The framework's own classes are <b>not</b> checked in production: {@code ContextConfig}
 * declares {@code @ComponentScan("com.ultikits.ultitools")}, but the only thing that acts on that
 * declaration, {@link SimpleContainer#processConfigurationClass(Class)}, has no caller anywhere in
 * {@code src/main} - it is invoked only from tests ({@code ContextConfigTest}, {@code ScanTest}).
 * A plugin module's class is unchecked if it sits outside whichever package that module's own scan
 * actually reaches; a framework class is unchecked unconditionally today.
 * <p>
 * The method-level check is also deliberately <b>non-transitive</b>: it walks from {@code clazz}
 * straight to each ancestor's own declaration and stops at the first ancestor whose declaration is
 * {@code @Final}, so a widening override interposed between them can defeat it. Concretely, if
 * {@code x.A} declares a package-private {@code @Final m()}, a same-package {@code x.B} widens it
 * to {@code public m()} (a genuine override), and {@code y.C} overrides {@code B.m()}, then
 * {@code validate(C)} walks past {@code B.m()} - it is not {@code @Final} - reaches {@code A.m()},
 * and {@code ReflectionUtil.overrides(C.m(), A.m())} is {@code false} because {@code C} and
 * {@code A} are in different packages, so no violation is reported for {@code C}. This is
 * acceptable because the gap can only exist once {@code @Final} has already been bypassed one class
 * earlier: for {@code B} to widen {@code A}'s method at all, {@code B} must sit in {@code A}'s own
 * package, and {@code validate(B)} - run whenever {@code B} is inside the scanned package set -
 * already catches that direct override, since {@code B} and {@code A} share a package and
 * {@code ReflectionUtil.overrides(B.m(), A.m())} is {@code true} there. {@code C} is only ever
 * downstream of a bypass that {@code validate(B)} already reported.
 * <p>
 * 该校验在类加载期恢复 {@code @Final} 的约束。框架加载全部模块，因此跨模块有效。
 * 但它不在编译期生效；覆盖范围严格等于组件扫描实际走到的类。对插件模块而言，这个范围来自
 * {@code PluginManager#getPluginScanPackages} 的三层回退：模块声明的
 * {@code @UltiToolsModule(scanBasePackages)}，其次插件类上的 {@code @ComponentScan}，都未声明
 * 则默认扫插件类自己所在的包——两个注解都不声明的模块依然会被检查，只是回退到自己的包，而不是
 * 完全跳出扫描。框架自身的类在生产环境中<b>不会</b>被检查：{@code ContextConfig} 虽然声明了
 * {@code @ComponentScan("com.ultikits.ultitools")}，但唯一会处理这个声明的
 * {@code SimpleContainer#processConfigurationClass}，在 {@code src/main} 里没有任何调用方——
 * 只有测试（{@code ContextConfigTest}、{@code ScanTest}）会调用它。对插件模块而言，类只要落在
 * 自己扫描实际到达的包之外就不受检查；而框架自身的类，目前无条件地不受检查。
 * <p>
 * 方法级检查同样刻意<b>不具备传递性</b>：它从 {@code clazz} 直接走到每一层祖先自己的声明，遇到
 * 第一个声明为 {@code @Final} 的祖先方法就停下，中间插入一次放宽访问权限的重写就能绕过它。具体
 * 来说：若 {@code x.A} 声明包私有的 {@code @Final m()}，同包的 {@code x.B} 把它放宽为
 * {@code public m()}（这是一次真正的重写），而 {@code y.C} 又重写了 {@code B.m()}，那么
 * {@code validate(C)} 会跳过非 {@code @Final} 的 {@code B.m()}，一路走到 {@code A.m()}，此时
 * {@code ReflectionUtil.overrides(C.m(), A.m())} 因为 {@code C} 与 {@code A} 不同包而返回
 * {@code false}，于是不会为 {@code C} 报告任何违规。这是可以接受的：这个空子只有在 {@code @Final}
 * 已经在上一层被绕过之后才存在——{@code B} 要放宽 {@code A} 的方法，前提就是 {@code B} 与
 * {@code A} 同包，而只要 {@code B} 落在被扫描的包范围内，{@code validate(B)} 就已经会直接抓到这次
 * 重写本身（{@code B} 与 {@code A} 同包，{@code ReflectionUtil.overrides(B.m(), A.m())} 为
 * {@code true}）。{@code C} 只是绕在一次早已被 {@code validate(B)} 报告过的违规下游而已。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class FinalContractValidator {

    private static final Logger LOGGER = Logger.getLogger(FinalContractValidator.class.getName());

    private FinalContractValidator() {
        // Utility class
    }

    /**
     * Validates that the given class does not violate any {@code @Final} contract.
     *
     * @param clazz the class to validate
     * @return one description per violation, empty if the class is compliant
     */
    public static List<String> validate(Class<?> clazz) {
        List<String> violations = new ArrayList<>();
        if (clazz == null || clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) {
            return violations;
        }
        // Proxies are generated by the container itself. @Final constrains module authors, not
        // the framework, so a proxy of a @Final class is legitimate.
        if (ProxyFactory.isProxyClass(clazz)) {
            return violations;
        }

        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass.isAnnotationPresent(Final.class)) {
            violations.add(clazz.getName() + " extends " + superclass.getName()
                    + ", which is annotated @Final and must not be extended. "
                    + "Remove the 'extends' clause, or drop @Final from the parent if extension "
                    + "is intended.");
        }

        // getDeclaredMethods() resolves every parameter and return type of every method it
        // returns, so a class whose signature references a type absent from its own defining
        // class loader throws NoClassDefFoundError right here - not a hypothetical: it is the
        // shape of a soft-dependency integration class (Vault, PlaceholderAPI, ...) with the
        // optional type in a method signature, including one disabled via @ConditionalOnConfig
        // because the dependency may not be installed. Before this method existed, nothing on
        // ComponentScanner's path touched method descriptors, so this check is what newly exposes
        // that class to the Error. Left uncaught, it escapes to ComponentScanner.scanDirectory's
        // production caller as an Error naming an unrelated missing class with no hint that a
        // @Final scan caused it, and ComponentScanner.scanJar catches NoClassDefFoundError and
        // simply skips the class - silently disabling this very check for it. Catching it here
        // instead keeps the check running (via the superclass-level findings already collected
        // above) and leaves a named trace instead of either outcome. See issue #190.
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                Method overridden = findOverriddenSealedMethod(clazz, method);
                if (overridden != null) {
                    violations.add(clazz.getName() + "#" + method.getName() + " overrides "
                            + overridden.getDeclaringClass().getName() + "#" + overridden.getName()
                            + ", which is annotated @Final and must not be overridden.");
                }
            }
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            LOGGER.log(Level.WARNING, "cannot check @Final method contract for " + clazz.getName()
                    + ": " + e.getMessage() + " is not on the classpath", e);
        }
        return violations;
    }

    /**
     * Walks up the hierarchy looking for a {@code @Final} method that {@code method} actually
     * overrides.
     * <p>
     * Matching on name and parameter types alone is not enough: a {@code private}, {@code static},
     * or cross-package package-private method in {@code clazz} that merely shares a signature with
     * an ancestor's {@code @Final} method is not an override at all per JLS 8.4.8.1, and reporting
     * it as one would reject a module that never actually did the thing {@code @Final} forbids.
     * Nor is it enough to compare a symmetric signature key: overriding is directional, so a key
     * comparison misses the mirror-image case - a subclass in the same package widening an
     * ancestor's package-private {@code @Final} method to {@code public}, which is a real override
     * and precisely what {@code @Final} exists to forbid.
     * <p>
     * {@link ReflectionUtil#overrides(Method, Method)} is the single implementation of that rule,
     * shared with {@link ReflectionUtil#getAllMethods(Class)}'s own override collapsing, so the two
     * checks agree by construction rather than by two people independently getting the same JLS
     * rule right. See issue #190.
     * <p>
     * 按名称加参数类型匹配是不够的：{@code clazz} 中与祖先类的 {@code @Final} 方法签名相同的
     * {@code private}、{@code static} 或跨包包私有方法，根据 JLS 8.4.8.1 根本不构成重写。比较对称
     * 的签名 key 同样不够：覆盖是有方向的，key 比较会漏掉镜像的那一半——同包子类把祖先的包私有
     * {@code @Final} 方法放宽为 {@code public}，那是真正的覆盖，也正是 {@code @Final} 要禁止的。
     * 这里改用 {@link ReflectionUtil#overrides(Method, Method)}——该规则的唯一实现，与
     * {@link ReflectionUtil#getAllMethods(Class)} 共用，让两处检查天然一致。
     */
    private static Method findOverriddenSealedMethod(Class<?> clazz, Method method) {
        for (Class<?> current = clazz.getSuperclass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Method candidate = current.getDeclaredMethod(method.getName(),
                        method.getParameterTypes());
                if (candidate.isAnnotationPresent(Final.class)
                        && ReflectionUtil.overrides(method, candidate)) {
                    return candidate;
                }
            } catch (NoSuchMethodException ignored) {
                // Not declared at this level; keep walking up.
            }
        }
        return null;
    }
}
