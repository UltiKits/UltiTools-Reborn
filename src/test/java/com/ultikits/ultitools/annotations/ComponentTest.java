package com.ultikits.ultitools.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Component} annotation.
 */
@DisplayName("Component Annotation Tests")
class ComponentTest {

    @Nested
    @DisplayName("Annotation Structure Tests")
    class AnnotationStructureTests {

        @Test
        @DisplayName("Should be an annotation")
        void shouldBeAnnotation() {
            assertThat(Component.class.isAnnotation()).isTrue();
        }

        @Test
        @DisplayName("Should have RUNTIME retention")
        void shouldHaveRuntimeRetention() {
            Retention retention = Component.class.getAnnotation(Retention.class);
            assertThat(retention).isNotNull();
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("Should target TYPE only")
        void shouldTargetTypeOnly() {
            Target target = Component.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        @DisplayName("Should have value attribute")
        void shouldHaveValueAttribute() throws NoSuchMethodException {
            Method valueMethod = Component.class.getMethod("value");
            assertThat(valueMethod).isNotNull();
            assertThat(valueMethod.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("value should default to empty string")
        void valueShouldDefaultToEmptyString() throws NoSuchMethodException {
            Method valueMethod = Component.class.getMethod("value");
            Object defaultValue = valueMethod.getDefaultValue();
            assertThat(defaultValue).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("Class Annotation Tests")
    class ClassAnnotationTests {

        @Test
        @DisplayName("Should be applicable to classes")
        void shouldBeApplicableToClasses() {
            @Component
            class TestComponent {
            }

            Component annotation = TestComponent.class.getAnnotation(Component.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEmpty();
        }

        @Test
        @DisplayName("Should support custom component name")
        void shouldSupportCustomComponentName() {
            @Component("myComponent")
            class NamedComponent {
            }

            Component annotation = NamedComponent.class.getAnnotation(Component.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("myComponent");
        }
    }
}
