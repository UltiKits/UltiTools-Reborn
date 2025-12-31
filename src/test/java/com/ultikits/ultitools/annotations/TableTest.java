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
 * Tests for the {@link Table} annotation.
 */
@DisplayName("Table Annotation Tests")
class TableTest {

    @Nested
    @DisplayName("Annotation Structure Tests")
    class AnnotationStructureTests {

        @Test
        @DisplayName("Should be an annotation")
        void shouldBeAnnotation() {
            assertThat(Table.class.isAnnotation()).isTrue();
        }

        @Test
        @DisplayName("Should have RUNTIME retention")
        void shouldHaveRuntimeRetention() {
            Retention retention = Table.class.getAnnotation(Retention.class);
            assertThat(retention).isNotNull();
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("Should target TYPE only")
        void shouldTargetTypeOnly() {
            Target target = Table.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        @DisplayName("Should have value attribute")
        void shouldHaveValueAttribute() throws NoSuchMethodException {
            Method valueMethod = Table.class.getMethod("value");
            assertThat(valueMethod).isNotNull();
            assertThat(valueMethod.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("value should not have default (required)")
        void valueShouldNotHaveDefault() throws NoSuchMethodException {
            Method valueMethod = Table.class.getMethod("value");
            Object defaultValue = valueMethod.getDefaultValue();
            assertThat(defaultValue).isNull();
        }
    }

    @Nested
    @DisplayName("Class Annotation Tests")
    class ClassAnnotationTests {

        @Test
        @DisplayName("Should be applicable to classes with table name")
        void shouldBeApplicableToClassesWithTableName() {
            @Table("user_data")
            class UserEntity {
            }

            Table annotation = UserEntity.class.getAnnotation(Table.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("user_data");
        }

        @Test
        @DisplayName("Should support various table name formats")
        void shouldSupportVariousTableNameFormats() {
            @Table("simple")
            class SimpleEntity {
            }

            @Table("snake_case_name")
            class SnakeCaseEntity {
            }

            @Table("CamelCaseName")
            class CamelCaseEntity {
            }

            assertThat(SimpleEntity.class.getAnnotation(Table.class).value())
                    .isEqualTo("simple");
            assertThat(SnakeCaseEntity.class.getAnnotation(Table.class).value())
                    .isEqualTo("snake_case_name");
            assertThat(CamelCaseEntity.class.getAnnotation(Table.class).value())
                    .isEqualTo("CamelCaseName");
        }
    }
}
