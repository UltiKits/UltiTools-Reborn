/**
 * Test-only fixtures for {@code PackageScanUtilsTest}'s per-class skip-and-continue coverage
 * (07-21, Task 1). {@link com.ultikits.testfixtures.packagescan.BreakableSuperclass} sits outside
 * the {@code configentity} sub-package deliberately, so a test class loader can block resolution of
 * it specifically without also touching any class actually enumerated by
 * {@code PackageScanUtils.scanAnnotatedClasses("com.ultikits.testfixtures.packagescan.configentity", ...)}.
 * <p>
 * 本包为 {@code PackageScanUtilsTest} 的逐类跳过并继续覆盖（07-21，任务 1）提供测试专用
 * fixture。{@link com.ultikits.testfixtures.packagescan.BreakableSuperclass}
 * 刻意放在 {@code configentity} 子包之外，这样测试类加载器可以专门阻断对它的解析，
 * 而不会影响 {@code PackageScanUtils.scanAnnotatedClasses} 实际枚举到的任何类。
 */
package com.ultikits.testfixtures.packagescan;
