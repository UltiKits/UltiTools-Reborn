package com.ultikits.ultitools.testing;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("UltiToolsTestHelper Tests")
class UltiToolsTestHelperTest {

    @AfterEach
    void tearDown() {
        UltiToolsTestHelper.cleanup();
    }

    @Nested
    @DisplayName("mockPlugin")
    class MockPluginTests {

        @Test
        @DisplayName("returns a mock plugin with stubbed logger and i18n")
        void returnsMockPlugin() {
            UltiToolsPlugin plugin = UltiToolsTestHelper.mockPlugin();
            assertThat(plugin).isNotNull();
            assertThat(plugin.getLogger()).isInstanceOf(PluginLogger.class);
            assertThat(plugin.i18n("hello")).isEqualTo("hello");
        }

        @Test
        @DisplayName("i18n returns key as-is for any string")
        void i18nPassthrough() {
            UltiToolsPlugin plugin = UltiToolsTestHelper.mockPlugin();
            assertThat(plugin.i18n("test_key")).isEqualTo("test_key");
            assertThat(plugin.i18n("another.key")).isEqualTo("another.key");
        }

        @Test
        @DisplayName("multiple calls return independent mocks")
        void independentMocks() {
            UltiToolsPlugin plugin1 = UltiToolsTestHelper.mockPlugin();
            UltiToolsPlugin plugin2 = UltiToolsTestHelper.mockPlugin();
            assertThat(plugin1).isNotSameAs(plugin2);
        }
    }

    @Nested
    @DisplayName("mockBukkitServer")
    class MockBukkitServerTests {

        @Test
        @DisplayName("installs mock server accessible via Bukkit.getServer()")
        void installsMockServer() {
            Server server = UltiToolsTestHelper.mockBukkitServer();
            assertThat(server).isNotNull();
            assertThat(Bukkit.getServer()).isSameAs(server);
        }

        @Test
        @DisplayName("server has logger, scheduler, and plugin manager")
        void serverHasBasicStubs() {
            Server server = UltiToolsTestHelper.mockBukkitServer();
            assertThat(server.getLogger()).isNotNull();
            assertThat(server.getScheduler()).isNotNull();
            assertThat(server.getPluginManager()).isNotNull();
        }

        @Test
        @DisplayName("getMockServer returns the installed server")
        void getMockServerReturns() {
            Server server = UltiToolsTestHelper.mockBukkitServer();
            assertThat(UltiToolsTestHelper.getMockServer()).isSameAs(server);
        }
    }

    @Nested
    @DisplayName("cleanup")
    class CleanupTests {

        @Test
        @DisplayName("resets Bukkit.server to null")
        void resetsBukkitServer() {
            UltiToolsTestHelper.mockBukkitServer();
            assertThat(Bukkit.getServer()).isNotNull();

            UltiToolsTestHelper.cleanup();
            Server server = UltiToolsTestHelper.getStaticField(Bukkit.class, "server");
            assertThat(server).isNull();
        }

        @Test
        @DisplayName("does not throw when called without prior setup")
        void idempotent() {
            assertThatCode(UltiToolsTestHelper::cleanup).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("setField / getField")
    class ReflectionTests {

        @Test
        @DisplayName("setField and getField work on private fields")
        void setAndGetField() {
            TestTarget target = new TestTarget();
            UltiToolsTestHelper.setField(target, "secret", "newValue");
            String value = UltiToolsTestHelper.getField(target, "secret");
            assertThat(value).isEqualTo("newValue");
        }

        @Test
        @DisplayName("setField throws for non-existent field")
        void setFieldThrowsForMissing() {
            TestTarget target = new TestTarget();
            assertThatThrownBy(() -> UltiToolsTestHelper.setField(target, "nonExistent", "value"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nonExistent");
        }

        @Test
        @DisplayName("getField throws for non-existent field")
        void getFieldThrowsForMissing() {
            TestTarget target = new TestTarget();
            assertThatThrownBy(() -> UltiToolsTestHelper.getField(target, "nonExistent"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nonExistent");
        }

        @Test
        @DisplayName("findField searches superclass hierarchy")
        void findFieldInSuperclass() {
            ChildTarget child = new ChildTarget();
            UltiToolsTestHelper.setField(child, "secret", "fromChild");
            String value = UltiToolsTestHelper.getField(child, "secret");
            assertThat(value).isEqualTo("fromChild");
        }
    }

    @Nested
    @DisplayName("setStaticField / getStaticField")
    class StaticReflectionTests {

        @Test
        @DisplayName("setStaticField and getStaticField work on static fields")
        void setAndGetStaticField() {
            UltiToolsTestHelper.setStaticField(StaticTarget.class, "value", "test123");
            String result = UltiToolsTestHelper.getStaticField(StaticTarget.class, "value");
            assertThat(result).isEqualTo("test123");
        }

        @Test
        @DisplayName("setStaticField throws for non-existent field")
        void throwsForMissing() {
            assertThatThrownBy(() -> UltiToolsTestHelper.setStaticField(StaticTarget.class, "nope", "x"))
                .isInstanceOf(RuntimeException.class);
        }
    }

    // Test helper classes
    private static class TestTarget {
        private String secret = "original";
    }

    private static class ChildTarget extends TestTarget {
        private int childField = 42;
    }

    private static class StaticTarget {
        private static String value = "default";
    }
}
