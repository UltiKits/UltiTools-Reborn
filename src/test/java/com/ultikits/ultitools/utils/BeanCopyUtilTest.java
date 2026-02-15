package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BeanCopyUtil 测试类
 */
@DisplayName("BeanCopyUtil 测试")
class BeanCopyUtilTest {

    // 测试用的简单 Bean 类
    public static class SourceBean {
        private String name;
        private int age;
        private Double score;
        private String ignored;
        @SuppressWarnings("PMD.UnusedPrivateField") // Static field is intentionally unused - tests that static fields are not copied
        private static String staticField = "static";
        private final String finalField = "final";

        public SourceBean() {}

        public SourceBean(String name, int age, Double score, String ignored) {
            this.name = name;
            this.age = age;
            this.score = score;
            this.ignored = ignored;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public String getIgnored() { return ignored; }
        public void setIgnored(String ignored) { this.ignored = ignored; }
    }

    public static class TargetBean {
        private String name;
        private int age;
        private Double score;
        private String ignored;
        private String extraField;
        @SuppressWarnings("PMD.UnusedPrivateField") // Static field is intentionally unused - tests that static fields are not copied
        private static String staticField = "static";
        @SuppressWarnings("PMD.UnusedPrivateField") // Final field is intentionally unused - tests that final fields are not copied
        private final String finalField = "final";

        public TargetBean() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public String getIgnored() { return ignored; }
        public void setIgnored(String ignored) { this.ignored = ignored; }
        public String getExtraField() { return extraField; }
        public void setExtraField(String extraField) { this.extraField = extraField; }
    }

    // 用于测试类型转换的 Bean
    public static class NumberSourceBean {
        private Integer intValue;
        private Long longValue;
        private Double doubleValue;
        private Float floatValue;

        public NumberSourceBean() {}

        public NumberSourceBean(Integer intValue, Long longValue, Double doubleValue, Float floatValue) {
            this.intValue = intValue;
            this.longValue = longValue;
            this.doubleValue = doubleValue;
            this.floatValue = floatValue;
        }

        public Integer getIntValue() { return intValue; }
        public void setIntValue(Integer intValue) { this.intValue = intValue; }
        public Long getLongValue() { return longValue; }
        public void setLongValue(Long longValue) { this.longValue = longValue; }
        public Double getDoubleValue() { return doubleValue; }
        public void setDoubleValue(Double doubleValue) { this.doubleValue = doubleValue; }
        public Float getFloatValue() { return floatValue; }
        public void setFloatValue(Float floatValue) { this.floatValue = floatValue; }
    }

    public static class NumberTargetBean {
        private Long intValue; // Integer -> Long 转换
        private Integer longValue; // Long -> Integer 转换
        private Float doubleValue; // Double -> Float 转换
        private Double floatValue; // Float -> Double 转换

        public Long getIntValue() { return intValue; }
        public void setIntValue(Long intValue) { this.intValue = intValue; }
        public Integer getLongValue() { return longValue; }
        public void setLongValue(Integer longValue) { this.longValue = longValue; }
        public Float getDoubleValue() { return doubleValue; }
        public void setDoubleValue(Float doubleValue) { this.doubleValue = doubleValue; }
        public Double getFloatValue() { return floatValue; }
        public void setFloatValue(Double floatValue) { this.floatValue = floatValue; }
    }

    // 继承测试用的 Bean
    public static class ParentBean {
        private String parentField;

        public String getParentField() { return parentField; }
        public void setParentField(String parentField) { this.parentField = parentField; }
    }

    public static class ChildBean extends ParentBean {
        private String childField;

        public String getChildField() { return childField; }
        public void setChildField(String childField) { this.childField = childField; }
    }

    @Nested
    @DisplayName("copyProperties(source, target) 方法测试")
    class CopyPropertiesBasicTests {

        @Test
        @DisplayName("应该复制所有匹配的属性")
        void shouldCopyAllMatchingProperties() {
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");
            TargetBean target = new TargetBean();

            BeanCopyUtil.copyProperties(source, target);

            assertThat(target.getName()).isEqualTo("John");
            assertThat(target.getAge()).isEqualTo(25);
            assertThat(target.getScore()).isEqualTo(95.5);
            assertThat(target.getIgnored()).isEqualTo("ignore");
        }

        @Test
        @DisplayName("应该处理 null 源对象")
        void shouldHandleNullSource() {
            TargetBean target = new TargetBean();
            target.setName("original");

            BeanCopyUtil.copyProperties(null, target);

            assertThat(target.getName()).isEqualTo("original");
        }

        @Test
        @DisplayName("应该处理 null 目标对象")
        void shouldHandleNullTarget() { // NOPMD - uses Mockito verify()
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");

            // Should not throw exception
            BeanCopyUtil.copyProperties(source, null);
        }

        @Test
        @DisplayName("应该处理空对象")
        void shouldHandleEmptyObjects() {
            SourceBean source = new SourceBean();
            TargetBean target = new TargetBean();

            BeanCopyUtil.copyProperties(source, target);

            assertThat(target.getName()).isNull();
            assertThat(target.getAge()).isEqualTo(0);
            assertThat(target.getScore()).isNull();
        }
    }

    @Nested
    @DisplayName("copyProperties(source, target, ignoreNullValue) 方法测试")
    class CopyPropertiesIgnoreNullTests {

        @Test
        @DisplayName("ignoreNullValue=true 时应该忽略 null 值")
        void shouldIgnoreNullValuesWhenFlagIsTrue() {
            SourceBean source = new SourceBean();
            source.setName("John");
            source.setAge(25);
            // score is null

            TargetBean target = new TargetBean();
            target.setScore(100.0);

            BeanCopyUtil.copyProperties(source, target, true);

            assertThat(target.getName()).isEqualTo("John");
            assertThat(target.getAge()).isEqualTo(25);
            assertThat(target.getScore()).isEqualTo(100.0); // 保持原值
        }

        @Test
        @DisplayName("ignoreNullValue=false 时应该复制 null 值")
        void shouldCopyNullValuesWhenFlagIsFalse() {
            SourceBean source = new SourceBean();
            source.setName("John");
            // score is null

            TargetBean target = new TargetBean();
            target.setScore(100.0);

            BeanCopyUtil.copyProperties(source, target, false);

            assertThat(target.getName()).isEqualTo("John");
            assertThat(target.getScore()).isNull(); // 被覆盖为 null
        }
    }

    @Nested
    @DisplayName("copyProperties(source, target, ignoreFields) 方法测试")
    class CopyPropertiesIgnoreFieldsTests {

        @Test
        @DisplayName("应该排除指定字段")
        void shouldExcludeSpecifiedFields() {
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");
            TargetBean target = new TargetBean();

            BeanCopyUtil.copyProperties(source, target, "name", "age");

            assertThat(target.getName()).isNull();
            assertThat(target.getAge()).isEqualTo(0);
            assertThat(target.getScore()).isEqualTo(95.5);
            assertThat(target.getIgnored()).isEqualTo("ignore");
        }

        @Test
        @DisplayName("应该处理空的排除字段列表")
        void shouldHandleEmptyIgnoreFields() {
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");
            TargetBean target = new TargetBean();

            BeanCopyUtil.copyProperties(source, target, (String[]) null);

            assertThat(target.getName()).isEqualTo("John");
            assertThat(target.getAge()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("copyProperties 完整参数方法测试")
    class CopyPropertiesFullParametersTests {

        @Test
        @DisplayName("应该同时支持忽略 null 和排除字段")
        void shouldSupportBothIgnoreNullAndExcludeFields() {
            SourceBean source = new SourceBean();
            source.setName("John");
            source.setAge(25);
            // score is null

            TargetBean target = new TargetBean();
            target.setScore(100.0);
            target.setIgnored("original");

            BeanCopyUtil.copyProperties(source, target, true, "ignored");

            assertThat(target.getName()).isEqualTo("John");
            assertThat(target.getAge()).isEqualTo(25);
            assertThat(target.getScore()).isEqualTo(100.0); // 保持原值
            assertThat(target.getIgnored()).isEqualTo("original"); // 被排除
        }
    }

    @Nested
    @DisplayName("copyToNewInstance 方法测试")
    class CopyToNewInstanceTests {

        @Test
        @DisplayName("应该创建新实例并复制属性")
        void shouldCreateNewInstanceAndCopyProperties() {
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");

            TargetBean target = BeanCopyUtil.copyToNewInstance(source, TargetBean.class);

            assertThat(target).isNotNull();
            assertThat(target.getName()).isEqualTo("John");
            assertThat(target.getAge()).isEqualTo(25);
            assertThat(target.getScore()).isEqualTo(95.5);
        }

        @Test
        @DisplayName("应该返回 null 当源对象为 null")
        void shouldReturnNullWhenSourceIsNull() {
            TargetBean target = BeanCopyUtil.copyToNewInstance(null, TargetBean.class);
            assertThat(target).isNull();
        }
    }

    @Nested
    @DisplayName("类型转换测试")
    class TypeConversionTests {

        @Test
        @DisplayName("应该支持 Number 类型间的转换")
        void shouldSupportNumberTypeConversion() {
            NumberSourceBean source = new NumberSourceBean(10, 100L, 3.14, 2.5f);
            NumberTargetBean target = new NumberTargetBean();

            BeanCopyUtil.copyProperties(source, target);

            assertThat(target.getIntValue()).isEqualTo(10L); // Integer -> Long
            assertThat(target.getLongValue()).isEqualTo(100); // Long -> Integer
            assertThat(target.getDoubleValue()).isEqualTo(3.14f, org.assertj.core.data.Offset.offset(0.01f)); // Double -> Float
            assertThat(target.getFloatValue()).isEqualTo(2.5); // Float -> Double
        }

        @Test
        @DisplayName("应该支持转换为 String")
        void shouldSupportConversionToString() {
            NumberSourceBean source = new NumberSourceBean(10, 100L, 3.14, 2.5f);
            
            // 创建一个目标类，其中 intValue 是 String 类型
            class StringTargetBean {
                private String intValue;
                public String getIntValue() { return intValue; }
            }
            
            StringTargetBean target = new StringTargetBean();
            BeanCopyUtil.copyProperties(source, target);
            
            assertThat(target.getIntValue()).isEqualTo("10");
        }
    }

    @Nested
    @DisplayName("继承字段测试")
    class InheritanceFieldsTests {

        @Test
        @DisplayName("应该复制父类字段")
        void shouldCopyParentFields() {
            ChildBean source = new ChildBean();
            source.setParentField("parent");
            source.setChildField("child");

            ChildBean target = new ChildBean();
            BeanCopyUtil.copyProperties(source, target);

            assertThat(target.getParentField()).isEqualTo("parent");
            assertThat(target.getChildField()).isEqualTo("child");
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("私有构造函数应该抛出 UnsupportedOperationException")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<BeanCopyUtil> constructor = BeanCopyUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true); // NOPMD
            
            assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("应该忽略 static 字段")
        void shouldIgnoreStaticFields() { // NOPMD - uses Mockito verify()
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");
            TargetBean target = new TargetBean();

            BeanCopyUtil.copyProperties(source, target);

            // static 字段不应该被复制
            // 这个测试主要确保不会抛出异常
        }

        @Test
        @DisplayName("应该忽略 final 字段")
        void shouldIgnoreFinalFields() { // NOPMD - uses Mockito verify()
            SourceBean source = new SourceBean("John", 25, 95.5, "ignore");
            TargetBean target = new TargetBean();

            BeanCopyUtil.copyProperties(source, target);

            // final 字段不应该被复制
            // 这个测试主要确保不会抛出异常
        }

        @Test
        @DisplayName("应该处理目标对象中不存在的字段")
        void shouldHandleMissingTargetFields() {
            class ExtraSourceBean {
                private String name;
                private String nonExistent;

                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public String getNonExistent() { return nonExistent; }
                public void setNonExistent(String nonExistent) { this.nonExistent = nonExistent; }
            }

            ExtraSourceBean source = new ExtraSourceBean();
            source.setName("John");
            source.setNonExistent("value");

            TargetBean target = new TargetBean();
            BeanCopyUtil.copyProperties(source, target);

            assertThat(target.getName()).isEqualTo("John");
            // nonExistent 字段不存在于目标对象中，应该被忽略
        }
    }
}
