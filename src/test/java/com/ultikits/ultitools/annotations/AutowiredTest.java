package com.ultikits.ultitools.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Autowired} annotation.
 */
@DisplayName("Autowired Annotation Tests")
class AutowiredTest {

    @Nested
    @DisplayName("Annotation Structure Tests")
    class AnnotationStructureTests {

        @Test
        @DisplayName("Should be an annotation")
        void shouldBeAnnotation() {
            assertThat(Autowired.class.isAnnotation()).isTrue();
        }

        @Test
        @DisplayName("Should have RUNTIME retention")
        void shouldHaveRuntimeRetention() {
            Retention retention = Autowired.class.getAnnotation(Retention.class);
            assertThat(retention).isNotNull();
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("Should target FIELD, METHOD, and CONSTRUCTOR")
        void shouldTargetFieldMethodAndConstructor() {
            Target target = Autowired.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value()).containsExactlyInAnyOrder(
                    ElementType.FIELD,
                    ElementType.METHOD,
                    ElementType.CONSTRUCTOR
            );
        }

        @Test
        @DisplayName("Should have required attribute")
        void shouldHaveRequiredAttribute() throws NoSuchMethodException {
            Method requiredMethod = Autowired.class.getMethod("required");
            assertThat(requiredMethod).isNotNull();
            assertThat(requiredMethod.getReturnType()).isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("required should default to true")
        void requiredShouldDefaultToTrue() throws NoSuchMethodException {
            Method requiredMethod = Autowired.class.getMethod("required");
            Object defaultValue = requiredMethod.getDefaultValue();
            assertThat(defaultValue).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("Field Annotation Tests")
    class FieldAnnotationTests {

        @Test
        @DisplayName("Should be applicable to fields")
        void shouldBeApplicableToFields() throws NoSuchFieldException {
            class TestClass {
                @Autowired
                private String dependency;
            }

            Field field = TestClass.class.getDeclaredField("dependency");
            Autowired annotation = field.getAnnotation(Autowired.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.required()).isTrue();
        }

        @Test
        @DisplayName("Should respect required=false on field")
        void shouldRespectRequiredFalseOnField() throws NoSuchFieldException {
            class TestClass {
                @Autowired(required = false)
                private String optionalDependency;
            }

            Field field = TestClass.class.getDeclaredField("optionalDependency");
            Autowired annotation = field.getAnnotation(Autowired.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.required()).isFalse();
        }
    }

    @Nested
    @DisplayName("Constructor Annotation Tests")
    class ConstructorAnnotationTests {

        @Test
        @DisplayName("Should be applicable to constructors")
        void shouldBeApplicableToConstructors() {
            // Local classes have different constructor signatures, use declared constructor
            Constructor<?>[] constructors = ConstructorTestClass.class.getDeclaredConstructors();
            boolean found = false;
            for (Constructor<?> constructor : constructors) {
                if (constructor.isAnnotationPresent(Autowired.class)) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }

    // Helper class for constructor test
    static class ConstructorTestClass {
        private final String dependency;

        @Autowired
        public ConstructorTestClass(String dependency) {
            this.dependency = dependency;
        }
    }

    @Nested
    @DisplayName("Method Annotation Tests")
    class MethodAnnotationTests {

        @Test
        @DisplayName("Should be applicable to setter methods")
        void shouldBeApplicableToSetterMethods() throws NoSuchMethodException {
            class TestClass {
                private String dependency;

                @Autowired
                public void setDependency(String dependency) {
                    this.dependency = dependency;
                }
            }

            Method method = TestClass.class.getMethod("setDependency", String.class);
            Autowired annotation = method.getAnnotation(Autowired.class);
            assertThat(annotation).isNotNull();
        }
    }
}
