package com.ultikits.testfixtures.packagescan.configentity;

import com.ultikits.ultitools.annotations.ConfigEntity;

/**
 * A healthy {@code @ConfigEntity}-annotated fixture class in the same package as
 * {@link BreakingConfigEntity} - {@code PackageScanUtilsTest} asserts this class is still
 * discovered even though its sibling fails to load.
 * <br>
 * 与 {@link BreakingConfigEntity} 同包的健康 {@code @ConfigEntity} fixture 类——
 * {@code PackageScanUtilsTest} 断言即便其同包类加载失败，本类仍会被发现。
 */
@ConfigEntity("config/healthy-one.yml")
public class HealthyConfigEntityOne {
}
