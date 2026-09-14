package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.services.EconomyProvider;
import com.ultikits.ultitools.services.impl.VaultEconomyProvider;

import net.milkbowl.vault.economy.Economy;

/**
 * Pins D-08's seven honest-reporting behaviours (#451): once a server's economy is unavailable,
 * every calling module is told exactly once per session, with a message that distinguishes
 * "Vault is not installed" from "Vault is installed but no provider is registered" and states the
 * condition belongs to the server's own environment, not to a framework or module defect.
 * <p>
 * The WARN/dedup logic and the pure module-attribution logic are tested separately (see class
 * javadoc on {@link EconomyUtils#reportEconomyStateIfUnavailable(String)} and
 * {@link EconomyUtils#attributeModule(StackTraceElement[], Map)} for why): behaviours 1-6 below
 * drive {@code reportEconomyStateIfUnavailable(String)} directly with an explicit module name,
 * since the dedup/state/message logic under test does not semantically depend on how that name
 * was resolved — the {@code AttributeModuleTests} nested class separately pins the pure
 * attribution function itself against synthetic stack traces.
 */
@DisplayName("EconomyUtils honest reporting (D-08, #451)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EconomyUtilsReportingTest {

    private ServerMock server;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        mockLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getLogger()).thenReturn(mockLogger));
        EconomyUtils.reset();
    }

    @AfterEach
    void tearDown() {
        EconomyUtils.reset();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("1: Vault absent — a module's first request logs exactly one WARNING naming the module, the missing-plugin cause, and the install instruction")
    void vaultAbsent_firstRequest_logsOneWarningWithModuleAndCauseAndInstruction() {
        EconomyUtils.reportEconomyStateIfUnavailable("ModuleA");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).warning(captor.capture());
        String message = captor.getValue();
        assertThat(message).contains("ModuleA");
        assertThat(message).contains("Vault is not installed on this server");
        assertThat(message).contains("This is the server's environment, not a defect in UltiTools or in 'ModuleA'");
        assertThat(message).contains("Install Vault");
    }

    @Test
    @DisplayName("2: Vault absent — the same module's second request logs no additional warning")
    void vaultAbsent_secondRequestFromSameModule_logsNoAdditionalWarning() {
        EconomyUtils.reportEconomyStateIfUnavailable("ModuleA");
        EconomyUtils.reportEconomyStateIfUnavailable("ModuleA");

        verify(mockLogger, times(1)).warning(anyString());
    }

    @Test
    @DisplayName("3: Vault absent — a different module's first request logs one more warning, naming that module")
    void vaultAbsent_differentModulesFirstRequest_logsOneMoreWarningNamingIt() {
        EconomyUtils.reportEconomyStateIfUnavailable("ModuleA");
        EconomyUtils.reportEconomyStateIfUnavailable("ModuleB");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(2)).warning(captor.capture());
        assertThat(captor.getAllValues().get(0)).contains("ModuleA");
        assertThat(captor.getAllValues().get(1)).contains("ModuleB");
    }

    @Test
    @DisplayName("4: Vault present, no provider registered — the warning names that cause, distinguishable from the missing-plugin text")
    void vaultPresentNoProvider_warningNamesThatCauseDistinctly() {
        MockBukkit.createMockPlugin("Vault");

        EconomyUtils.reportEconomyStateIfUnavailable("ModuleC");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).warning(captor.capture());
        String message = captor.getValue();
        assertThat(message).contains("Vault is installed, but no economy provider is registered with it");
        assertThat(message).doesNotContain("Vault is not installed");
    }

    @Test
    @DisplayName("5: Vault present with a provider registered — no warning is logged, and the start-up line names the provider")
    void vaultPresentWithProvider_noWarning_startupLineNamesProvider() {
        Plugin vaultPlugin = MockBukkit.createMockPlugin("Vault");
        Economy mockEconomy = mock(Economy.class);
        when(mockEconomy.getName()).thenReturn("TestEconomyPlugin");
        server.getServicesManager().register(Economy.class, mockEconomy, vaultPlugin, ServicePriority.Normal);

        EconomyUtils.reportEconomyStateIfUnavailable("ModuleD");
        verify(mockLogger, never()).warning(anyString());

        EconomyUtils.logStartupState();
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).info(infoCaptor.capture());
        assertThat(infoCaptor.getValue()).contains("TestEconomyPlugin");
    }

    @Test
    @DisplayName("6: a request attributed to no registered module still logs once, as an unknown caller, and never throws")
    void unattributedRequest_logsOnceAsUnknownCaller_neverThrows() {
        assertThatCode(() -> EconomyUtils.reportEconomyStateIfUnavailable(null)).doesNotThrowAnyException();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).warning(captor.capture());
        assertThat(captor.getValue()).contains(EconomyUtils.UNKNOWN_MODULE);
    }

    @Test
    @DisplayName("7: EconomyProvider carries @ApiStatus.Internal and its implementation registers under both a bean name and its interface name")
    void economyProviderIsInternalAndRegistersUnderBeanNameAndInterfaceName() throws Exception {
        // @ApiStatus.Internal is RetentionPolicy.CLASS (CLAUDE.md: "a build-time/IDE signal"),
        // so it is deliberately invisible to Class#isAnnotationPresent at runtime -- it is still
        // present in the compiled .class bytes as an invisible annotation attribute, so read
        // those directly rather than asserting something the JVM's reflection API cannot see.
        assertThat(classBytesContain(EconomyProvider.class, "Lorg/jetbrains/annotations/ApiStatus$Internal;"))
                .as("EconomyProvider.class bytecode must carry the ApiStatus.Internal annotation descriptor")
                .isTrue();

        SimpleContainer container = new SimpleContainer();
        VaultEconomyProvider provider = new VaultEconomyProvider();
        container.registerSingleton("vaultEconomyProvider", provider);
        container.registerSingleton(EconomyProvider.class.getName(), provider);

        assertThat(container.getBean("vaultEconomyProvider")).isSameAs(provider);
        assertThat(container.getBean(EconomyProvider.class)).isSameAs(provider);
    }

    /**
     * Reads {@code type}'s own compiled {@code .class} bytes and checks whether the given
     * UTF-8/Latin-1-safe descriptor substring (e.g. an annotation type descriptor) appears
     * anywhere in them — the only way to observe a {@code RetentionPolicy.CLASS} annotation once
     * compiled, since the JVM's runtime reflection API does not retain it.
     */
    private static boolean classBytesContain(Class<?> type, String descriptor) throws java.io.IOException {
        String resourcePath = "/" + type.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = type.getResourceAsStream(resourcePath)) {
            assertThat(in).as("compiled class resource must exist: " + resourcePath).isNotNull();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String classFileText = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
            return classFileText.contains(descriptor);
        }
    }

    @Test
    @DisplayName("8: getEconomy() sees a provider that registers after an earlier failed call, instead of caching that failure forever (CR-01, 16-REVIEW-economy.md)")
    void getEconomy_seesLateProviderRegistration_insteadOfCachingEarlierFailureForever() {
        // First call happens before Vault is installed at all -- the ordinary case during a
        // normal boot sequence, since DependenceManagers.initCoreServices() runs early.
        assertThat(EconomyUtils.getEconomy()).isNull();

        // Vault, and its economy provider, register AFTER that first failed call.
        Plugin vaultPlugin = MockBukkit.createMockPlugin("Vault");
        Economy mockEconomy = mock(Economy.class);
        server.getServicesManager().register(Economy.class, mockEconomy, vaultPlugin, ServicePriority.Normal);

        assertThat(EconomyUtils.isAvailable()).isTrue();
        assertThat(EconomyUtils.getEconomy())
                .as("getEconomy() must re-check the live provider, not return a cached-forever null")
                .isSameAs(mockEconomy);
    }

    @Test
    @DisplayName("9: getEconomy() reports the same honest WARNING every sibling operation does, not silently returning null (CR-01, 16-REVIEW-economy.md)")
    void getEconomy_reportsUnavailability_likeEverySiblingOperation() {
        assertThat(EconomyUtils.getEconomy()).isNull();

        verify(mockLogger, times(1)).warning(anyString());
    }

    @Test
    @DisplayName("10: setup() also re-checks the live provider rather than latching its first failed attempt forever (CR-01, 16-REVIEW-economy.md)")
    void setup_seesLateProviderRegistration_insteadOfCachingEarlierFailureForever() {
        assertThat(EconomyUtils.setup()).isFalse();

        Plugin vaultPlugin = MockBukkit.createMockPlugin("Vault");
        Economy mockEconomy = mock(Economy.class);
        server.getServicesManager().register(Economy.class, mockEconomy, vaultPlugin, ServicePriority.Normal);

        assertThat(EconomyUtils.setup()).isTrue();
    }

    @Nested
    @DisplayName("attributeModule — pure module-attribution logic")
    class AttributeModuleTests {

        @Test
        @DisplayName("an empty prefix map matches nothing")
        void emptyPrefixMap_returnsNull() {
            assertThat(EconomyUtils.attributeModule(Thread.currentThread().getStackTrace(), new LinkedHashMap<>()))
                    .isNull();
        }

        @Test
        @DisplayName("a null stack or a null prefix map is safe and matches nothing")
        void nullInputs_returnNullRatherThanThrow() {
            assertThat(EconomyUtils.attributeModule(null, new LinkedHashMap<>())).isNull();
            assertThat(EconomyUtils.attributeModule(new StackTraceElement[0], null)).isNull();
        }

        @Test
        @DisplayName("matches the first (most-recent) frame whose class name starts with a mapped prefix")
        void matchesFirstFrameWithMappedPrefix() {
            StackTraceElement[] stack = {
                    new StackTraceElement("com.ultikits.ultitools.utils.EconomyUtils", "getBalance", "EconomyUtils.java", 1),
                    new StackTraceElement("com.example.moduleb.SomeClass", "doThing", "SomeClass.java", 10),
                    new StackTraceElement("com.example.modulea.OtherClass", "doOtherThing", "OtherClass.java", 20),
            };
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            prefixToModule.put("com.example.modulea", "ModuleA");
            prefixToModule.put("com.example.moduleb", "ModuleB");

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isEqualTo("ModuleB");
        }

        @Test
        @DisplayName("a stack with no matching frame at all returns null")
        void noMatchingFrame_returnsNull() {
            StackTraceElement[] stack = {
                    new StackTraceElement("com.ultikits.ultitools.utils.EconomyUtils", "getBalance", "EconomyUtils.java", 1),
                    new StackTraceElement("org.bukkit.craftbukkit.CraftServer", "dispatch", "CraftServer.java", 5),
            };
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            prefixToModule.put("com.example.modulea", "ModuleA");

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isNull();
        }
    }
}
