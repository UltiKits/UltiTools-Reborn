package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.ultikits.testfixtures.economyattribution.registering.RegisteringEconomyModule;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * #483: a module that requests the economy while it is still being registered -- from its
 * constructor, its own {@code @PostConstruct} method or {@code registerSelf()} -- is named in the
 * warning. It is not in the loaded-module list until registration finishes, so attribution used to
 * report "an unknown caller". The module being registered is tracked for the registering thread
 * only, and only until its registration attempt ends.
 */
@DisplayName("#483: a module requesting the economy during its own registration is named")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EconomyUtilsRegisteringAttributionTest {

    @TempDir
    File tempDir;

    private Logger logger;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        logger = mock(Logger.class);
        pluginManager = new PluginManager();
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "en");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(logger);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
            when(ultiTools.getDataFolder()).thenReturn(tempDir);
            when(ultiTools.getConfig()).thenReturn(config);
            when(ultiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
            when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
            when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        });
        RegisteringEconomyModule.resourceFolder = new File(tempDir, "RegisteringEconomyModule").getAbsolutePath();
        EconomyUtils.reset();
    }

    @AfterEach
    void tearDown() {
        RegisteringEconomyModule.window = "";
        EconomyUtils.reset();
        MockBukkitHelper.safeUnmock();
    }

    private boolean registerWithRequestIn(String window) {
        RegisteringEconomyModule.window = window;
        // getPluginVersion() reads the framework's own env.yml resource, which the mocked
        // UltiTools cannot serve; every other static member stays real.
        try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
            return pluginManager.register(RegisteringEconomyModule.class);
        }
    }

    /** The economy warnings only: the fixture module's own logger writes through the same mock. */
    private List<String> warnings(int expected) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeast(0)).warning(captor.capture());
        List<String> economy = captor.getAllValues().stream()
                .filter(message -> message.contains("requested the economy service"))
                .collect(Collectors.toList());
        assertThat(economy).hasSize(expected);
        return economy;
    }

    private void assertNamedOnce() {
        assertThat(warnings(1).get(0)).contains("Module '" + RegisteringEconomyModule.NAME + "'")
                .doesNotContain(EconomyUtils.UNKNOWN_MODULE);
    }

    @Test
    @DisplayName("a request from the module's constructor is attributed to the module")
    void requestFromConstructorIsNamed() {
        assertThat(registerWithRequestIn("constructor")).isTrue();
        assertNamedOnce();
    }

    @Test
    @DisplayName("a request from the module's @PostConstruct method is attributed to the module")
    void requestFromPostConstructIsNamed() {
        assertThat(registerWithRequestIn("postconstruct")).isTrue();
        assertNamedOnce();
    }

    @Test
    @DisplayName("a request from registerSelf() is attributed to the module")
    void requestFromRegisterSelfIsNamed() {
        assertThat(registerWithRequestIn("registerself")).isTrue();
        assertNamedOnce();
    }

    @Test
    @DisplayName("a second thread started during registration does not see the module being registered")
    void otherThreadDuringRegistrationIsNotAttributedToTheRegisteringModule() {
        registerWithRequestIn("otherthread");

        assertThat(warnings(1).get(0)).contains(EconomyUtils.UNKNOWN_MODULE);
    }

    @Test
    @DisplayName("after a failed registration the module is no longer tracked")
    void failedRegistrationLeavesNothingTracked() {
        assertThat(registerWithRequestIn("refuse")).isFalse();

        RegisteringEconomyModule.requestEconomyNow();

        assertThat(warnings(1).get(0)).contains(EconomyUtils.UNKNOWN_MODULE);
    }
}
