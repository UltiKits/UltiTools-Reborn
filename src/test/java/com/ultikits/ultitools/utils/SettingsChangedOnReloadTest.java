package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.stub.UltiCloudStub;

/**
 * Plan 22-22 Task 3 (S-53, contract {@code handshake.ts}, SEMANTICS 20): {@code /ul reload} reports a changed
 * {@code ultipanel.capabilities.power-actions} once, as {@code settings.changed}, on the open connection; an unchanged
 * value is not repeated; a reload while offline sends nothing and the next hello carries the new value. The reload is
 * driven through the real {@code UltiTools#reloadPluginsAndReport()}.
 */
@DisplayName("settings.changed on /ul reload")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test re-publishes the UltiTools singleton and its fields
class SettingsChangedOnReloadTest {

    static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final String CREDENTIAL = "ucs_TESTSECRET";

    @TempDir
    Path temp;

    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private UltiTools plugin;
    private final YamlConfiguration config = new YamlConfiguration();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Path state = temp.resolve("server").resolve(".ultikits");
        Files.createDirectories(state);
        final File dataFolder = Files.createDirectories(temp.resolve("server").resolve("plugins").resolve("UltiTools"))
                .toFile();
        final Logger logger = Logger.getLogger("SettingsChangedOnReloadTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { }
            @Override public void flush() { }
            @Override public void close() { }
        });
        config.set("language", "en");
        config.set(Capability.POWER_ACTIONS.getConfigPath(), false);
        plugin = TestHelper.mockUltiToolsInstance(tools -> {
            lenient().when(tools.getLogger()).thenReturn(logger);
            lenient().when(tools.getConfig()).thenReturn(config);
            lenient().when(tools.getDataFolder()).thenReturn(dataFolder);
            lenient().when(tools.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
            try {
                doCallRealMethod().when(tools).reloadPluginsAndReport();
            } catch (java.io.IOException impossible) {
                throw new AssertionError(impossible);
            }
        });
        PluginManager modules = mock(PluginManager.class);
        when(modules.reloadAllAndReport()).thenReturn(Collections.<String>emptyList());
        setField(plugin, "pluginManager", modules);
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(CREDENTIAL, SERVER_ID));
        CloudSession.setAgentEndpointOverrideForTesting(new URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
        features = CloudFeatures.start(CloudFeatures.Bindings.forTesting());
        setField(plugin, "cloudFeatures", features);
    }

    @AfterEach
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        features.shutdown();
        stub.close();
        CloudTestAccess.reset();
        MockBukkitHelper.safeUnmock();
        setField(null, "ultiTools", null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = UltiTools.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void connect() throws Exception {
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
    }

    private boolean helloPower(int index) {
        return stub.gateway().received("hello").get(index).getAsJsonObject("payload").getAsJsonObject("settings")
                .get("powerActionsEnabled").getAsBoolean();
    }

    @Test
    @DisplayName("connected: a reload that turns power actions on sends exactly one settings.changed; an unchanged one none")
    void changedReloadSendsOneSettingsChanged() throws Exception {
        connect();
        assertThat(helloPower(0)).isFalse();

        config.set(Capability.POWER_ACTIONS.getConfigPath(), true);
        plugin.reloadPluginsAndReport();
        await(() -> !stub.gateway().received("settings.changed").isEmpty(), "settings.changed");
        JsonObject payload = stub.gateway().received("settings.changed").get(0).getAsJsonObject("payload");
        assertThat(payload.toString()).isEqualTo("{\"powerActionsEnabled\":true}");

        plugin.reloadPluginsAndReport();
        Thread.sleep(1000L);
        assertThat(stub.gateway().received("settings.changed")).as("unchanged: nothing repeated").hasSize(1);
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("a reload with power actions unchanged sends nothing")
    void unchangedReloadSendsNothing() throws Exception {
        connect();
        plugin.reloadPluginsAndReport();
        Thread.sleep(1000L);
        assertThat(stub.gateway().received("settings.changed")).isEmpty();
    }

    @Test
    @DisplayName("offline: a reload sends nothing, and the next hello carries the new value without a settings.changed")
    void offlineReloadIsCarriedByTheNextHello() throws Exception {
        config.set(Capability.POWER_ACTIONS.getConfigPath(), true);
        plugin.reloadPluginsAndReport();
        assertThat(stub.gateway().received(null)).as("nothing sent while offline").isEmpty();

        connect();
        assertThat(helloPower(0)).isTrue();
        Thread.sleep(1000L);
        assertThat(stub.gateway().received("settings.changed")).isEmpty();
    }

    @Test
    @DisplayName("power actions changed after the hello was built and before the welcome: one settings.changed")
    void changeBetweenHelloAndWelcomeIsReportedAtTheWelcome() throws Exception {
        features.shutdown();
        CloudFeatures.Bindings bindings = CloudFeatures.Bindings.forTesting();
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        // The hello reads the switch first (off); the operator turns it on before the welcome arrives.
        bindings.powerEnabled = () -> reads.getAndIncrement() > 0;
        features = CloudFeatures.start(bindings);
        setField(plugin, "cloudFeatures", features);
        connect();
        assertThat(helloPower(0)).isFalse();

        await(() -> !stub.gateway().received("settings.changed").isEmpty(), "settings.changed at the welcome");
        assertThat(stub.gateway().received("settings.changed").get(0).getAsJsonObject("payload").toString())
                .isEqualTo("{\"powerActionsEnabled\":true}");
        Thread.sleep(1000L);
        assertThat(stub.gateway().received("settings.changed")).hasSize(1);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}
