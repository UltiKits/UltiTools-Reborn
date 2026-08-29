package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * DependencyUtils 测试类
 * 使用 Mockito mock UltiToolsPlugin 来直接测试 DependencyUtils.getPluginPackages 方法
 */
@DisplayName("DependencyUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DependencyUtilsTest {

    @Nested
    @DisplayName("getPluginPackages 方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getPluginPackages方法应该存在")
        void getPluginPackagesMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = DependencyUtils.class.getMethod(
                "getPluginPackages", UltiToolsPlugin.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String[].class);
        }
    }

    @Nested
    @DisplayName("getPluginPackages 实际方法测试 - 使用 Mock UltiToolsPlugin")
    class GetPluginPackagesActualMethodTests {

        @Test
        @DisplayName("没有任何注解时应该返回类的包名")
        void shouldReturnClassPackageWhenNoAnnotations() {
            // mock 一个没有注解的 UltiToolsPlugin 子类
            UltiToolsPlugin mockPlugin = mock(PlainMockPlugin.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.ultikits.ultitools.utils");
        }

        @Test
        @DisplayName("有 EnableAutoRegister 但 scanPackage 为空时应该返回类的包名")
        void shouldReturnClassPackageWhenEnableAutoRegisterWithEmptyScanPackage() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithEmptyEnableAutoRegister.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.ultikits.ultitools.utils");
        }

        @Test
        @DisplayName("有 EnableAutoRegister 且 scanPackage 不为空时应该返回 scanPackage")
        void shouldReturnScanPackageWhenEnableAutoRegisterWithScanPackage() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithEnableAutoRegister.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.custom.scan.package");
        }

        @Test
        @DisplayName("有 ComponentScan 且 value 不为空时应该返回 value")
        void shouldReturnValueWhenComponentScanWithValue() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithComponentScanValue.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(2);
            assertThat(packages).containsExactly("com.value.one", "com.value.two");
        }

        @Test
        @DisplayName("有 ComponentScan 且 value 为空但 basePackages 不为空时应该返回 basePackages")
        void shouldReturnBasePackagesWhenComponentScanWithBasePackages() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithComponentScanBasePackages.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(2);
            assertThat(packages).containsExactly("com.base.one", "com.base.two");
        }

        @Test
        @DisplayName("有 ComponentScan 但 value 和 basePackages 都为空时应该返回类的包名")
        void shouldReturnClassPackageWhenComponentScanEmpty() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithEmptyComponentScan.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.ultikits.ultitools.utils");
        }

        @Test
        @DisplayName("ComponentScan 与 EnableAutoRegister 同时声明时应该按声明顺序累加，而不是互相排斥")
        void componentScanAndEnableAutoRegisterContributeAdditively() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithBothAnnotations.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            // 累加式：ComponentScan 的 value 先贡献，EnableAutoRegister 的 scanPackage 后贡献，
            // 二者都保留而不是互相排斥（SILENT-22 / 04-08 Task 2）。
            assertThat(packages).hasSize(2);
            assertThat(packages).containsExactly("com.componentscan.priority", "com.enableautoregister.additional");
        }

        @Test
        @DisplayName("ComponentScan 有多个包时应该返回所有包")
        void shouldReturnAllPackagesWhenComponentScanHasMultiplePackages() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithMultiplePackages.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(4);
            assertThat(packages).containsExactly("com.pkg1", "com.pkg2", "com.pkg3", "com.pkg4");
        }

        @Test
        @DisplayName("EnableAutoRegister 的 scanPackage 可以包含深层嵌套包名")
        void enableAutoRegisterScanPackageCanContainDeeplyNestedPackage() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithDeeplyNestedPackage.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.very.deeply.nested.package.structure");
        }
    }

    @Nested
    @DisplayName("元注解解析测试 (SILENT-22 / 04-08 Task 2)")
    class MetaAnnotationResolutionTests {

        @Test
        @DisplayName("只标注 @UltiToolsModule(scanBasePackages = {...}) 时应该返回声明的包，而不是类自身的包")
        void shouldReturnDeclaredPackageForUltiToolsModuleOnly() {
            // 这是这个方法在修复前的确切缺陷：isAnnotationPresent(ComponentScan.class) 只看
            // 直接注解，@UltiToolsModule 是元注解，所以修复前这里会静默落回类自身的包名，
            // 丢弃 scanBasePackages 声明的值。
            UltiToolsPlugin mockPlugin = mock(MockPluginWithUltiToolsModuleScanBasePackages.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.example.moduleonly");
        }

        @Test
        @DisplayName("只标注 @UltiToolsModule 且声明两个 scanBasePackages 时应该按声明顺序返回两个")
        void shouldReturnBothPackagesForUltiToolsModuleWithTwoScanBasePackages() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithUltiToolsModuleTwoScanBasePackages.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(2);
            assertThat(packages).containsExactly("com.example.moduleone", "com.example.moduletwo");
        }

        @Test
        @DisplayName("当 @UltiToolsModule 的 scanBasePackages 与类自身包名一致时（12 个自研模块的真实形状），结果不变")
        void shouldMatchTodaysResultForInHouseModuleShape() {
            UltiToolsPlugin mockPlugin = mock(MockPluginMatchingInHouseModuleShape.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.ultikits.ultitools.utils");
        }

        @Test
        @DisplayName("ComponentScan 声明 basePackageClasses() 时应该返回这些类所在的包")
        void shouldReturnPackagesOfBasePackageClasses() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithComponentScanBasePackageClasses.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.ultikits.ultitools.annotations");
        }
    }

    @Nested
    @DisplayName("注解检测逻辑测试")
    class AnnotationDetectionTests {

        @Test
        @DisplayName("ComponentScan注解应该可被检测")
        void componentScanAnnotationShouldBeDetectable() {
            boolean hasAnnotation = MockPluginWithEmptyComponentScan.class.isAnnotationPresent(ComponentScan.class);
            assertThat(hasAnnotation).isTrue();
        }

        @Test
        @DisplayName("EnableAutoRegister注解应该可被检测")
        void enableAutoRegisterAnnotationShouldBeDetectable() {
            boolean hasAnnotation = MockPluginWithEmptyEnableAutoRegister.class.isAnnotationPresent(EnableAutoRegister.class);
            assertThat(hasAnnotation).isTrue();
        }

        @Test
        @DisplayName("没有注解的类应该被正确识别")
        void noAnnotationShouldBeDetectable() {
            boolean hasComponentScan = PlainMockPlugin.class.isAnnotationPresent(ComponentScan.class);
            boolean hasEnableAutoRegister = PlainMockPlugin.class.isAnnotationPresent(EnableAutoRegister.class);
            assertThat(hasComponentScan).isFalse();
            assertThat(hasEnableAutoRegister).isFalse();
        }
    }

    @Nested
    @DisplayName("ComponentScan注解值提取测试")
    class ComponentScanValueExtractionTests {

        @Test
        @DisplayName("应该能获取ComponentScan的value值")
        void shouldGetComponentScanValue() {
            ComponentScan annotation = MockPluginWithComponentScanValue.class.getAnnotation(ComponentScan.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).containsExactly("com.value.one", "com.value.two");
        }

        @Test
        @DisplayName("应该能获取ComponentScan的basePackages值")
        void shouldGetComponentScanBasePackages() {
            ComponentScan annotation = MockPluginWithComponentScanBasePackages.class.getAnnotation(ComponentScan.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.basePackages()).containsExactly("com.base.one", "com.base.two");
        }

        @Test
        @DisplayName("空ComponentScan注解应该返回空数组")
        void emptyComponentScanShouldReturnEmptyArrays() {
            ComponentScan annotation = MockPluginWithEmptyComponentScan.class.getAnnotation(ComponentScan.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEmpty();
            assertThat(annotation.basePackages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("EnableAutoRegister注解值提取测试")
    class EnableAutoRegisterValueExtractionTests {

        @Test
        @DisplayName("应该能获取EnableAutoRegister的scanPackage值")
        void shouldGetEnableAutoRegisterScanPackage() {
            EnableAutoRegister annotation = MockPluginWithEnableAutoRegister.class.getAnnotation(EnableAutoRegister.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.scanPackage()).isEqualTo("com.custom.scan.package");
        }

        @Test
        @DisplayName("空EnableAutoRegister注解应该返回空字符串")
        void emptyEnableAutoRegisterShouldReturnEmptyString() {
            EnableAutoRegister annotation = MockPluginWithEmptyEnableAutoRegister.class.getAnnotation(EnableAutoRegister.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.scanPackage()).isEmpty();
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryConditionTests {

        @Test
        @DisplayName("ComponentScan 只有一个包时应该返回单元素数组")
        void shouldReturnSingleElementArrayWhenComponentScanHasOnePackage() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithSinglePackage.class);
            
            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);
            
            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.single.package");
        }

        @Test
        @DisplayName("EnableAutoRegister 的 scanPackage 可以包含点号分隔的深层包名")
        void enableAutoRegisterScanPackageCanContainDots() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithDeeplyNestedPackage.class);
            
            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);
            
            assertThat(packages).hasSize(1);
            assertThat(packages[0]).isEqualTo("com.very.deeply.nested.package.structure");
        }

        @Test
        @DisplayName("应该能获取类的包名作为默认值")
        void shouldGetClassPackageNameAsDefault() {
            String packageName = PlainMockPlugin.class.getPackage().getName();
            assertThat(packageName).isEqualTo("com.ultikits.ultitools.utils");
        }
    }

    @Nested
    @DisplayName("复杂场景测试")
    class ComplexScenarioTests {

        @Test
        @DisplayName("当 ComponentScan 的 value 和 basePackages 都有值时，二者应该按声明顺序累加")
        void valueAndBasePackagesContributeAdditively() {
            UltiToolsPlugin mockPlugin = mock(MockPluginWithBothValueAndBasePackages.class);

            String[] packages = DependencyUtils.getPluginPackages(mockPlugin);

            // 累加式而非首个匹配优先：value 先贡献，basePackages 后贡献，重复项才会被折叠
            // （SILENT-22 / 04-08 Task 2，与 PluginManager.getPluginScanPackages 的形状一致）。
            assertThat(packages).hasSize(2);
            assertThat(packages).containsExactly("com.value.priority", "com.base.additional");
        }

        @Test
        @DisplayName("不同注解组合下的正确行为")
        void shouldHandleVariousAnnotationCombinations() {
            // 仅 EnableAutoRegister 带 scanPackage
            UltiToolsPlugin plugin1 = mock(MockPluginWithEnableAutoRegister.class);
            assertThat(DependencyUtils.getPluginPackages(plugin1))
                .containsExactly("com.custom.scan.package");

            // 仅空 ComponentScan
            UltiToolsPlugin plugin2 = mock(MockPluginWithEmptyComponentScan.class);
            assertThat(DependencyUtils.getPluginPackages(plugin2))
                .containsExactly("com.ultikits.ultitools.utils");

            // ComponentScan 带 basePackages
            UltiToolsPlugin plugin3 = mock(MockPluginWithComponentScanBasePackages.class);
            assertThat(DependencyUtils.getPluginPackages(plugin3))
                .containsExactly("com.base.one", "com.base.two");
        }
    }

    // ========== Mock 插件类定义 ==========
    // 使用 abstract 类继承 UltiToolsPlugin，避免需要调用父类构造函数
    // Mockito 可以 mock abstract 类

    /**
     * 没有任何注解的 Mock 插件
     */
    static abstract class PlainMockPlugin extends UltiToolsPlugin {
    }

    /**
     * 有 EnableAutoRegister 但 scanPackage 为空的 Mock 插件
     */
    @EnableAutoRegister
    static abstract class MockPluginWithEmptyEnableAutoRegister extends UltiToolsPlugin {
    }

    /**
     * 有 EnableAutoRegister 且 scanPackage 有值的 Mock 插件
     */
    @EnableAutoRegister(scanPackage = "com.custom.scan.package")
    static abstract class MockPluginWithEnableAutoRegister extends UltiToolsPlugin {
    }

    /**
     * 有 ComponentScan 且 value 有值的 Mock 插件
     */
    @ComponentScan(value = {"com.value.one", "com.value.two"})
    static abstract class MockPluginWithComponentScanValue extends UltiToolsPlugin {
    }

    /**
     * 有 ComponentScan 且 basePackages 有值的 Mock 插件
     */
    @ComponentScan(basePackages = {"com.base.one", "com.base.two"})
    static abstract class MockPluginWithComponentScanBasePackages extends UltiToolsPlugin {
    }

    /**
     * 有空 ComponentScan 的 Mock 插件
     */
    @ComponentScan
    static abstract class MockPluginWithEmptyComponentScan extends UltiToolsPlugin {
    }

    /**
     * 同时有 ComponentScan 和 EnableAutoRegister 的 Mock 插件
     * 二者应该按声明顺序累加，而不是互相排斥（SILENT-22 / 04-08 Task 2）
     */
    @ComponentScan(value = {"com.componentscan.priority"})
    @EnableAutoRegister(scanPackage = "com.enableautoregister.additional")
    static abstract class MockPluginWithBothAnnotations extends UltiToolsPlugin {
    }

    /**
     * 有多个包的 ComponentScan Mock 插件
     */
    @ComponentScan(value = {"com.pkg1", "com.pkg2", "com.pkg3", "com.pkg4"})
    static abstract class MockPluginWithMultiplePackages extends UltiToolsPlugin {
    }

    /**
     * 有深层嵌套包名的 EnableAutoRegister Mock 插件
     */
    @EnableAutoRegister(scanPackage = "com.very.deeply.nested.package.structure")
    static abstract class MockPluginWithDeeplyNestedPackage extends UltiToolsPlugin {
    }

    /**
     * 只有一个包的 ComponentScan Mock 插件
     */
    @ComponentScan(value = {"com.single.package"})
    static abstract class MockPluginWithSinglePackage extends UltiToolsPlugin {
    }

    /**
     * 同时有 value 和 basePackages 的 ComponentScan Mock 插件
     * 二者应该按声明顺序累加（SILENT-22 / 04-08 Task 2）
     */
    @ComponentScan(value = {"com.value.priority"}, basePackages = {"com.base.additional"})
    static abstract class MockPluginWithBothValueAndBasePackages extends UltiToolsPlugin {
    }

    /**
     * 只标注了 {@code @UltiToolsModule} 的 Mock 插件，声明单个 scanBasePackages ——
     * 覆盖当前 12 个自研模块共有的实际形状：declaration 中的包名与类自身的包名不同
     * （SILENT-22）。
     */
    @UltiToolsModule(scanBasePackages = {"com.example.moduleonly"})
    static abstract class MockPluginWithUltiToolsModuleScanBasePackages extends UltiToolsPlugin {
    }

    /**
     * 只标注了 {@code @UltiToolsModule} 的 Mock 插件，声明两个 scanBasePackages。
     */
    @UltiToolsModule(scanBasePackages = {"com.example.moduleone", "com.example.moduletwo"})
    static abstract class MockPluginWithUltiToolsModuleTwoScanBasePackages extends UltiToolsPlugin {
    }

    /**
     * 只标注了 {@code @UltiToolsModule} 的 Mock 插件，其单个 scanBasePackages 声明与类自身的包名
     * 完全一致——这正是当前 12 个自研模块的真实形状：修复前后必须产出同一个单元素结果。
     */
    @UltiToolsModule(scanBasePackages = {"com.ultikits.ultitools.utils"})
    static abstract class MockPluginMatchingInHouseModuleShape extends UltiToolsPlugin {
    }

    /**
     * 声明 {@code basePackageClasses()} 的 ComponentScan Mock 插件。
     */
    @ComponentScan(basePackageClasses = {UltiToolsModule.class})
    static abstract class MockPluginWithComponentScanBasePackageClasses extends UltiToolsPlugin {
    }
}
