package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MetricsPackageBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"org.bstats", "your.package"})
    void relocationCheckAllowsSiblingPackage(String root) {
        assertThatCode(() -> constructRelocatedMetrics(root + "x")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"org.bstats", "your.package", "org.bstats.child", "your.package.child"})
    void relocationCheckRejectsExactPackageAndSubpackages(String packageName) {
        assertThatThrownBy(() -> constructRelocatedMetrics(packageName))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private void constructRelocatedMetrics(String packageName) throws Exception {
        // Relocate the actual implementation, including its class literal, rather than testing a
        // duplicate predicate. Disabled metrics perform no network requests or scheduled work.
        // The shipped example root "your.package" contains a Java keyword, but is a legal JVM name.
        Class<?> relocated = new ByteBuddy().with(TypeValidation.DISABLED).redefine(Metrics.MetricsBase.class)
                .name(packageName + ".BoundaryMetrics")
                .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER).getLoaded();
        Object instance = relocated.getConstructor(String.class, String.class, int.class, boolean.class,
                Consumer.class, Consumer.class, Consumer.class, Supplier.class, BiConsumer.class,
                Consumer.class, boolean.class, boolean.class, boolean.class)
                .newInstance("bukkit", "test-server", 1, false,
                        null, null, null, null, null, null, false, false, false);
        relocated.getMethod("shutdown").invoke(instance);
    }
}
