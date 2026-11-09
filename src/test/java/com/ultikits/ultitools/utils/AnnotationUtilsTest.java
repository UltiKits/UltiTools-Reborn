package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * AnnotationUtils structure tests. Its only method, {@code findAnnotation(Class, Class)}, was
 * removed in 6.4.0 (replaced by {@code MergedAnnotationResolver.find}).
 */
@DisplayName("AnnotationUtils")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AnnotationUtilsTest {

    @Test
    @DisplayName("the constructor is private")
    void constructorShouldBePrivate() throws Exception {
        java.lang.reflect.Constructor<?> constructor = AnnotationUtils.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("findAnnotation(Class, Class) was removed in 6.4.0")
    void findAnnotationIsRemoved() {
        assertThat(AnnotationUtils.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("findAnnotation");
    }
}
