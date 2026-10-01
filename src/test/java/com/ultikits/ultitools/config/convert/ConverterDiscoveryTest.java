package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.PackageScanUtils;
import com.ultikits.ultitools.utils.TestHelper;

class ConverterDiscoveryTest {
    private static final String FIRST = "fixture.first";
    private static final String SECOND = "fixture.second";
    private static UltiToolsPlugin constructingPlugin;
    private static boolean constructed;

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
            assertThat(ConverterRegistry.hasModule(plugin)).isFalse();
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
        public BadConfig(String path) {
            super(path);
            constructed = true;
        }
    }

    public static class Connector extends UltiToolsPlugin {
        public Connector(String directory) {
            super("FixtureModule", "1.0", Collections.emptyList(), Collections.emptyList(),
                    630, Connector.class.getName(), directory);
        }
        @Override
        public boolean registerSelf() { return true; }
        @Override
        public void unregisterSelf() { }
    }
}
