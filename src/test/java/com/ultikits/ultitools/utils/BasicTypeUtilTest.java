package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BasicTypeUtil 测试类
 */
@DisplayName("BasicTypeUtil 测试")
class BasicTypeUtilTest {

    @Nested
    @DisplayName("isBasicType(Class<?>) 方法测试")
    class IsBasicTypeClassTests {

        @Test
        @DisplayName("应该识别所有原始类型")
        void shouldRecognizePrimitiveTypes() {
            assertThat(BasicTypeUtil.isBasicType(boolean.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(byte.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(char.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(short.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(int.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(long.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(float.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(double.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(void.class)).isTrue();
        }

        @Test
        @DisplayName("应该识别所有包装类型")
        void shouldRecognizeWrapperTypes() {
            assertThat(BasicTypeUtil.isBasicType(Boolean.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Byte.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Character.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Short.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Integer.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Long.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Float.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Double.class)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Void.class)).isTrue();
        }

        @Test
        @DisplayName("应该识别 String 为基本类型")
        void shouldRecognizeStringAsBasicType() {
            assertThat(BasicTypeUtil.isBasicType(String.class)).isTrue();
        }

        @Test
        @DisplayName("应该返回 false 对于非基本类型")
        void shouldReturnFalseForNonBasicTypes() {
            assertThat(BasicTypeUtil.isBasicType(Object.class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(List.class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(ArrayList.class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(HashMap.class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(Date.class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(BigInteger.class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(BigDecimal.class)).isFalse();
        }

        @Test
        @DisplayName("应该返回 false 对于 null 参数")
        void shouldReturnFalseForNullClass() {
            assertThat(BasicTypeUtil.isBasicType((Class<?>) null)).isFalse();
        }

        @Test
        @DisplayName("应该返回 false 对于数组类型")
        void shouldReturnFalseForArrayTypes() {
            assertThat(BasicTypeUtil.isBasicType(int[].class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(String[].class)).isFalse();
            assertThat(BasicTypeUtil.isBasicType(Integer[].class)).isFalse();
        }

        @Test
        @DisplayName("应该返回 false 对于自定义类")
        void shouldReturnFalseForCustomClasses() {
            assertThat(BasicTypeUtil.isBasicType(BasicTypeUtilTest.class)).isFalse();
        }
    }

    @Nested
    @DisplayName("isBasicType(Object) 方法测试")
    class IsBasicTypeObjectTests {

        @Test
        @DisplayName("应该识别原始类型的值")
        void shouldRecognizePrimitiveValues() {
            assertThat(BasicTypeUtil.isBasicType(true)).isTrue();
            assertThat(BasicTypeUtil.isBasicType((byte) 1)).isTrue();
            assertThat(BasicTypeUtil.isBasicType('a')).isTrue();
            assertThat(BasicTypeUtil.isBasicType((short) 1)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(1)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(1L)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(1.0f)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(1.0d)).isTrue();
        }

        @Test
        @DisplayName("应该识别包装类型的值")
        void shouldRecognizeWrapperValues() {
            assertThat(BasicTypeUtil.isBasicType(Boolean.TRUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Byte.valueOf((byte) 1))).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Character.valueOf('a'))).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Short.valueOf((short) 1))).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Integer.valueOf(1))).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Long.valueOf(1L))).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Float.valueOf(1.0f))).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Double.valueOf(1.0d))).isTrue();
        }

        @Test
        @DisplayName("应该识别 String 值")
        void shouldRecognizeStringValue() {
            assertThat(BasicTypeUtil.isBasicType("hello")).isTrue();
            assertThat(BasicTypeUtil.isBasicType("")).isTrue();
        }

        @Test
        @DisplayName("应该返回 false 对于非基本类型对象")
        void shouldReturnFalseForNonBasicTypeObjects() {
            assertThat(BasicTypeUtil.isBasicType(new ArrayList<>())).isFalse();
            assertThat(BasicTypeUtil.isBasicType(new HashMap<>())).isFalse();
            assertThat(BasicTypeUtil.isBasicType(new Date())).isFalse();
            assertThat(BasicTypeUtil.isBasicType(new Object())).isFalse();
        }

        @Test
        @DisplayName("应该返回 false 对于 null 对象")
        void shouldReturnFalseForNullObject() {
            assertThat(BasicTypeUtil.isBasicType((Object) null)).isFalse();
        }

        @Test
        @DisplayName("应该返回 false 对于数组对象")
        void shouldReturnFalseForArrayObjects() {
            assertThat(BasicTypeUtil.isBasicType(new int[]{1, 2, 3})).isFalse();
            assertThat(BasicTypeUtil.isBasicType(new String[]{"a", "b"})).isFalse();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("私有构造函数应该抛出 UnsupportedOperationException")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<BasicTypeUtil> constructor = BasicTypeUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            
            assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("应该正确处理极值")
        void shouldHandleExtremeValues() {
            assertThat(BasicTypeUtil.isBasicType(Integer.MAX_VALUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Integer.MIN_VALUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Long.MAX_VALUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Long.MIN_VALUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Double.MAX_VALUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Double.MIN_VALUE)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Float.POSITIVE_INFINITY)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Float.NEGATIVE_INFINITY)).isTrue();
            assertThat(BasicTypeUtil.isBasicType(Double.NaN)).isTrue();
        }

        @Test
        @DisplayName("应该正确处理空字符串")
        void shouldHandleEmptyString() {
            assertThat(BasicTypeUtil.isBasicType("")).isTrue();
        }
    }
}
