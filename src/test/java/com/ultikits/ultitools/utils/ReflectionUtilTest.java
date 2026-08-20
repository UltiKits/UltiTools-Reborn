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

import com.ultikits.testfixtures.overrideslots.pkgx.PackagePrivateSlotBase;
import com.ultikits.testfixtures.overrideslots.pkgx.SamePackageWideningMiddle;
import com.ultikits.testfixtures.overrideslots.pkgy.CrossPackageWideningChild;
import com.ultikits.testfixtures.overrideslots.pkgy.TransitiveWideningLeaf;

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

    // 用于 getAllMethods "不可覆盖的声明各自成槽" 用例的辅助类：父子两层各自声明同名同参数的
    // private / static 方法。二者都不参与覆盖，因此两层都必须保留——这是防止修复过度合并的护栏。
    // 与上面的 GetAllMethodsBase 同理，必须声明在顶层类下而不是 @Nested 测试类里。
    //
    // Fixtures for the "non-overridable declarations each get their own slot" cases: a parent and a
    // child each declaring a same-name, same-parameter private (resp. static) method. Neither
    // participates in overriding, so both levels must survive - a guard against an over-collapsing
    // fix. Declared at the top level for the same source-1.8 reason as GetAllMethodsBase above.
    // 参数列表不同 -> 必然不是覆盖。Used by the overrides() signature-mismatch test.
    public static class DifferentParamsBase {
        public void slot(String value) { }
    }

    public static class DifferentParamsChild extends DifferentParamsBase {
        public void slot() { }
    }

    public static class PrivateSlotBase {
        private void slot() { }
    }

    public static class PrivateSlotChild extends PrivateSlotBase {
        private void slot() { }
    }

    public static class StaticSlotBase {
        public static void slot() { }
    }

    public static class StaticSlotChild extends StaticSlotBase {
        public static void slot() { }
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

        private java.util.List<Method> named(java.util.List<Method> methods, String name) {
            java.util.List<Method> matches = new ArrayList<>();
            for (Method method : methods) {
                if (name.equals(method.getName())) {
                    matches.add(method);
                }
            }
            return matches;
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
        @DisplayName("Should collapse a package-private method widened to public by a same-package "
                + "subclass into one entry")
        void shouldCollapseSamePackageWidening() {
            // JLS 8.4.8.1: a package-private method IS overridden by a same-signature method in a
            // same-package subclass, and nothing there constrains the OVERRIDING method's access -
            // widening to public is permitted and leaves the override relation intact. See issue
            // #190: a symmetric name-based key gives the two declarations different keys (one
            // carries the package, the other does not) and lets both survive.
            java.util.List<Method> slots =
                    named(ReflectionUtil.getAllMethods(SamePackageWideningMiddle.class), "slot");

            assertEquals(1, slots.size(),
                    "a public override of a same-package package-private method is still an "
                            + "override and must collapse to one entry: " + slots);
            assertEquals(SamePackageWideningMiddle.class, slots.get(0).getDeclaringClass(),
                    "the subclass's override must win, so its annotations are the ones seen");
        }

        @Test
        @DisplayName("Should collapse a three-level transitively overridden method into one entry")
        void shouldCollapseTransitiveOverride() {
            // pkgx.PackagePrivateSlotBase (package-private) <- pkgx.SamePackageWideningMiddle
            // (public, same package: overrides) <- pkgy.TransitiveWideningLeaf (public, different
            // package: overrides the middle, not the root, directly). Overriding is transitive per
            // JLS 8.4.8.1, so all three are one slot. A de-dup that compares each candidate only
            // against the surviving representative keeps the root as a second entry.
            java.util.List<Method> slots =
                    named(ReflectionUtil.getAllMethods(TransitiveWideningLeaf.class), "slot");

            assertEquals(1, slots.size(),
                    "overriding is transitive: the leaf overrides the middle, which overrides the "
                            + "root, so all three declarations are one slot: " + slots);
            assertEquals(TransitiveWideningLeaf.class, slots.get(0).getDeclaringClass(),
                    "the most derived declaration must win");
        }

        @Test
        @DisplayName("Should keep a cross-package package-private method separate from a subclass's "
                + "public method of the same signature")
        void shouldKeepCrossPackageWideningSeparate() {
            // Guard against an over-collapsing fix. With no same-package intermediate to bridge
            // them, pkgy.CrossPackageWideningChild.slot() does not override pkgx's package-private
            // slot() - the parent's is not even accessible here - so both must survive.
            java.util.List<Method> slots =
                    named(ReflectionUtil.getAllMethods(CrossPackageWideningChild.class), "slot");

            assertEquals(2, slots.size(),
                    "a package-private method is overridden only from the same package "
                            + "(JLS 8.4.8.1), and there is no same-package intermediate here: "
                            + slots);
            assertTrue(slots.get(0).getDeclaringClass() == CrossPackageWideningChild.class,
                    "subclass declarations must still come first: " + slots);
            assertTrue(slots.get(1).getDeclaringClass() == PackagePrivateSlotBase.class,
                    "the parent's distinct method must still be present: " + slots);
        }

        @Test
        @DisplayName("Should keep same-signature private methods on two levels separate")
        void shouldKeepPrivateMethodsSeparate() {
            // Guard against an over-collapsing fix. private methods are dispatched with
            // invokespecial and never participate in overriding (JLS 8.4.8.1), so these are two
            // distinct methods that merely share a name.
            java.util.List<Method> slots =
                    named(ReflectionUtil.getAllMethods(PrivateSlotChild.class), "slot");

            assertEquals(2, slots.size(),
                    "private methods cannot override one another, so both must survive: " + slots);
        }

        @Test
        @DisplayName("Should keep same-signature static methods on two levels separate")
        void shouldKeepStaticMethodsSeparate() {
            // Guard against an over-collapsing fix. A static method hides, rather than overrides,
            // the one above it - a different relation, and two distinct methods.
            java.util.List<Method> slots =
                    named(ReflectionUtil.getAllMethods(StaticSlotChild.class), "slot");

            assertEquals(2, slots.size(),
                    "static methods hide rather than override, so both must survive: " + slots);
        }

        @Test
        @DisplayName("Should return an empty list for null")
        void shouldHandleNull() {
            assertTrue(ReflectionUtil.getAllMethods(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("overrides")
    class Overrides {

        private Method declared(Class<?> owner, String name) {
            try {
                return owner.getDeclaredMethod(name);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("fixture is missing " + owner.getName() + "#" + name, e);
            }
        }

        @Test
        @DisplayName("Should hold when a same-package subclass widens a package-private method to "
                + "public")
        void shouldHoldForSamePackageWidening() {
            // JLS 8.4.8.1 places no condition on the OVERRIDING method's access.
            assertTrue(ReflectionUtil.overrides(
                    declared(SamePackageWideningMiddle.class, "slot"),
                    declared(PackagePrivateSlotBase.class, "slot")));
        }

        @Test
        @DisplayName("Should not hold in the reverse direction")
        void shouldNotHoldReversed() {
            // Overriding is directional - the whole reason a symmetric key cannot express it.
            assertFalse(ReflectionUtil.overrides(
                    declared(PackagePrivateSlotBase.class, "slot"),
                    declared(SamePackageWideningMiddle.class, "slot")));
        }

        @Test
        @DisplayName("Should not hold across packages with no same-package intermediate")
        void shouldNotHoldAcrossPackages() {
            assertFalse(ReflectionUtil.overrides(
                    declared(CrossPackageWideningChild.class, "slot"),
                    declared(PackagePrivateSlotBase.class, "slot")));
        }

        @Test
        @DisplayName("Should not hold directly for a transitively overridden ancestor")
        void shouldNotHoldDirectlyForTransitiveAncestor() {
            // The leaf overrides the root only THROUGH the middle declaration. overrides() states
            // the direct rule; getAllMethods is what composes it into transitive slots.
            assertFalse(ReflectionUtil.overrides(
                    declared(TransitiveWideningLeaf.class, "slot"),
                    declared(PackagePrivateSlotBase.class, "slot")));
            assertTrue(ReflectionUtil.overrides(
                    declared(TransitiveWideningLeaf.class, "slot"),
                    declared(SamePackageWideningMiddle.class, "slot")));
        }

        @Test
        @DisplayName("Should not hold for private or static declarations")
        void shouldNotHoldForNonOverridable() {
            assertFalse(ReflectionUtil.overrides(
                    declared(PrivateSlotChild.class, "slot"),
                    declared(PrivateSlotBase.class, "slot")));
            assertFalse(ReflectionUtil.overrides(
                    declared(StaticSlotChild.class, "slot"),
                    declared(StaticSlotBase.class, "slot")));
        }

        @Test
        @DisplayName("Should not hold for a declaration against itself")
        void shouldNotHoldForSameDeclaration() {
            // The supertype must be PROPER: a class does not override its own method.
            Method slot = declared(SamePackageWideningMiddle.class, "slot");
            assertFalse(ReflectionUtil.overrides(slot, slot));
        }

        @Test
        @DisplayName("Should not hold when names or parameter types differ")
        void shouldNotHoldForDifferentSignatures() throws Exception {
            Method inherited = GetAllMethodsBase.class.getDeclaredMethod("inherited");
            Method overridden = GetAllMethodsChild.class.getDeclaredMethod("overridden");
            assertFalse(ReflectionUtil.overrides(overridden, inherited), "different names");

            Method noParams = DifferentParamsChild.class.getDeclaredMethod("slot");
            Method withParam = DifferentParamsBase.class.getDeclaredMethod("slot", String.class);
            assertFalse(ReflectionUtil.overrides(noParams, withParam), "different parameter lists");
        }

        @Test
        @DisplayName("Should return false for null arguments")
        void shouldHandleNulls() {
            Method slot = declared(SamePackageWideningMiddle.class, "slot");
            assertFalse(ReflectionUtil.overrides(null, slot));
            assertFalse(ReflectionUtil.overrides(slot, null));
            assertFalse(ReflectionUtil.overrides(null, null));
        }
    }

    @Nested
    @DisplayName("signatureOf")
    class SignatureOf {

        @Test
        @DisplayName("Should give the same key to an override that does not change visibility")
        void shouldMatchForPlainOverride() throws Exception {
            assertEquals(
                    ReflectionUtil.signatureOf(
                            GetAllMethodsChild.class.getDeclaredMethod("overridden")),
                    ReflectionUtil.signatureOf(
                            GetAllMethodsBase.class.getDeclaredMethod("overridden")));
        }

        @Test
        @DisplayName("Should give different keys to a package-private method and the public "
                + "override that widens it")
        void shouldBeAsymmetricForWidening() throws Exception {
            // Documents the limitation the javadoc warns about, so that anyone tempted to use this
            // key as an override test has the counter-example in front of them: these two ARE the
            // same method per JLS 8.4.8.1, and their keys differ. Use overrides(Method, Method).
            assertFalse(ReflectionUtil.signatureOf(
                            SamePackageWideningMiddle.class.getDeclaredMethod("slot"))
                    .equals(ReflectionUtil.signatureOf(
                            PackagePrivateSlotBase.class.getDeclaredMethod("slot"))),
                    "if this ever becomes true, signatureOf changed - re-check its javadoc");
            assertTrue(ReflectionUtil.overrides(
                            SamePackageWideningMiddle.class.getDeclaredMethod("slot"),
                            PackagePrivateSlotBase.class.getDeclaredMethod("slot")),
                    "...while the real relation does hold");
        }

        @Test
        @DisplayName("Should keep private and static declarations on different levels distinct")
        void shouldSeparateNonOverridableDeclarations() throws Exception {
            assertFalse(ReflectionUtil.signatureOf(PrivateSlotChild.class.getDeclaredMethod("slot"))
                    .equals(ReflectionUtil.signatureOf(
                            PrivateSlotBase.class.getDeclaredMethod("slot"))));
            assertFalse(ReflectionUtil.signatureOf(StaticSlotChild.class.getDeclaredMethod("slot"))
                    .equals(ReflectionUtil.signatureOf(
                            StaticSlotBase.class.getDeclaredMethod("slot"))));
        }
    }
}
