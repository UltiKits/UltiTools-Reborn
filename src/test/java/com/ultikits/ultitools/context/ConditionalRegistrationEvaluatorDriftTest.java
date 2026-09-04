package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Issue #392 / D-01: {@code @ConditionalOnConfig} is evaluated once at component-scan time, so
 * {@code ul reload} re-reads the config file but never re-evaluates the condition. Per the locked
 * decision, the framework does not rebuild or re-register anything on reload -- it records the
 * scan-time decision and reports drift as a {@code Level.WARNING} when {@link
 * UltiToolsPlugin#reloadSelf()} is called, so an operator sees the flip instead of silence.
 * <p>
 * {@link #reloadAfterFlipToDisabledReportsDriftOnce()} proves the end-to-end path first (scan
 * -&gt; config flip -&gt; reload -&gt; warning), following the {@code doCallRealMethod()} idiom
 * used by {@code UltiToolsPluginLanguageFallbackTest}. The remaining tests exercise
 * {@link ConditionalRegistrationEvaluator#reportDrift(UltiToolsPlugin)} directly, which the
 * tracer test already proved is reachable from {@code reloadSelf()}.
 *
 * @since 6.3.0
 */
@DisplayName("ConditionalRegistrationEvaluator drift report (#392, D-01)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective field set mirrors the proven idiom
class ConditionalRegistrationEvaluatorDriftTest {

    @TempDir
    File tempDir;

    private ConfigManager mockConfigManager;
    private final List<LogRecord> captured = new ArrayList<>();
    private Handler captureHandler;
    private final List<UltiToolsPlugin> registeredPlugins = new ArrayList<>();
    private final List<SimpleContainer> containers = new ArrayList<>();

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @ConditionalOnConfig(value = "config/config.yml", path = "enableFeatureA")
    static class FeatureAComponent {
    }

    @ConditionalOnConfig(value = "config/config.yml", path = "enableFeatureB", negate = true)
    static class FeatureBComponent {
    }

    static class PlainComponent {
    }

    @BeforeEach
    void setUp() {
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(mockConfigManager));

        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
                // Nothing to flush: publish() appends straight to the in-memory list.
            }

            @Override
            public void close() {
                // Nothing to release: the captured records outlive this handler on purpose, so
                // a test can still read them after tearDown() detaches it.
            }
        };
        Logger.getLogger(ConditionalRegistrationEvaluator.class.getName()).addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(ConditionalRegistrationEvaluator.class.getName()).removeHandler(captureHandler);
        // The record is a static map -- release everything this test registered so it cannot
        // leak into a later test in this class (Task 2, "lifecycle release" coverage note).
        for (UltiToolsPlugin plugin : registeredPlugins) {
            ConditionalRegistrationEvaluator.clear(plugin);
        }
        for (SimpleContainer container : containers) {
            container.close();
        }
    }

    private void writeYaml(String relativePath, String content) throws IOException {
        File configFile = new File(tempDir, relativePath);
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }
    }

    private void writeLangFile(String code, String jsonContent) throws IOException {
        File langDir = new File(tempDir, "lang");
        langDir.mkdirs();
        Files.write(new File(langDir, code + ".json").toPath(), jsonContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A plain {@code mock(UltiToolsPlugin.class)} with {@code getResourceFolderPath()} stubbed to
     * {@link #tempDir}, tracked for release in {@link #tearDown()}.
     */
    private UltiToolsPlugin mockPlugin() {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        registeredPlugins.add(plugin);
        return plugin;
    }

    /**
     * A {@link SimpleContainer} with {@code plugin} registered as the resolvable
     * {@link UltiToolsPlugin} bean, tracked for {@code close()} in {@link #tearDown()}.
     * <p>
     * Also stubs {@code plugin.getContext()} to return this same container (D-03) -- this is
     * the container {@code reportDrift} reads at claim time via {@code plugin.getContext()},
     * mirroring production's {@code UltiToolsPlugin#setContext(SimpleContainer)} wiring. A test
     * that wants a fixture reported "present" must register it into the returned container
     * (e.g. via {@code registerSingleton}); a test that leaves it unregistered gets "absent" by
     * construction, no separate stub needed.
     */
    private SimpleContainer containerFor(UltiToolsPlugin plugin) {
        SimpleContainer container = new SimpleContainer();
        container.registerType(UltiToolsPlugin.class, plugin);
        when(plugin.getContext()).thenReturn(container);
        containers.add(container);
        return container;
    }

    /**
     * A {@link SimpleContainer} with no {@link UltiToolsPlugin} bean at all -- mirrors the
     * framework's own core context, which never resolves a plugin (Test 6).
     */
    private SimpleContainer containerWithNoPlugin() {
        SimpleContainer container = new SimpleContainer();
        containers.add(container);
        return container;
    }

    private List<LogRecord> warningsCaptured() {
        List<LogRecord> warnings = new ArrayList<>();
        for (LogRecord record : captured) {
            // Level.equals compares intValue(), so this also matches a custom Level
            // registered at WARNING severity -- which is what "logged as a warning" means.
            if (Level.WARNING.equals(record.getLevel())) {
                warnings.add(record);
            }
        }
        return warnings;
    }

    @Test
    @DisplayName("scan enabled -> reload disabled emits exactly one WARNING naming class/file/key/direction/restart")
    void reloadAfterFlipToDisabledReportsDriftOnce() throws Exception {
        // 1. Scan-time evaluation: enableFeatureA is true, component is registered.
        writeYaml("config/config.yml", "enableFeatureA: true\n");
        writeLangFile("en", "{\"greeting\":\"Hi\"}");

        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        registeredPlugins.add(plugin);
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(plugin.getLanguageCode()).thenReturn("en");
        when(plugin.supported()).thenReturn(Collections.singletonList("en"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        // reloadSelf() reads the private field directly, not the getter -- mirror the proven
        // idiom from UltiToolsPluginLanguageFallbackTest.
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, tempDir.getAbsolutePath());

        SimpleContainer container = containerFor(plugin);

        boolean registeredAtScanTime = ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container);
        assertThat(registeredAtScanTime)
                .as("scan-time decision with enableFeatureA: true must be register")
                .isTrue();

        // 2. Flip the key on disk -- exactly what an operator does before `ul reload`.
        writeYaml("config/config.yml", "enableFeatureA: false\n");

        // 3. Reload -- must call the real reloadSelf() body, including the new drift report.
        doCallRealMethod().when(plugin).reloadSelf();
        plugin.reloadSelf();

        // 4. Exactly one WARNING, naming the class, the config file, the key, the direction, and
        // the restart requirement.
        List<LogRecord> warnings = warningsCaptured();
        assertThat(warnings)
                .as("reloadSelf() must emit exactly one @ConditionalOnConfig drift WARNING after "
                        + "enableFeatureA flipped true -> false; an empty list means "
                        + "ConditionalRegistrationEvaluator.reportDrift is no longer wired into "
                        + "UltiToolsPlugin.reloadSelf()")
                .hasSize(1);
        String message = warnings.get(0).getMessage();
        assertThat(message).contains(FeatureAComponent.class.getName());
        assertThat(message).contains("config/config.yml");
        assertThat(message).contains("enableFeatureA");
        assertThat(message).contains("disabled");
        assertThat(message).contains("restart");
    }

    @Test
    @DisplayName("scan disabled -> reload enabled reports the reverse direction, mirror wording")
    void reloadAfterFlipToEnabledReportsDrift() throws Exception {
        writeYaml("config/config.yml", "enableFeatureA: false\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);

        boolean registeredAtScanTime = ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container);
        assertThat(registeredAtScanTime)
                .as("scan-time decision with enableFeatureA: false must be skip")
                .isFalse();

        writeYaml("config/config.yml", "enableFeatureA: true\n");

        List<String> messages = ConditionalRegistrationEvaluator.reportDrift(plugin);
        assertThat(messages)
                .as("the disabled -> enabled direction must report too; #392 measured BOTH "
                        + "directions inert, so covering only one would leave half the defect")
                .hasSize(1);
        String message = messages.get(0);
        assertThat(message).contains(FeatureAComponent.class.getName());
        assertThat(message).contains("config/config.yml");
        assertThat(message).contains("enableFeatureA");
        assertThat(message).contains("enabled");
        assertThat(message).contains("was not registered");
        assertThat(message).contains("restart");

        assertThat(warningsCaptured())
                .as("the drift message must reach the log, not only the return value")
                .hasSize(1);
    }

    @Test
    @DisplayName("no config change across a reload emits no drift and returns an empty list")
    void noDriftEmitsNothing() throws Exception {
        writeYaml("config/config.yml", "enableFeatureA: true\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);

        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container))
                .as("scan-time decision with enableFeatureA: true")
                .isTrue();

        // No change to the file before reporting.
        List<String> messages = ConditionalRegistrationEvaluator.reportDrift(plugin);
        assertThat(messages)
                .as("an unchanged config must produce no drift report; a report here means "
                        + "reportDrift compares against something other than the recorded decision")
                .isEmpty();
        assertThat(warningsCaptured())
                .as("an unchanged config must not log a drift WARNING")
                .isEmpty();
    }

    @Test
    @DisplayName("negate=true reports the registration decision, not the raw YAML boolean")
    void negateReportsRegistrationDecisionNotRawBoolean() throws Exception {
        // negate=true + raw false -> registered.
        writeYaml("config/config.yml", "enableFeatureB: false\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);

        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureBComponent.class, container))
                .as("negate=true with raw false must register")
                .isTrue();
        // The scan-time decision was "register" -- simulate ComponentScanner having gone on to
        // actually construct the singleton, so the D-03 container query (Task 1) has a real
        // instance to observe when reportDrift is called below (rather than a coincidental
        // string match that a fixture with no singleton would produce).
        container.registerSingleton("featureBComponent", new FeatureBComponent());

        // negate=true + raw true -> skipped; the message must say "disabled"/"already
        // registered" (the registration decision), never the raw YAML boolean.
        writeYaml("config/config.yml", "enableFeatureB: true\n");

        List<String> messages = ConditionalRegistrationEvaluator.reportDrift(plugin);
        assertThat(messages)
                .as("negate=true must still report drift when the registration decision flips")
                .hasSize(1);
        String message = messages.get(0);
        assertThat(message).contains(FeatureBComponent.class.getName());
        assertThat(message).contains("config/config.yml");
        assertThat(message).contains("enableFeatureB");
        assertThat(message).contains("disabled");
        assertThat(message).contains("already registered");
    }

    @Test
    @DisplayName("D-03: an absent instance is never reported as registered, even when the condition now enables it")
    void absentInstanceIsNeverReportedAsRegistered() throws Exception {
        // Recorded false at scan time; no singleton and no bean definition are ever registered
        // for FeatureAComponent in `container` -- the query must read that absence, not assume
        // presence from the fact that the condition now says "register".
        writeYaml("config/config.yml", "enableFeatureA: false\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);

        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container))
                .as("scan-time decision with enableFeatureA: false must be skip")
                .isFalse();

        writeYaml("config/config.yml", "enableFeatureA: true\n");

        List<String> messages = ConditionalRegistrationEvaluator.reportDrift(plugin);
        assertThat(messages).hasSize(1);
        String message = messages.get(0);
        assertThat(message)
                .as("no singleton or definition is registered, so the message must not claim presence")
                .contains("was not registered at startup")
                .doesNotContain("already registered");
    }

    @Test
    @DisplayName("D-03: a present instance is reported as registered when the condition now disables it")
    void presentInstanceIsReportedAsRegistered() throws Exception {
        // Mirror of the above: recorded true at scan time, and a real singleton IS registered
        // in `container` this time -- the query must read that presence too.
        writeYaml("config/config.yml", "enableFeatureA: true\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);
        container.registerSingleton("featureAComponent", new FeatureAComponent());

        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container))
                .as("scan-time decision with enableFeatureA: true must be register")
                .isTrue();

        writeYaml("config/config.yml", "enableFeatureA: false\n");

        List<String> messages = ConditionalRegistrationEvaluator.reportDrift(plugin);
        assertThat(messages).hasSize(1);
        String message = messages.get(0);
        assertThat(message)
                .as("a real singleton is registered, so the message must claim presence")
                .contains("already registered")
                .doesNotContain("was not registered at startup");
    }

    @Test
    @DisplayName("D-03 control: the same container answers absent for one fixture and present for another in the same run")
    void containerQueryDistinguishesPresentFromAbsentInSameRun() throws Exception {
        // A negative result ("absent") is only trustworthy if paired with a positive control
        // proving the query itself works -- both fixtures are read from the SAME container in
        // this single test run.
        writeYaml("config/config.yml", "enableFeatureA: false\nenableFeatureB: true\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);
        // FeatureBComponent (negate=true) is registered as a real singleton; FeatureAComponent
        // is deliberately left unregistered.
        container.registerSingleton("featureBComponent", new FeatureBComponent());

        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container))
                .as("scan-time decision with enableFeatureA: false must be skip")
                .isFalse();
        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureBComponent.class, container))
                .as("negate=true with raw enableFeatureB: true must be skip")
                .isFalse();

        writeYaml("config/config.yml", "enableFeatureA: true\nenableFeatureB: false\n");

        List<String> messages = ConditionalRegistrationEvaluator.reportDrift(plugin);
        assertThat(messages).hasSize(2);
        String aMessage = messages.stream()
                .filter(m -> m.contains(FeatureAComponent.class.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no drift message for FeatureAComponent"));
        String bMessage = messages.stream()
                .filter(m -> m.contains(FeatureBComponent.class.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no drift message for FeatureBComponent"));
        assertThat(aMessage)
                .as("FeatureAComponent is unregistered in this container -- absent")
                .contains("was not registered at startup");
        assertThat(bMessage)
                .as("FeatureBComponent is a real singleton in this same container -- present")
                .contains("already registered");
    }

    @Test
    @DisplayName("clear(plugin) releases the record so a subsequent reportDrift stays empty despite drift")
    void clearReleasesRecordedDecisions() throws Exception {
        writeYaml("config/config.yml", "enableFeatureA: true\n");
        UltiToolsPlugin plugin = mockPlugin();
        SimpleContainer container = containerFor(plugin);

        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container))
                .as("scan-time decision, recorded so clear() has something to release")
                .isTrue();

        writeYaml("config/config.yml", "enableFeatureA: false\n");

        ConditionalRegistrationEvaluator.clear(plugin);

        assertThat(ConditionalRegistrationEvaluator.reportDrift(plugin))
                .as("clear(plugin) must release the record; a report here means PluginManager."
                        + "unregister would leave the module ClassLoader pinned after unload")
                .isEmpty();
        assertThat(warningsCaptured())
                .as("a released record must not log either")
                .isEmpty();
    }

    @Test
    @DisplayName("no UltiToolsPlugin in the container fail-opens and records nothing; unconditional classes record nothing")
    void coreContextAndUnconditionalClassesRecordNothing() throws Exception {
        // (a) A container with no UltiToolsPlugin bean at all -- mirrors the framework's own
        // core context. Fail-open (true), and nothing is recorded against any plugin.
        SimpleContainer emptyContainer = containerWithNoPlugin();
        assertThat(ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, emptyContainer))
                .as("a container with no UltiToolsPlugin bean must fail open (D-20)")
                .isTrue();

        UltiToolsPlugin plugin = mockPlugin();
        assertThat(ConditionalRegistrationEvaluator.reportDrift(plugin))
                .as("the core-context fail-open path must record nothing against any plugin; a "
                        + "non-empty list here means the record is keyed on something other than "
                        + "a resolved UltiToolsPlugin")
                .isEmpty();

        // (b) A class with no @ConditionalOnConfig at all is also a no-op, even against a real,
        // resolvable plugin.
        SimpleContainer container = containerFor(plugin);
        assertThat(ConditionalRegistrationEvaluator.shouldRegister(PlainComponent.class, container))
                .as("an unannotated class always registers")
                .isTrue();
        assertThat(ConditionalRegistrationEvaluator.reportDrift(plugin))
                .as("a class carrying no @ConditionalOnConfig must record nothing, so it can never drift")
                .isEmpty();
    }
}
