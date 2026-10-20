package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Pins D-16: a module whose plugin.yml carries no {@code name:} key is refused at load, rather
 * than silently collapsing into the shared {@code "unknown"} identity that used to route its data
 * into {@code sqliteDB/unknown.db} alongside every other name-less module.
 * <p>
 * This fixture class has no {@code plugin.yml} resource of its own on the test classpath at all
 * (a {@code getInputStream()} lookup for one against a directory-based CodeSource, as Surefire
 * runs test classes from {@code target/test-classes/}, fails the same way "no name: key" does),
 * which is exactly the scenario this refusal exists to catch: a module JAR that cannot supply a
 * name.
 */
@DisplayName("UltiToolsPlugin name: 缺失时拒绝加载测试 (D-16)")
class UltiToolsPluginNameFallbackTest {

    @BeforeEach
    void setUp() {
        // getLogger() is exercised on the loadPluginConfiguration() IOException path this test
        // deliberately hits (see class javadoc), so it needs a real java.util.logging.Logger, not
        // Mockito's default null return.
        TestHelper.mockUltiToolsInstance(
                ultiTools -> when(ultiTools.getLogger()).thenReturn(Logger.getLogger("test")));
    }

    @Test
    @DisplayName("plugin.yml 缺少 name: 时构造函数应该抛出 PluginModuleException")
    void shouldRefuseToLoadWhenNameKeyMissing() {
        assertThatThrownBy(FixturePlugin::new)
                .isInstanceOf(PluginModuleException.class)
                .hasMessageContaining("name");
    }

    /** Minimal concrete UltiToolsPlugin subclass - only registerSelf() is abstract. */
    static class FixturePlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }
}
