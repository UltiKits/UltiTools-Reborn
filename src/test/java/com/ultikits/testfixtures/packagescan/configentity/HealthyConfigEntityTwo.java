package com.ultikits.testfixtures.packagescan.configentity;

import com.ultikits.ultitools.annotations.ConfigEntity;

/**
 * A second healthy {@code @ConfigEntity}-annotated fixture class, ensuring the survivor assertion
 * in {@code PackageScanUtilsTest} is not satisfied by a single-survivor coincidence.
 * <br>
 * 第二个健康的 {@code @ConfigEntity} fixture 类，确保 {@code PackageScanUtilsTest}
 * 的幸存者断言不会因为"恰好只有一个幸存者"而巧合成立。
 */
@ConfigEntity("config/healthy-two.yml")
public class HealthyConfigEntityTwo {
}
