package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ReflectionUtil 测试类
 */
@DisplayName("ReflectionUtil 测试")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test for reflection utility
class ReflectionUtilTest {

    // 测试用注解
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
    @interface TestAnnotation {
        String value() default "";
    }

    // 测试用父类
    @TestAnnotation("parent")
    static class ParentClass {
        private String parentField;
        protected int protectedField;

        public ParentClass() {}

        public String getParentField() {
            return parentField;
        }

        public void setParentField(String parentField) {
            this.parentField = parentField;
        }

        protected void parentMethod() {}
    }

    // 测试用子类
    @TestAnnotation("child")
    static class ChildClass extends ParentClass {
        @TestAnnotation("field")
        private String childField;
        private double number;

        public ChildClass() {}

        public ChildClass(String childField) {
            this.childField = childField;
        }

        public ChildClass(String childField, double number) {
            this.childField = childField;
            this.number = number;
        }

        public String getChildField() {
            return childField;
        }

        public void setChildField(String childField) {
            this.childField = childField;
        }

        @TestAnnotation("method")
        public void annotatedMethod() {}

        public void normalMethod() {}
    }

    // 测试用的没有无参构造函数的类
    static class NoDefaultConstructor {
        private final String value;

        public NoDefaultConstructor(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    @Nested
    @DisplayName("getAllFields 方法测试")
    class GetAllFieldsTests {

        @Test
        @DisplayName("应该获取类的所有字段（包括父类）")
        void shouldGetAllFieldsIncludingParent() {
            List<Field> fields = ReflectionUtil.getAllFields(ChildClass.class);

            assertThat(fields).hasSizeGreaterThanOrEqualTo(4);
            assertThat(fields.stream().map(Field::getName))
                .contains("childField", "number", "parentField", "protectedField");
        }

        @Test
        @DisplayName("应该处理没有字段的类")
        void shouldHandleClassWithNoFields() {
            List<Field> fields = ReflectionUtil.getAllFields(Object.class);
            assertThat(fields).isEmpty();
        }

        @Test
        @DisplayName("应该获取类的所有字段作为数组")
        void shouldGetAllFieldsAsArray() {
            Field[] fields = ReflectionUtil.getFields(ChildClass.class);

            assertThat(fields).hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Nested
    @DisplayName("getField 方法测试")
    class GetFieldTests {

        @Test
        @DisplayName("应该获取指定字段")
        void shouldGetSpecifiedField() {
            Field field = ReflectionUtil.getField(ChildClass.class, "childField");

            assertThat(field).isNotNull();
            assertThat(field.getName()).isEqualTo("childField");
        }

        @Test
        @DisplayName("应该获取父类字段")
        void shouldGetParentField() {
            Field field = ReflectionUtil.getField(ChildClass.class, "parentField");

            assertThat(field).isNotNull();
            assertThat(field.getName()).isEqualTo("parentField");
        }

        @Test
        @DisplayName("应该返回 null 对于不存在的字段")
        void shouldReturnNullForNonExistentField() {
            Field field = ReflectionUtil.getField(ChildClass.class, "nonExistent");

            assertThat(field).isNull();
        }
    }

    @Nested
    @DisplayName("getFieldValue 方法测试")
    class GetFieldValueTests {

        @Test
        @DisplayName("应该获取字段值（通过 Field）")
        void shouldGetFieldValueByField() {
            ChildClass obj = new ChildClass("test");
            Field field = ReflectionUtil.getField(ChildClass.class, "childField");

            Object value = ReflectionUtil.getFieldValue(obj, field);

            assertThat(value).isEqualTo("test");
        }

        @Test
        @DisplayName("应该获取字段值（通过字段名）")
        void shouldGetFieldValueByName() {
            ChildClass obj = new ChildClass("test");

            Object value = ReflectionUtil.getFieldValue(obj, "childField");

            assertThat(value).isEqualTo("test");
        }

        @Test
        @DisplayName("应该抛出异常对于不存在的字段名")
        void shouldThrowExceptionForNonExistentFieldName() {
            ChildClass obj = new ChildClass("test");

            assertThatThrownBy(() -> ReflectionUtil.getFieldValue(obj, "nonExistent"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Field not found");
        }
    }

    @Nested
    @DisplayName("setFieldValue 方法测试")
    class SetFieldValueTests {

        @Test
        @DisplayName("应该设置字段值（通过 Field）")
        void shouldSetFieldValueByField() {
            ChildClass obj = new ChildClass();
            Field field = ReflectionUtil.getField(ChildClass.class, "childField");

            ReflectionUtil.setFieldValue(obj, field, "newValue");

            assertThat(obj.getChildField()).isEqualTo("newValue");
        }

        @Test
        @DisplayName("应该设置字段值（通过字段名）")
        void shouldSetFieldValueByName() {
            ChildClass obj = new ChildClass();

            ReflectionUtil.setFieldValue(obj, "childField", "newValue");

            assertThat(obj.getChildField()).isEqualTo("newValue");
        }

        @Test
        @DisplayName("应该抛出异常对于不存在的字段名")
        void shouldThrowExceptionForNonExistentFieldNameOnSet() {
            ChildClass obj = new ChildClass();

            assertThatThrownBy(() -> ReflectionUtil.setFieldValue(obj, "nonExistent", "value"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Field not found");
        }
    }

    @Nested
    @DisplayName("newInstance 方法测试")
    class NewInstanceTests {

        @Test
        @DisplayName("应该使用无参构造器创建实例")
        void shouldCreateInstanceWithNoArgConstructor() {
            ChildClass instance = ReflectionUtil.newInstance(ChildClass.class);

            assertThat(instance).isNotNull();
            assertThat(instance.getChildField()).isNull();
        }

        @Test
        @DisplayName("应该使用带参构造器创建实例")
        void shouldCreateInstanceWithArgs() {
            ChildClass instance = ReflectionUtil.newInstance(ChildClass.class, "test");

            assertThat(instance).isNotNull();
            assertThat(instance.getChildField()).isEqualTo("test");
        }

        @Test
        @DisplayName("应该使用多参构造器创建实例")
        void shouldCreateInstanceWithMultipleArgs() {
            ChildClass instance = ReflectionUtil.newInstance(ChildClass.class, "test", 3.14);

            assertThat(instance).isNotNull();
            assertThat(instance.getChildField()).isEqualTo("test");
        }

        @Test
        @DisplayName("应该抛出异常当找不到合适的构造器")
        void shouldThrowExceptionWhenNoSuitableConstructor() {
            assertThatThrownBy(() -> ReflectionUtil.newInstance(ChildClass.class, 1, 2, 3))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("newInstanceIfPossible 应该返回 null 当创建失败")
        void newInstanceIfPossibleShouldReturnNullOnFailure() {
            Object result = ReflectionUtil.newInstanceIfPossible(NoDefaultConstructor.class);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("newInstanceIfPossible 应该成功创建实例")
        void newInstanceIfPossibleShouldCreateInstance() {
            ChildClass result = ReflectionUtil.newInstanceIfPossible(ChildClass.class);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("注解操作测试")
    class AnnotationTests {

        @Test
        @DisplayName("应该获取类上的注解")
        void shouldGetAnnotationOnClass() {
            TestAnnotation annotation = ReflectionUtil.getAnnotation(ChildClass.class, TestAnnotation.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("child");
        }

        @Test
        @DisplayName("应该获取字段上的注解")
        void shouldGetAnnotationOnField() {
            Field field = ReflectionUtil.getField(ChildClass.class, "childField");
            TestAnnotation annotation = ReflectionUtil.getAnnotation(field, TestAnnotation.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("field");
        }

        @Test
        @DisplayName("应该返回 null 当注解不存在")
        void shouldReturnNullWhenAnnotationNotPresent() {
            TestAnnotation annotation = ReflectionUtil.getAnnotation(String.class, TestAnnotation.class);

            assertThat(annotation).isNull();
        }

        @Test
        @DisplayName("应该判断类是否有注解")
        void shouldCheckIfClassHasAnnotation() {
            assertThat(ReflectionUtil.hasAnnotation(ChildClass.class, TestAnnotation.class)).isTrue();
            assertThat(ReflectionUtil.hasAnnotation(String.class, TestAnnotation.class)).isFalse();
        }

        @Test
        @DisplayName("应该判断字段是否有注解")
        void shouldCheckIfFieldHasAnnotation() {
            Field annotatedField = ReflectionUtil.getField(ChildClass.class, "childField");
            Field normalField = ReflectionUtil.getField(ChildClass.class, "number");

            assertThat(ReflectionUtil.hasAnnotation(annotatedField, TestAnnotation.class)).isTrue();
            assertThat(ReflectionUtil.hasAnnotation(normalField, TestAnnotation.class)).isFalse();
        }
    }

    @Nested
    @DisplayName("方法操作测试")
    class MethodTests {

        @Test
        @DisplayName("应该获取所有方法")
        void shouldGetAllMethods() {
            Method[] methods = ReflectionUtil.getMethods(ChildClass.class);

            assertThat(methods).isNotEmpty();
            assertThat(java.util.Arrays.stream(methods).map(Method::getName))
                .contains("annotatedMethod", "normalMethod", "getChildField", "setChildField");
        }

        @Test
        @DisplayName("应该按条件过滤方法")
        void shouldFilterMethodsByPredicate() {
            Predicate<Method> filter = m -> m.getName().startsWith("get");
            Method[] methods = ReflectionUtil.getMethods(ChildClass.class, filter);

            assertThat(methods).allMatch(m -> m.getName().startsWith("get"));
        }

        @Test
        @DisplayName("应该调用方法")
        void shouldInvokeMethod() throws Exception {
            ChildClass obj = new ChildClass("test");
            Method getter = ChildClass.class.getMethod("getChildField");

            String result = ReflectionUtil.invoke(obj, getter);

            assertThat(result).isEqualTo("test");
        }

        @Test
        @DisplayName("应该获取包括父类的方法")
        void shouldGetMethodsIncludingParent() {
            Method[] methods = ReflectionUtil.getMethods(ChildClass.class);

            assertThat(java.util.Arrays.stream(methods).map(Method::getName))
                .contains("parentMethod");
        }

        @Test
        @DisplayName("应该按名称过滤时排除覆盖泛型接口方法产生的桥接方法重复项")
        void shouldNotDoubleCountBridgeMethodFromGenericInterfaceOverride() {
            // ComparableFixture.compareTo(ComparableFixture) overrides Comparable<T>.compareTo(T),
            // which the compiler backs with a synthetic bridge compareTo(Object) on the same class.
            // Comparable is an interface, so getAllMethods' superclass walk never sees a second,
            // independently-erased declaration to worry about de-duplicating - this isolates the
            // bridge-skip behavior from the separate (and out of scope) question of recognizing an
            // override across generic-erasure boundaries between two *classes*. A raw
            // getDeclaredMethods() walk returns both the real method and the bridge as separate
            // "compareTo" hits - the shape that let AbstractCommandExecutor#getMethod invoke every
            // hit and produce a duplicate tab-completion entry for an overridden generic method.
            Predicate<Method> filter = m -> m.getName().equals("compareTo");
            Method[] methods = ReflectionUtil.getMethods(ComparableFixture.class, filter);

            assertThat(methods).hasSize(1);
            assertThat(methods[0].isBridge()).isFalse();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("私有构造函数应该抛出 UnsupportedOperationException")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<ReflectionUtil> constructor = ReflectionUtil.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("类型兼容性测试")
    class TypeCompatibilityTests {

        @Test
        @DisplayName("应该处理基本类型和包装类型的兼容性")
        void shouldHandlePrimitiveAndWrapperCompatibility() {
            // 测试通过 ChildClass 验证类型兼容性
            ChildClass instance = ReflectionUtil.newInstance(ChildClass.class, "test", Double.valueOf(42.0));
            assertThat(instance.getChildField()).isEqualTo("test");
        }

        @Test
        @DisplayName("应该处理 int 基本类型构造器参数")
        void shouldHandleIntPrimitiveParameter() {
            IntConstructorClass instance = ReflectionUtil.newInstance(IntConstructorClass.class, Integer.valueOf(42));
            assertThat(instance.getValue()).isEqualTo(42);
        }

        @Test
        @DisplayName("应该处理 long 基本类型构造器参数")
        void shouldHandleLongPrimitiveParameter() {
            LongConstructorClass instance = ReflectionUtil.newInstance(LongConstructorClass.class, Long.valueOf(100L));
            assertThat(instance.getValue()).isEqualTo(100L);
        }

        @Test
        @DisplayName("应该处理 float 基本类型构造器参数")
        void shouldHandleFloatPrimitiveParameter() {
            FloatConstructorClass instance = ReflectionUtil.newInstance(FloatConstructorClass.class, Float.valueOf(3.14f));
            assertThat(instance.getValue()).isEqualTo(3.14f);
        }

        @Test
        @DisplayName("应该处理 boolean 基本类型构造器参数")
        void shouldHandleBooleanPrimitiveParameter() {
            BooleanConstructorClass instance = ReflectionUtil.newInstance(BooleanConstructorClass.class, Boolean.TRUE);
            assertThat(instance.getValue()).isTrue();
        }

        @Test
        @DisplayName("应该处理 byte 基本类型构造器参数")
        void shouldHandleBytePrimitiveParameter() {
            ByteConstructorClass instance = ReflectionUtil.newInstance(ByteConstructorClass.class, Byte.valueOf((byte) 127));
            assertThat(instance.getValue()).isEqualTo((byte) 127);
        }

        @Test
        @DisplayName("应该处理 short 基本类型构造器参数")
        void shouldHandleShortPrimitiveParameter() {
            ShortConstructorClass instance = ReflectionUtil.newInstance(ShortConstructorClass.class, Short.valueOf((short) 1000));
            assertThat(instance.getValue()).isEqualTo((short) 1000);
        }

        @Test
        @DisplayName("应该处理 char 基本类型构造器参数")
        void shouldHandleCharPrimitiveParameter() {
            CharConstructorClass instance = ReflectionUtil.newInstance(CharConstructorClass.class, Character.valueOf('A'));
            assertThat(instance.getValue()).isEqualTo('A');
        }
    }

    // 用于基本类型构造器测试的辅助类
    static class IntConstructorClass {
        private final int value;
        public IntConstructorClass(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    static class LongConstructorClass {
        private final long value;
        public LongConstructorClass(long value) { this.value = value; }
        public long getValue() { return value; }
    }

    static class FloatConstructorClass {
        private final float value;
        public FloatConstructorClass(float value) { this.value = value; }
        public float getValue() { return value; }
    }

    static class BooleanConstructorClass {
        private final boolean value;
        public BooleanConstructorClass(boolean value) { this.value = value; }
        public boolean getValue() { return value; }
    }

    static class ByteConstructorClass {
        private final byte value;
        public ByteConstructorClass(byte value) { this.value = value; }
        public byte getValue() { return value; }
    }

    static class ShortConstructorClass {
        private final short value;
        public ShortConstructorClass(short value) { this.value = value; }
        public short getValue() { return value; }
    }

    static class CharConstructorClass {
        private final char value;
        public CharConstructorClass(char value) { this.value = value; }
        public char getValue() { return value; }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("应该处理私有字段")
        void shouldHandlePrivateFields() {
            ChildClass obj = new ChildClass();
            ReflectionUtil.setFieldValue(obj, "childField", "private");

            assertThat(ReflectionUtil.getFieldValue(obj, "childField")).isEqualTo("private");
        }

        @Test
        @DisplayName("应该处理受保护的字段")
        void shouldHandleProtectedFields() {
            ChildClass obj = new ChildClass();
            ReflectionUtil.setFieldValue(obj, "protectedField", 100);

            assertThat(ReflectionUtil.getFieldValue(obj, "protectedField")).isEqualTo(100);
        }

        @Test
        @DisplayName("应该处理 null 过滤条件")
        void shouldHandleNullFilter() {
            Method[] methods = ReflectionUtil.getMethods(ChildClass.class, null);
            assertThat(methods).isNotEmpty();
        }

        @Test
        @DisplayName("应该处理 newInstance 空参数数组")
        void shouldHandleNewInstanceWithEmptyParams() {
            ChildClass instance = ReflectionUtil.newInstance(ChildClass.class, new Object[0]);
            assertThat(instance).isNotNull();
        }

        @Test
        @DisplayName("应该处理 newInstance null 参数")
        void shouldHandleNewInstanceWithNullParams() {
            ChildClass instance = ReflectionUtil.newInstance(ChildClass.class, (Object[]) null);
            assertThat(instance).isNotNull();
        }

        @Test
        @DisplayName("应该处理参数中有 null 的情况")
        void shouldHandleNullInParams() {
            NullableParamClass instance = ReflectionUtil.newInstance(NullableParamClass.class, (String) null);
            assertThat(instance).isNotNull();
            assertThat(instance.getValue()).isNull();
        }

        @Test
        @DisplayName("应该正确处理类型不匹配的模糊匹配")
        void shouldHandleFuzzyMatchWithIncompatibleTypes() {
            // IncompatibleClass 有一个接受 List<String> 的构造器，
            // 传递一个 String 应该无法匹配，导致抛出异常
            assertThatThrownBy(() -> ReflectionUtil.newInstance(IncompatibleClass.class, "not a list"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No suitable constructor found");
        }
    }

    // 用于 null 参数测试的辅助类
    static class NullableParamClass {
        private final String value;
        public NullableParamClass(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    // 用于类型不匹配测试的辅助类
    static class IncompatibleClass {
        public IncompatibleClass(List<String> items) {}
    }

    // 用于 getMethods 桥接方法去重测试的辅助类。实现 Comparable<T> 让编译器为
    // compareTo(ComparableFixture) 生成一个同类内的合成桥接方法 compareTo(Object)。
    //
    // Used by the getMethods bridge-de-duplication test. Implementing Comparable<T> makes the
    // compiler emit a synthetic bridge compareTo(Object) alongside compareTo(ComparableFixture) on
    // this same class.
    static class ComparableFixture implements Comparable<ComparableFixture> {
        private final int value;

        ComparableFixture(int value) { this.value = value; }

        @Override
        public int compareTo(ComparableFixture other) { return Integer.compare(value, other.value); }
    }

    // 用于 getAllMethods 测试的辅助类。
    // 必须声明为 ReflectionUtilTest（顶层类）的静态嵌套类，而不是 @Nested 测试类 GetAllMethods
    // 内部——JLS 在 source 1.8 下禁止在非静态内部类（@Nested 类正是这种）里声明静态成员
    // （staticMethod() 除外无法绕开：它本身就是本用例要覆盖的场景）。
    //
    // Declared as static nested classes of ReflectionUtilTest (the top-level class) rather than
    // inside the @Nested test class GetAllMethods: under source 1.8, the JLS forbids a static
    // member declaration inside a non-static inner class (which @Nested classes are), and
    // staticMethod() below is exactly the case this test needs to cover.
    public static class GetAllMethodsBase {
        public void inherited() { }
        public void overridden() { }
        public final void finalMethod() { }
        private void privateMethod() { }
        public static void staticMethod() { }
    }

    public static class GetAllMethodsChild extends GetAllMethodsBase {
        @Override
        public void overridden() { }
        public void own() { }
    }

    public static class GetAllMethodsGenericBase<T> {
        public T id(T value) { return value; }
    }

    public static class GetAllMethodsStringChild extends GetAllMethodsGenericBase<String> {
        @Override
        public String id(String value) { return value; }
    }

    @Nested
    @DisplayName("getAllMethods")
    class GetAllMethods {

        private java.util.List<String> namesOf(java.util.List<Method> methods) {
            java.util.List<String> names = new ArrayList<>();
            for (Method method : methods) {
                names.add(method.getName());
            }
            java.util.Collections.sort(names);
            return names;
        }

        @Test
        @DisplayName("Should include methods declared on superclasses")
        void shouldIncludeInherited() {
            assertTrue(namesOf(ReflectionUtil.getAllMethods(GetAllMethodsChild.class)).contains("inherited"));
        }

        @Test
        @DisplayName("Should include private, final and static methods from the hierarchy")
        void shouldIncludeNonOverridableMethods() {
            java.util.List<String> names = namesOf(ReflectionUtil.getAllMethods(GetAllMethodsChild.class));
            assertTrue(names.contains("finalMethod"), names.toString());
            assertTrue(names.contains("privateMethod"), names.toString());
            assertTrue(names.contains("staticMethod"), names.toString());
        }

        @Test
        @DisplayName("Should return an overridden method exactly once")
        void shouldDedupeOverride() {
            int count = 0;
            for (Method method : ReflectionUtil.getAllMethods(GetAllMethodsChild.class)) {
                if ("overridden".equals(method.getName())) {
                    count++;
                }
            }
            assertEquals(1, count, "an overridden signature must appear once, not once per level");
        }

        @Test
        @DisplayName("Should keep the most specific override")
        void shouldKeepMostSpecific() {
            for (Method method : ReflectionUtil.getAllMethods(GetAllMethodsChild.class)) {
                if ("overridden".equals(method.getName())) {
                    assertEquals(GetAllMethodsChild.class, method.getDeclaringClass(),
                            "the subclass's override must win, so its annotations are the ones seen");
                }
            }
        }

        @Test
        @DisplayName("Should skip bridge and synthetic methods")
        void shouldSkipBridgeMethods() {
            boolean fixtureHasBridge = false;
            for (Method method : GetAllMethodsStringChild.class.getDeclaredMethods()) {
                if (method.isBridge()) {
                    fixtureHasBridge = true;
                    break;
                }
            }
            assertTrue(fixtureHasBridge,
                    "precondition: the fixture must actually produce a bridge method");

            for (Method method : ReflectionUtil.getAllMethods(GetAllMethodsStringChild.class)) {
                assertFalse(method.isBridge(), "bridge method leaked: " + method);
                assertFalse(method.isSynthetic(), "synthetic method leaked: " + method);
            }
        }

        @Test
        @DisplayName("Should not include Object's methods")
        void shouldStopBeforeObject() {
            assertFalse(namesOf(ReflectionUtil.getAllMethods(GetAllMethodsChild.class)).contains("wait"));
        }

        @Test
        @DisplayName("Should return an empty list for null")
        void shouldHandleNull() {
            assertTrue(ReflectionUtil.getAllMethods(null).isEmpty());
        }
    }
}
