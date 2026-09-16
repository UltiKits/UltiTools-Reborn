package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
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

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.PluginManager;
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

    // Codex P2, PR #463: three DISTINCT declared subclasses, not three mock(UltiToolsPlugin.class)
    // instances of the same abstract type. Mockito/ByteBuddy generates and reuses ONE dynamic
    // proxy subclass per mocked TYPE (not per instance), so three mocks of the bare
    // UltiToolsPlugin.class would all share the identical runtime Class -- silently collapsing
    // three intended per-plugin getPluginScanPackages(Class) stubs into one, with each when(...)
    // call's own argument-evaluation re-triggering whatever stub was already active for that
    // shared Class and overwriting it. That collision was caught only by observing its actual
    // symptom: the plugin-list mutation fired during test SETUP, before the real loop the test
    // means to exercise ever ran. Distinct declared types guarantee distinct mock classes.
    private abstract static class FixturePluginA extends UltiToolsPlugin {
    }

    private abstract static class FixturePluginB extends UltiToolsPlugin {
    }

    private abstract static class FixturePluginC extends UltiToolsPlugin {
    }

    @Test
    @DisplayName("11: a concurrent mutation of PluginManager's live plugin list during attribution does not propagate ConcurrentModificationException (Codex P2, PR #463)")
    void attributeCallingModule_concurrentPluginListMutation_doesNotPropagateCME() {
        // PluginManager#getPluginList() returns its live, unsynchronized ArrayList directly (see
        // PluginManager.java:103 -- @Getter over "private final List<UltiToolsPlugin> pluginList
        // = new ArrayList<>()"), and PluginInstallUtils#uninstallPlugin mutates that same list via
        // .remove(...), reachable from a normal /upm uninstall command. A module calling this
        // economy facade from an async command or @Scheduled(async = true) task can race that
        // mutation. Reproduced deterministically (no real thread timing needed): the first
        // plugin's own getPluginScanPackages() lookup removes the SECOND of three plugins from the
        // SAME live list as a side effect, mid-iteration -- exactly the ArrayList#modCount change a
        // fail-fast iterator detects, regardless of whether the real-world mutator is a second
        // thread or (as here) a re-entrant call on this one. A third plugin is required: removing
        // the second-to-last element of a two-element list instead makes ArrayList$Itr#hasNext()
        // return false (cursor == the new, shrunken size) before next()'s modCount check ever
        // fires, silently ending the loop one iteration early with no CME at all -- masking the
        // exact bug this test exists to catch.
        PluginManager pluginManager = mock(PluginManager.class);
        List<UltiToolsPlugin> livePluginList = new ArrayList<>();
        UltiToolsPlugin pluginA = mock(FixturePluginA.class);
        UltiToolsPlugin pluginB = mock(FixturePluginB.class);
        UltiToolsPlugin pluginC = mock(FixturePluginC.class);
        when(pluginA.getPluginName()).thenReturn("ModuleA");
        when(pluginB.getPluginName()).thenReturn("ModuleB");
        when(pluginC.getPluginName()).thenReturn("ModuleC");
        livePluginList.add(pluginA);
        livePluginList.add(pluginB);
        livePluginList.add(pluginC);
        when(pluginManager.getPluginList()).thenReturn(livePluginList);
        when(pluginManager.getPluginScanPackages(pluginA.getClass())).thenAnswer(invocation -> {
            livePluginList.remove(pluginB);
            return new String[] {"com.example.modulea"};
        });
        when(pluginManager.getPluginScanPackages(pluginB.getClass()))
                .thenReturn(new String[] {"com.example.moduleb"});
        when(pluginManager.getPluginScanPackages(pluginC.getClass()))
                .thenReturn(new String[] {"com.example.modulec"});

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        EconomyUtils.reset();

        assertThatCode(() -> EconomyUtils.getBalance(mock(OfflinePlayer.class)))
                .as("a concurrent mutation of PluginManager's live plugin list during attribution "
                        + "must not propagate ConcurrentModificationException out of the economy "
                        + "facade -- this is a best-effort attribution helper whose existing "
                        + "'unattributable' fallback (returning null) is the correct outcome here too")
                .doesNotThrowAnyException();
    }

    /**
     * A minimal {@code ArrayList} whose {@link #toArray()} deliberately corrupts its own result,
     * simulating the data race {@code ArrayList}'s real copy constructor is genuinely exposed to
     * under true concurrent mutation (Codex P2, PR #463, follow-up on 6beb6400): {@code toArray()}
     * reads the live {@code elementData} array and {@code size} field with no synchronization and
     * no fail-fast modCount check, so a structural change on another thread mid-copy can leave a
     * trailing {@code null} in the copied array where a real element should be — {@code
     * ConcurrentModificationException} is never involved, since nothing here is iterating via a
     * fail-fast {@code Iterator}. Deterministic in a single thread by construction, rather than
     * relying on real timing to hit a data race that may not reproduce reliably.
     */
    private static final class RacyToArrayList extends ArrayList<UltiToolsPlugin> {
        @Override
        public Object[] toArray() {
            Object[] real = super.toArray();
            if (real.length > 0) {
                real[real.length - 1] = null;
            }
            return real;
        }
    }

    @Test
    @DisplayName("12: a torn snapshot copy (trailing null, simulating ArrayList#toArray()'s own data race) does not propagate NullPointerException either (Codex P2, PR #463, follow-up on 6beb6400)")
    void attributeCallingModule_tornSnapshotCopy_doesNotPropagateNPE() {
        PluginManager pluginManager = mock(PluginManager.class);
        List<UltiToolsPlugin> racyList = new RacyToArrayList();
        UltiToolsPlugin pluginA = mock(FixturePluginA.class);
        UltiToolsPlugin pluginB = mock(FixturePluginB.class);
        when(pluginA.getPluginName()).thenReturn("ModuleA");
        when(pluginB.getPluginName()).thenReturn("ModuleB");
        racyList.add(pluginA);
        racyList.add(pluginB);
        when(pluginManager.getPluginList()).thenReturn(racyList);
        when(pluginManager.getPluginScanPackages(pluginA.getClass()))
                .thenReturn(new String[] {"com.example.modulea"});
        when(pluginManager.getPluginScanPackages(pluginB.getClass()))
                .thenReturn(new String[] {"com.example.moduleb"});

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        EconomyUtils.reset();

        assertThatCode(() -> EconomyUtils.getBalance(mock(OfflinePlayer.class)))
                .as("a torn snapshot copy (a null element where a real plugin should be, exactly "
                        + "what ArrayList's own unsynchronized toArray() can produce under real "
                        + "concurrent mutation) must not propagate NullPointerException out of the "
                        + "economy facade either -- the same 'unattributable, return null' fallback "
                        + "applies here as it does for ConcurrentModificationException")
                .doesNotThrowAnyException();
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

        @Test
        @DisplayName("a sibling package with a shared string prefix but no package boundary is NOT attributed (Codex P2, 16-07)")
        void siblingPackageWithSharedPrefixButNoBoundary_doesNotMatch() {
            // "com.example.foobar" shares the raw string "com.example.foo" as a prefix, but is a
            // completely unrelated package -- a plain String#startsWith check would misattribute
            // this request to "ModuleFoo", spending that module's once-per-session dedup slot on a
            // caller that was never actually inside it.
            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.foobar.SomeClass", "doThing", "SomeClass.java", 10),
            };
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            prefixToModule.put("com.example.foo", "ModuleFoo");

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isNull();
        }

        @Test
        @DisplayName("a class that IS the mapped package's own top-level class (no trailing dot) still matches")
        void exactPackagePrefixWithNoTrailingSegment_stillMatches() {
            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.foo.SomeClass", "doThing", "SomeClass.java", 10),
            };
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            prefixToModule.put("com.example.foo", "ModuleFoo");

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isEqualTo("ModuleFoo");
        }

        @Test
        @DisplayName("a nested scan-package collision attributes to the most specific (longest) matching prefix, insertion order broad-then-narrow (Codex P2, PR #463, #482)")
        void nestedScanPackageCollision_broadInsertedFirst_attributesToMostSpecific() {
            // com.example.shop.X matches BOTH "com.example" (ModuleA) and "com.example.shop"
            // (ModuleB) -- the correct attribution is ModuleB (the actual owner of the frame's
            // package), independent of which prefix was registered first. Before the fix, a first
            // match on insertion-order alone would return whichever module happened to be inserted
            // first, making module load order (not the frame's own package) decide attribution and
            // spending the wrong module's once-per-session dedup slot.
            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.shop.SomeClass", "doThing", "SomeClass.java", 10),
            };
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            prefixToModule.put("com.example", "ModuleA");
            prefixToModule.put("com.example.shop", "ModuleB");

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isEqualTo("ModuleB");
        }

        @Test
        @DisplayName("a nested scan-package collision attributes to the most specific (longest) matching prefix, insertion order narrow-then-broad (Codex P2, PR #463, #482)")
        void nestedScanPackageCollision_narrowInsertedFirst_attributesToMostSpecific() {
            // Same collision as above with the two entries inserted in the opposite order -- the
            // result must be identical (ModuleB), proving the selection depends on prefix
            // specificity, not on LinkedHashMap iteration/insertion order.
            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.shop.SomeClass", "doThing", "SomeClass.java", 10),
            };
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            prefixToModule.put("com.example.shop", "ModuleB");
            prefixToModule.put("com.example", "ModuleA");

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isEqualTo("ModuleB");
        }
    }

    @Nested
    @DisplayName("scan-package ownership merging — ambiguous shared root handling (Codex P2, PR #463, EconomyUtils.java:440)")
    class ScanPackageOwnershipTests {

        @Test
        @DisplayName("1: two DIFFERENT modules declaring the identical scan root make a frame in that root unattributed, not attributed to whichever module registered first")
        void identicalScanRoot_twoDifferentModules_frameIsUnattributed() {
            // Reproduces the finding directly: ModuleA and ModuleB both declare the exact same
            // scan root, "com.example.shared". Before the fix, mergeScanPackageOwner() is a plain
            // putIfAbsent -- ModuleA (registered first) silently wins and ModuleB is discarded, so
            // a frame in the shared root is wrongly attributed to ModuleA even though it may
            // genuinely belong to ModuleB. After the fix, the shared root is recognised as
            // ambiguous and the frame is left unattributed (null), which the caller then reports
            // as EconomyUtils.UNKNOWN_MODULE rather than naming either module.
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleA");
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleB");

            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.shared.SomeClass", "doThing", "SomeClass.java", 10),
            };

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isNull();
        }

        @Test
        @DisplayName("2: the same two modules, but a longer root only ModuleA declares is still named -- the ambiguity rule does not swallow the normal case")
        void longerRootDeclaredByOnlyOneModule_stillNamed_evenWhenShorterRootIsAmbiguous() {
            // Same ambiguous shared root as test 1, PLUS a longer, more specific root that only
            // ModuleA declares. A frame inside the longer root must still resolve to ModuleA --
            // the longest-matching-prefix rule stays intact for the normal case; ambiguity only
            // applies to a frame whose BEST (longest) match is itself an ambiguous root.
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleA");
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleB");
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared.modulea", "ModuleA");

            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.shared.modulea.SomeClass", "doThing", "SomeClass.java", 10),
            };

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isEqualTo("ModuleA");
        }

        @Test
        @DisplayName("3: the same module registering its own scan root twice is not treated as ambiguous")
        void sameModuleRegisteringSameRootTwice_isNotAmbiguous() {
            // Guards the merge helper itself, not the reviewer's finding directly: re-declaring
            // the identical (root, owner) pair -- e.g. a module present in more than one of
            // PluginManager's scan sources -- must not be confused with two DIFFERENT modules
            // sharing a root.
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleA");
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleA");

            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.shared.SomeClass", "doThing", "SomeClass.java", 10),
            };

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isEqualTo("ModuleA");
        }

        @Test
        @DisplayName("4: the closest frame's ambiguous root terminates attribution -- it does NOT fall through to a more distant frame's unambiguous module (Codex P2, PR #463, round 3)")
        void closestFrameAmbiguousRoot_terminatesAttribution_doesNotFallThroughToMoreDistantModule() {
            // The closest (most-recent) frame matches the ambiguous "com.example.shared" root
            // (ModuleA and ModuleB both declared it). A MORE DISTANT frame, further up the same
            // stack, unambiguously matches "com.example.other" (ModuleC alone declared it). The
            // closest frame that matches ANY scan root identifies the requester; if THAT frame's
            // root is ambiguous, the requester cannot be determined, full stop -- continuing past
            // it and naming ModuleC (an unrelated, more distant module) would reproduce the exact
            // wrong-module-name defect this fix removes, just by a longer route. Before this
            // round's fix, an ambiguous frame was treated identically to "no match for this
            // frame", so the walk continued outward and returned "ModuleC" -- wrong.
            Map<String, String> prefixToModule = new LinkedHashMap<>();
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleA");
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.shared", "ModuleB");
            EconomyUtils.mergeScanPackageOwner(prefixToModule, "com.example.other", "ModuleC");

            StackTraceElement[] stack = {
                    new StackTraceElement("com.example.shared.SomeClass", "doThing", "SomeClass.java", 10),
                    new StackTraceElement("com.example.other.OtherClass", "doOtherThing", "OtherClass.java", 20),
            };

            assertThat(EconomyUtils.attributeModule(stack, prefixToModule)).isNull();
        }
    }

    @Test
    @DisplayName("13: an ambiguous attribution (shared scan root) consumes ONE shared UNKNOWN_MODULE dedup slot, not one per module (Codex P2, PR #463)")
    void ambiguousAttribution_sharedDedupSlot_notOnePerModule() {
        // attributeModule() resolves an ambiguous shared root to null (ScanPackageOwnershipTests
        // test 1), and reportEconomyStateIfUnavailable(String) always maps a null moduleName onto
        // the SAME constant, EconomyUtils.UNKNOWN_MODULE (EconomyUtils.java:354) -- there is no
        // path by which two different modules' ambiguous requests can consume two different dedup
        // slots. Simulated here as two separate reportEconomyStateIfUnavailable(null) calls
        // (exactly what attributeCallingModule() now returns for both ModuleA's and ModuleB's
        // calls into their shared root), asserting only ONE warning fires in total.
        EconomyUtils.reportEconomyStateIfUnavailable(null);
        EconomyUtils.reportEconomyStateIfUnavailable(null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).warning(captor.capture());
        assertThat(captor.getValue()).contains(EconomyUtils.UNKNOWN_MODULE);
    }
}
