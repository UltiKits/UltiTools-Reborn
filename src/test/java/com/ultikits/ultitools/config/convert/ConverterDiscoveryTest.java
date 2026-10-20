package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import org.bukkit.configuration.file.YamlConfiguration;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.PackageScanUtils;
import com.ultikits.ultitools.utils.TestHelper;

class ConverterDiscoveryTest {
    private static final String FIRST = "fixture.first";
    private static final String SECOND = "fixture.second";
    private static UltiToolsPlugin constructingPlugin;
    private static boolean constructed;
    private static int manualCalls;
    private static boolean languageReady;

    @TempDir
    Path directory;
    private UltiToolsPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        TestHelper.mockUltiToolsInstance();
        plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("FixtureModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        constructingPlugin = plugin;
        constructed = false;
        manualCalls = 0;
        languageReady = false;
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            YamlConfiguration config = new YamlConfiguration();
            config.set("language", "en");
            lenient().when(ultiTools.getConfig()).thenReturn(config);
            lenient().when(ultiTools.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            lenient().when(ultiTools.getConfigManager()).thenReturn(new ConfigManager());
        });
    }

    @Test
    void allPackagesConvertersExistBeforeFirstEntityConstructor() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(ValidConverter.class, GoodConfig.class)) {
            ConfigManager manager = new ConfigManager();
            manager.registerAll(plugin, new String[]{FIRST, SECOND}, getClass().getClassLoader());
            assertThat(constructed).isTrue();
            assertThat(manager.getAllConfigEntities(plugin)).containsKey("config/good.yml");
            ConverterRegistry prepared = ConverterRegistry.forModule(plugin);
            manager.registerAll(plugin, new String[]{FIRST, SECOND}, getClass().getClassLoader());
            assertThat(ConverterRegistry.forModule(plugin)).isSameAs(prepared);
        }
    }

    @Test
    void missingTypeRefusesBeforeConstructorOrFileCreation() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, BadConfig.class)) {
            assertThatThrownBy(() -> new ConfigManager().registerAll(plugin,
                    new String[]{FIRST, SECOND}, getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("no config converter for")
                    .hasMessageContaining(Value.class.getName());
            assertThat(constructed).isFalse();
            assertThat(Files.exists(directory.resolve("config/bad.yml"))).isFalse();
            assertThat(ConverterRegistry.hasModule(plugin)).isTrue();
        }
    }

    @Test
    void converterRequiresPublicNoArgumentConstructorAndPreparationIsAtomic() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(PrivateConverter.class, null)) {
            assertThatThrownBy(() -> ConverterRegistry.prepareModule(plugin,
                    new String[]{FIRST, SECOND}, getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(PrivateConverter.class.getName())
                    .hasMessageContaining("public no-argument constructor");
            assertThat(ConverterRegistry.hasModule(plugin)).isFalse();
        }
    }

    @Test
    void duplicateExactTypeFailsNamingBothClassesWithoutPublishingRegistry() {
        try (MockedStatic<PackageScanUtils> scanner = mockStatic(PackageScanUtils.class)) {
            scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                    .thenAnswer(call -> call.getArgument(0) == ConfigConverterFor.class
                            ? new HashSet<>(java.util.Arrays.asList(ValidConverter.class, DuplicateConverter.class))
                            : Collections.emptySet());
            assertThatThrownBy(() -> ConverterRegistry.prepareModule(plugin,
                    new String[]{FIRST}, getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(ValidConverter.class.getName())
                    .hasMessageContaining(DuplicateConverter.class.getName());
            assertThat(ConverterRegistry.hasModule(plugin)).isFalse();
        }
    }

    @Test
    void directRegistrationChecksBeforeInit() throws Exception {
        BadConfig config = new BadConfig("config/bad.yml");
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, null)) {
            assertThatThrownBy(() -> new ConfigManager().register(plugin, config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(Value.class.getName());
            assertThat(Files.exists(directory.resolve("config/bad.yml"))).isFalse();
        }
    }

    @Test
    void connectorConstructorChecksTypesBeforeLanguageOrResourceExtraction() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, BadConfig.class)) {
            assertThatThrownBy(() -> new Connector(directory.toString()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(Value.class.getName());
            assertThat(constructed).isFalse();
            assertThat(Files.exists(directory.resolve("config/bad.yml"))).isFalse();
        }
    }

    @Test
    void disabledAutoRegistrationIgnoresUnusedEntitiesButDiscoversConverters() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(ValidConverter.class, BadConfig.class)) {
            scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                    .thenAnswer(call -> call.getArgument(0) == ConfigConverterFor.class
                            ? Collections.singleton(ValidConverter.class) : Collections.singleton(BadConfig.class));
            ManualConnector connector = new ManualConnector(directory.toString());
            assertThat(ConverterRegistry.forModule(connector).resolve(Value.class)).isInstanceOf(ValidConverter.class);
            assertThat(constructed).isFalse();
            assertThat(manualCalls).isEqualTo(1);
            assertThat(languageReady).isTrue();
        }
    }

    @Test
    void absentAutoAnnotationIgnoresUnusedEntitiesAtOriginalManualCallPoint() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, BadConfig.class)) {
            assertThatCode(() -> new UnannotatedConnector(directory.toString())).doesNotThrowAnyException();
            assertThat(constructed).isFalse();
            assertThat(manualCalls).isEqualTo(1);
            assertThat(languageReady).isTrue();
        }
    }

    @Test
    void cachedDiscoveryDoesNotBypassExplicitManualWholeBatchValidation() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, null)) {
            ConverterRegistry.prepareModule(plugin, new String[]{FIRST, SECOND}, getClass().getClassLoader());
            scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                    .thenAnswer(call -> call.getArgument(0) != ConfigEntity.class ? Collections.emptySet()
                            : FIRST.equals(call.getArgument(1)) ? Collections.singleton(PlainConfig.class)
                            : Collections.singleton(BadConfig.class));
            assertThatThrownBy(() -> new ConfigManager().registerAll(plugin,
                    new String[]{FIRST, SECOND}, getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining(Value.class.getName());
            assertThat(constructed).isFalse();
            assertThat(directory.resolve("config/plain.yml")).doesNotExist();
        }
    }

    @Test
    void directRegistrationValidatesOnlyActualEntityNotUnusedScanClasses() throws Exception {
        PlainConfig selected = new PlainConfig("config/plain.yml");
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, BadConfig.class)) {
            ConfigManager manager = new ConfigManager();
            manager.register(plugin, selected);
            assertThat(manager.getAllConfigEntities(plugin)).containsEntry("config/plain.yml", selected);
        }
    }

    @Test
    void emptyConfigAnnotationIsStillIgnoredWithoutValidatingItsFields() {
        try (MockedStatic<PackageScanUtils> scanner = scanner(null, EmptyConfig.class)) {
            ConfigManager manager = new ConfigManager();
            assertThatCode(() -> manager.registerAll(plugin, FIRST, getClass().getClassLoader()))
                    .doesNotThrowAnyException();
            assertThat(constructed).isFalse();
            assertThat(manager.getAllConfigEntities(plugin)).isNull();
        }
    }

    private MockedStatic<PackageScanUtils> scanner(Class<?> converter, Class<?> entity) {
        MockedStatic<PackageScanUtils> scanner = mockStatic(PackageScanUtils.class);
        scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                .thenAnswer(call -> {
                    Class<? extends Annotation> annotation = call.getArgument(0);
                    String scannedPackage = call.getArgument(1);
                    if (annotation == ConfigConverterFor.class && converter != null && SECOND.equals(scannedPackage)) {
                        return Collections.singleton(converter);
                    }
                    if (annotation == ConfigEntity.class && entity != null && !SECOND.equals(scannedPackage)) {
                        return Collections.singleton(entity);
                    }
                    return Collections.emptySet();
                });
        return scanner;
    }

    public static class Value { }

    @ConfigConverterFor(Value.class)
    public static class ValidConverter implements ConfigConverter<Value> {
        @Override
        public Object toPlain(Value value, ConversionContext ctx) {
            return "value";
        }
        @Override
        public Value fromPlain(Object plain, ConversionContext ctx) {
            return new Value();
        }
    }

    @ConfigConverterFor(Value.class)
    public static class DuplicateConverter extends ValidConverter { }

    @ConfigConverterFor(Value.class)
    public static class PrivateConverter extends ValidConverter {
        private PrivateConverter() { }
    }

    @ConfigEntity("config/good.yml")
    public static class GoodConfig extends AbstractConfigEntity {
    @SuppressWarnings("PMD.AssignmentToNonFinalStatic") // Test-only constructor probe is reset and asserted by each discovery scenario.
        public GoodConfig(String path) {
            super(path);
            assertThat(ConverterRegistry.forModule(constructingPlugin).resolve(Value.class))
                    .isInstanceOf(ValidConverter.class);
            constructed = true;
        }
    }

    @ConfigEntity("config/bad.yml")
    public static class BadConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "values")
        private List<Value> values;
    @SuppressWarnings("PMD.AssignmentToNonFinalStatic") // Test-only constructor probe is reset and asserted by each discovery scenario.
        public BadConfig(String path) {
            super(path);
            constructed = true;
        }
    }

    @ConfigEntity("config/plain.yml")
    public static class PlainConfig extends AbstractConfigEntity {
        @ConfigEntry private String value = "ready";
    @SuppressWarnings("PMD.AssignmentToNonFinalStatic") // Test-only constructor probe is reset and asserted by each discovery scenario.
        public PlainConfig(String path) { super(path); constructed = true; }
    }

    @ConfigEntity("")
    public static class EmptyConfig extends BadConfig {
        public EmptyConfig(String path) { super(path); }
    }

    public static class UnannotatedConnector extends UltiToolsPlugin {
        public UnannotatedConnector(String directory) {
            super("FixtureModule", "1.0", Collections.emptyList(), Collections.emptyList(),
                    630, UnannotatedConnector.class.getName(), directory);
        }
        @Override public List<AbstractConfigEntity> getAllConfigs() {
            manualCalls++;
            languageReady = getLanguage() != null;
            return Collections.emptyList();
        }
        @Override public boolean registerSelf() { return true; }
    }

    @EnableAutoRegister(config = false)
    public static class ManualConnector extends UnannotatedConnector {
        public ManualConnector(String directory) { super(directory); }
    }

    @EnableAutoRegister
    public static class Connector extends UltiToolsPlugin {
        public Connector(String directory) {
            super("FixtureModule", "1.0", Collections.emptyList(), Collections.emptyList(),
                    630, Connector.class.getName(), directory);
        }
        @Override
        public boolean registerSelf() { return true; }
    }
}
