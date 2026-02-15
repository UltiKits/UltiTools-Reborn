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
 * Tests for the {@link Service} annotation.
 */
@DisplayName("Service Annotation Tests")
class ServiceTest {

    @Nested
    @DisplayName("Annotation Structure Tests")
    class AnnotationStructureTests {

        @Test
        @DisplayName("Should be an annotation")
        void shouldBeAnnotation() {
            assertThat(Service.class.isAnnotation()).isTrue();
        }

        @Test
        @DisplayName("Should have RUNTIME retention")
        void shouldHaveRuntimeRetention() {
            Retention retention = Service.class.getAnnotation(Retention.class);
            assertThat(retention).isNotNull();
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("Should target TYPE only")
        void shouldTargetTypeOnly() {
            Target target = Service.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        @DisplayName("Should be meta-annotated with @Component")
        void shouldBeMetaAnnotatedWithComponent() {
            Component component = Service.class.getAnnotation(Component.class);
            assertThat(component).isNotNull();
        }

        @Test
        @DisplayName("Should have value attribute")
        void shouldHaveValueAttribute() throws NoSuchMethodException {
            Method valueMethod = Service.class.getMethod("value");
            assertThat(valueMethod).isNotNull();
            assertThat(valueMethod.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("value should default to empty string")
        void valueShouldDefaultToEmptyString() throws NoSuchMethodException {
            Method valueMethod = Service.class.getMethod("value");
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
            @Service
            class TestService {
            }

            Service annotation = TestService.class.getAnnotation(Service.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEmpty();
        }

        @Test
        @DisplayName("Should support custom service name")
        void shouldSupportCustomServiceName() {
            @Service("myService")
            class NamedService {
            }

            Service annotation = NamedService.class.getAnnotation(Service.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("myService");
        }

        @Test
        @DisplayName("Class should also have @Component annotation via meta-annotation")
        void classShouldAlsoHaveComponentAnnotationViaMetaAnnotation() {
            @Service
            class TestService {
            }

            // Service is meta-annotated with @Component, but the annotation
            // is not directly on the class. We verify Service has @Component.
            assertThat(Service.class.isAnnotationPresent(Component.class)).isTrue();
        }
    }
}
