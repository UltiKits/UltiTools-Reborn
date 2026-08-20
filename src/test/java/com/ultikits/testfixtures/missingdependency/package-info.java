/**
 * Test-only fixtures for simulating a type that is absent from a class's defining class loader at
 * runtime - the shape a soft-dependency integration class (Vault, PlaceholderAPI, ...) has when the
 * optional dependency is not installed on the server.
 * <p>
 * {@link com.ultikits.testfixtures.missingdependency.HasMethodReferencingMissingType} references
 * {@link com.ultikits.testfixtures.missingdependency.MissingDependencyType} in a method signature.
 * Both classes compile and sit on the test classpath normally, so by themselves they are unremarkable.
 * The scenario only becomes real when a test loads
 * {@code HasMethodReferencingMissingType} through a class loader that deliberately refuses to
 * resolve {@code MissingDependencyType} - see
 * {@code com.ultikits.ultitools.context.FinalContractValidatorTest} for the loader that does this
 * and the {@link NoClassDefFoundError} it reproduces on
 * {@link Class#getDeclaredMethods()}.
 * <p>
 * 本包模拟一个类型在其定义类加载器眼中"不存在"的场景——这正是软依赖集成类（Vault、
 * PlaceholderAPI 等）在可选依赖未安装时的真实形态。两个 fixture 类本身编译正常、挂在测试类路径
 * 上毫无异常；只有当测试用一个刻意拒绝解析 {@code MissingDependencyType} 的类加载器去加载
 * {@code HasMethodReferencingMissingType} 时，场景才会真正复现。
 */
package com.ultikits.testfixtures.missingdependency;
