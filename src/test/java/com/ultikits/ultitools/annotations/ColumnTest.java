package com.ultikits.ultitools.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Column} annotation.
 */
@DisplayName("Column Annotation Tests")
class ColumnTest {

    @Nested
    @DisplayName("Annotation Structure Tests")
    class AnnotationStructureTests {

        @Test
        @DisplayName("Should be an annotation")
        void shouldBeAnnotation() {
            assertThat(Column.class.isAnnotation()).isTrue();
        }

        @Test
        @DisplayName("Should have RUNTIME retention")
        void shouldHaveRuntimeRetention() {
            Retention retention = Column.class.getAnnotation(Retention.class);
            assertThat(retention).isNotNull();
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("Should target FIELD only")
        void shouldTargetFieldOnly() {
            Target target = Column.class.getAnnotation(Target.class);
            assertThat(target).isNotNull();
            assertThat(target.value()).containsExactly(ElementType.FIELD);
        }

        @Test
        @DisplayName("Should have value attribute")
        void shouldHaveValueAttribute() throws NoSuchMethodException {
            Method valueMethod = Column.class.getMethod("value");
            assertThat(valueMethod).isNotNull();
            assertThat(valueMethod.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("Should have type attribute")
        void shouldHaveTypeAttribute() throws NoSuchMethodException {
            Method typeMethod = Column.class.getMethod("type");
            assertThat(typeMethod).isNotNull();
            assertThat(typeMethod.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("type should default to VARCHAR(255)")
        void typeShouldDefaultToVarchar255() throws NoSuchMethodException {
            Method typeMethod = Column.class.getMethod("type");
            Object defaultValue = typeMethod.getDefaultValue();
            assertThat(defaultValue).isEqualTo("VARCHAR(255)");
        }
    }

    @Nested
    @DisplayName("Field Annotation Tests")
    class FieldAnnotationTests {

        @Test
        @DisplayName("Should be applicable to fields")
        void shouldBeApplicableToFields() throws NoSuchFieldException {
            class TestEntity {
                @Column("user_name")
                private String name;
            }

            Field field = TestEntity.class.getDeclaredField("name");
            Column annotation = field.getAnnotation(Column.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("user_name");
            assertThat(annotation.type()).isEqualTo("VARCHAR(255)"); // Default value
        }

        @Test
        @DisplayName("Should support custom column type")
        void shouldSupportCustomColumnType() throws NoSuchFieldException {
            class TestEntity {
                @Column(value = "price", type = "DECIMAL(10,2)")
                private double price;
            }

            Field field = TestEntity.class.getDeclaredField("price");
            Column annotation = field.getAnnotation(Column.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("price");
            assertThat(annotation.type()).isEqualTo("DECIMAL(10,2)");
        }

        @Test
        @DisplayName("Should support various SQL types")
        void shouldSupportVariousSqlTypes() throws NoSuchFieldException {
            class TestEntity {
                @Column(value = "count", type = "INTEGER")
                private int count;

                @Column(value = "amount", type = "FLOAT")
                private float amount;

                @Column(value = "description", type = "TEXT")
                private String description;

                @Column(value = "is_active", type = "BOOLEAN")
                private boolean active;
            }

            assertThat(TestEntity.class.getDeclaredField("count")
                    .getAnnotation(Column.class).type()).isEqualTo("INTEGER");
            assertThat(TestEntity.class.getDeclaredField("amount")
                    .getAnnotation(Column.class).type()).isEqualTo("FLOAT");
            assertThat(TestEntity.class.getDeclaredField("description")
                    .getAnnotation(Column.class).type()).isEqualTo("TEXT");
            assertThat(TestEntity.class.getDeclaredField("active")
                    .getAnnotation(Column.class).type()).isEqualTo("BOOLEAN");
        }
    }
}
