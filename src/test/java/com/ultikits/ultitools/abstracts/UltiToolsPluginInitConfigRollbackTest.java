package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.testfixtures.configstrandedmulti.pkga.ValidMultiPackageConfigEntity;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Gate-1 CR-01 regression (plan 16-14, {@code 16-REVIEW-config.md}): #358 Part 1's
 * stranded-entity fix must be scoped to a plugin's WHOLE {@code initConfig()} run, across every
 * scan package {@code DependencyUtils.getPluginPackages} returns - not to a single {@code
 * registerAll()} call. Uses a REAL {@link ConfigManager} (not mocked) so the actual
 * snapshot/rollback machinery runs, driven through {@code initConfig()} exactly the way a real
 * module load does.
 */
@DisplayName("UltiToolsPlugin.initConfig 跨扫描包的注册表回滚 (CR-01, #358 Part 1)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of the private initConfig
class UltiToolsPluginInitConfigRollbackTest {

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin mockPlugin;

    /**
     * Two SIBLING packages - neither is a subpackage of the other, so #362's de-duplication
     * leaves both in {@code DependencyUtils.getPluginPackages}'s result, and {@code
     * initConfig()}'s loop calls {@code registerAll} once per package, exactly the shape CR-01
     * describes.
     */
    @UltiToolsModule(scanBasePackages = {
            "com.ultikits.testfixtures.configstrandedmulti.pkga",
            "com.ultikits.testfixtures.configstrandedmulti.pkgb"
    })
    abstract static class MultiPackageModuleFixture extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() {
        ValidMultiPackageConfigEntity.CONSTRUCTION_COUNT.set(0);
        configManager = new ConfigManager();
        TestHelper.mockUltiToolsInstance(ultiTools ->
                when(ultiTools.getConfigManager()).thenReturn(configManager));

        mockPlugin = mock(MultiPackageModuleFixture.class);
        lenient().when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        ConfigFileStubs.stubConfigFolder(mockPlugin, tempDir);
    }

    private void invokeInitConfig(UltiToolsPlugin plugin) throws Exception {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("initConfig");
        method.setAccessible(true);
        method.invoke(plugin);
    }

    @Test
    @DisplayName("第二个扫描包的类校验失败时，第一个扫描包已注册成功的类不应该遗留")
    void secondPackageRefusalRollsBackFirstPackagesEntryToo() {
        assertThatThrownBy(() -> invokeInitConfig(mockPlugin))
                .hasRootCauseInstanceOf(ConfigurationException.class);

        Map<String, com.ultikits.ultitools.abstracts.AbstractConfigEntity> configs =
                configManager.getAllConfigEntities(mockPlugin);
        assertThat(configs == null || configs.isEmpty())
                .as("pkga's entry (registered by the first registerAll() call in the loop) must "
                        + "not survive pkgb's refusal in the second call - the rollback must span "
                        + "the whole initConfig() loop, not just the call that failed")
                .isTrue();

        // ensureConstructable()'s own throwaway instance means one *successful* registration of
        // this class constructs it twice, not once - the counter's job here is only to prove the
        // class WAS constructed (the scan reached it) before the rollback removed its entry, not
        // to assert an exact framework-internal count.
        assertThat(ValidMultiPackageConfigEntity.CONSTRUCTION_COUNT.get())
                .as("pkga's class must actually have been constructed by the first registerAll() "
                        + "call for this test to prove anything about rolling back real work")
                .isGreaterThan(0);
    }
}
