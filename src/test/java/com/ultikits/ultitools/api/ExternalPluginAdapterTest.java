package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.testfixtures.externalplugininjection.ConnectorPluginFixture;
import com.ultikits.testfixtures.externalplugininjection.JavaPluginInjectingService;
import com.ultikits.testfixtures.externalplugininjection.UltiToolsPluginInjectingService;
import com.ultikits.testfixtures.wr01contractgap.broken.ConnectorPluginFixtureBroken;
import com.ultikits.testfixtures.wr01contractgap.ok.ConnectorPluginFixtureOk;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;

public class ExternalPluginAdapterTest {
    private JavaPlugin mockPlugin;
    private ExternalPluginAdapter adapter;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(JavaPlugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(mockPlugin.getName()).thenReturn("TestExternalPlugin");
        when(mockPlugin.getDescription()).thenReturn(desc);
        when(desc.getVersion()).thenReturn("1.0.0");
        when(desc.getAuthors()).thenReturn(Arrays.asList("TestAuthor"));
        when(desc.getMain()).thenReturn("com.example.test.TestPlugin");
        when(mockPlugin.getDataFolder()).thenReturn(new File("/tmp/TestExternalPlugin"));
        when(mockPlugin.getLogger()).thenReturn(Logger.getLogger("TestExternalPlugin"));

        adapter = new ExternalPluginAdapter(mockPlugin);
    }

    @Test
    void getPluginName_returnsJavaPluginName() {
        assertThat(adapter.getPluginName()).isEqualTo("TestExternalPlugin");
    }

    @Test
    void getVersion_returnsJavaPluginVersion() {
        assertThat(adapter.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void getDataFolder_returnsJavaPluginDataFolder() {
        assertThat(adapter.getDataFolder()).isEqualTo(new File("/tmp/TestExternalPlugin"));
    }

    @Test
    void getPluginClassLoader_returnsNonNull() {
        // Mock's classloader is the test classloader — just verify it's not null
        assertThat(adapter.getPluginClassLoader()).isNotNull();
    }

    @Test
    void getJavaPlugin_returnsWrappedPlugin() {
        assertThat(adapter.getJavaPlugin()).isSameAs(mockPlugin);
    }

    @Test
    void getScanPackage_returnsMainClassPackage() {
        assertThat(adapter.getScanPackage()).isEqualTo("com.example.test");
    }

    @Test
    void getScanPackage_withDeepNesting() {
        JavaPlugin plugin2 = mock(JavaPlugin.class);
        PluginDescriptionFile desc2 = mock(PluginDescriptionFile.class);
        when(plugin2.getName()).thenReturn("DeepPlugin");
        when(plugin2.getDescription()).thenReturn(desc2);
        when(desc2.getVersion()).thenReturn("1.0.0");
        when(desc2.getAuthors()).thenReturn(Arrays.asList("Author"));
        when(desc2.getMain()).thenReturn("com.example.deep.nested.pkg.MyPlugin");
        when(plugin2.getDataFolder()).thenReturn(new File("/tmp/DeepPlugin"));
        when(plugin2.getLogger()).thenReturn(Logger.getLogger("DeepPlugin"));

        ExternalPluginAdapter adapter2 = new ExternalPluginAdapter(plugin2);
        assertThat(adapter2.getScanPackage()).isEqualTo("com.example.deep.nested.pkg");
    }

    @Test
    void getScanPackage_topLevelClass_returnsEmpty() {
        JavaPlugin plugin3 = mock(JavaPlugin.class);
        PluginDescriptionFile desc3 = mock(PluginDescriptionFile.class);
        when(plugin3.getName()).thenReturn("TopLevel");
        when(plugin3.getDescription()).thenReturn(desc3);
        when(desc3.getVersion()).thenReturn("1.0.0");
        when(desc3.getAuthors()).thenReturn(Arrays.asList("Author"));
        when(desc3.getMain()).thenReturn("TopLevelPlugin");
        when(plugin3.getDataFolder()).thenReturn(new File("/tmp/TopLevel"));
        when(plugin3.getLogger()).thenReturn(Logger.getLogger("TopLevel"));

        ExternalPluginAdapter adapter3 = new ExternalPluginAdapter(plugin3);
        assertThat(adapter3.getScanPackage()).isEqualTo("");
    }

    @Test
    void isConnected_defaultsFalse() {
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    void setConnected_updatesState() {
        adapter.setConnected(true);
        assertThat(adapter.isConnected()).isTrue();
    }

    @Test
    void getLogger_delegatesToJavaPlugin() {
        Logger logger = adapter.getLogger();
        assertThat(logger).isNotNull();
    }

    @Test
    void getAuthors_returnsPluginAuthors() {
        assertThat(adapter.getAuthors()).containsExactly("TestAuthor");
    }

    @Test
    void getMainClass_returnsPluginMainClass() {
        assertThat(adapter.getMainClass()).isEqualTo("com.example.test.TestPlugin");
    }

    /**
     * SILENT-16 (#331): {@code registerExternal} creates a child container parented to the core
     * context, which already holds the CORE {@code UltiTools} instance under the name
     * {@code "ultiTools"} ({@code DependenceManagers:34}) -- and {@code UltiTools extends
     * JavaPlugin}. Before this plan's fix, a {@code @Service} in the connector's own scan package
     * constructor-injecting {@code JavaPlugin} misses the (empty) child, walks up, and
     * {@code isInstance}-matches that core instance instead of the connector's own. Each test
     * below registers a decoy {@code JavaPlugin} singleton in a hand-built parent container to
     * stand in for that core instance, so a wrong-instance regression fails loudly on identity,
     * not silently on a null or a coincidentally-matching mock.
     * <br>
     * SILENT-16（#331）：{@code registerExternal} 建的子容器，其父容器里已经存着核心
     * {@code UltiTools} 实例，注册名是 {@code "ultiTools"}（{@code DependenceManagers:34}），
     * 而 {@code UltiTools extends JavaPlugin}。在本计划的修复之前，连接器自身扫描包里一个
     * 构造注入 {@code JavaPlugin} 的 {@code @Service}，会因为（空的）子容器没命中而往上走，
     * 用 {@code isInstance} 匹配到那个核心实例，而不是连接器自己的。下面每个测试都在自建的父容器
     * 里注册一个诱饵 {@code JavaPlugin} 单例，代表那个核心实例，这样注错实例的回归会在身份比对上
     * 明显失败，而不是悄悄变成 null 或恰好匹配的 mock。
     */
    @Nested
    @DisplayName("child-container JavaPlugin injection parity (SILENT-16, #331)")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective reset of UltiTools.ultiTools between tests
    class ChildContainerInjectionTests {

        @BeforeEach
        void setUpMockBukkit() {
            com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
            MockBukkit.mock();
            MockBukkit.createMockPlugin();
        }

        @AfterEach
        void tearDownMockBukkit() throws Exception {
            resetUltiToolsInstance();
            com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        }

        private void resetUltiToolsInstance() throws Exception {
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        }

        private PluginManager newPluginManagerWithParent(SimpleContainer parentContext) {
            DependenceManagers mockDependenceManagers = mock(DependenceManagers.class);
            when(mockDependenceManagers.getContext()).thenReturn(parentContext);

            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
                when(ultiTools.getDependenceManagers()).thenReturn(mockDependenceManagers);
                when(ultiTools.getCommandManager()).thenReturn(new CommandManager());
                when(ultiTools.getListenerManager()).thenReturn(new ListenerManager());
                // wireAop resolves a DataSource through UltiTools.getInstance().getDataStore() for
                // the @Transactional advisor. CALLS_REAL_METHODS lets the interface's own default
                // getDataSource(DataScope) run and throw UnsupportedOperationException -- wireAop's
                // existing graceful "declare unavailable" fallback -- instead of a bare mock's null
                // surfacing as an NPE.
                when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
            });

            return new PluginManager();
        }

        private SimpleContainer newParentContextWithCoreDecoy(JavaPlugin coreDecoy) {
            SimpleContainer parentContext = new SimpleContainer();
            parentContext.refresh();
            // Mirrors DependenceManagers:34's registerSingleton("ultiTools", plugin) -- the
            // wrong-instance source this plan's child registration shadows.
            parentContext.registerSingleton("ultiTools", coreDecoy);
            // Every test's scan package is shared by all four fixture services (they must be, so
            // scanComponents finds them together), and refresh() eagerly instantiates every
            // non-lazy singleton it scans -- so UltiToolsPluginInjectingService's constructor
            // parameter needs something UltiToolsPlugin-typed reachable from the parent in every
            // test, not only the one that asserts on it.
            parentContext.registerSingleton("someModule", mock(UltiToolsPlugin.class));
            return parentContext;
        }

        @Test
        @DisplayName("a @Service constructor-injecting JavaPlugin receives the connector's own instance, not the core's")
        void javaPluginConstructorInjection_receivesConnectorsOwnInstance() throws Exception {
            JavaPlugin coreDecoy = mock(JavaPlugin.class);
            PluginManager pm = newPluginManagerWithParent(newParentContextWithCoreDecoy(coreDecoy));

            ConnectorPluginFixture connectorPlugin = MockBukkit.loadSimple(ConnectorPluginFixture.class);
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(connectorPlugin);

            pm.registerExternal(adapter);

            JavaPluginInjectingService service =
                    adapter.getContext().getBean(JavaPluginInjectingService.class);

            assertThat(service).isNotNull();
            assertThat(service.getInjectedPlugin()).isSameAs(connectorPlugin);
            assertThat(service.getInjectedPlugin()).isNotSameAs(coreDecoy);
        }

        @Test
        @DisplayName("the connector's own concrete plugin class also resolves to that instance")
        void concreteClassRegistration_resolvesToConnectorsOwnInstance() throws Exception {
            JavaPlugin coreDecoy = mock(JavaPlugin.class);
            PluginManager pm = newPluginManagerWithParent(newParentContextWithCoreDecoy(coreDecoy));

            ConnectorPluginFixture connectorPlugin = MockBukkit.loadSimple(ConnectorPluginFixture.class);
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(connectorPlugin);

            pm.registerExternal(adapter);

            // registerType keys by exact Class, so a constructor parameter declared as the
            // connector's own concrete runtime class (not the general JavaPlugin type
            // JavaPluginInjectingService exercises) needs to resolve too. Queried directly on the
            // container -- not through a scanned @Service -- because MockBukkit's own plugin
            // loader (PluginManagerMock.loadPlugin -> loadProxyClass) instantiates a dynamically
            // generated proxy subclass of ConnectorPluginFixture, not ConnectorPluginFixture
            // itself, so no compile-time-fixed fixture could declare a constructor parameter of
            // that exact, only-known-at-test-runtime type.
            Object resolved = adapter.getContext().getBean(connectorPlugin.getClass());

            assertThat(resolved).isSameAs(connectorPlugin);
        }

        @Test
        @DisplayName("a @Service constructor-injecting UltiToolsPlugin still resolves from the parent, unaffected")
        void ultiToolsPluginConstructorInjection_stillResolvesFromParent() throws Exception {
            // An unrelated UltiToolsPlugin-typed singleton, standing in for whatever already
            // resolves this type from the parent today -- proves the JavaPlugin/concrete-class
            // registration this plan adds does not shadow a completely unrelated type (D-13).
            UltiToolsPlugin parentModule = mock(UltiToolsPlugin.class);
            SimpleContainer parentContext = new SimpleContainer();
            parentContext.refresh();
            parentContext.registerSingleton("ultiTools", mock(JavaPlugin.class));
            parentContext.registerSingleton("someModule", parentModule);

            PluginManager pm = newPluginManagerWithParent(parentContext);

            ConnectorPluginFixture connectorPlugin = MockBukkit.loadSimple(ConnectorPluginFixture.class);
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(connectorPlugin);

            pm.registerExternal(adapter);

            UltiToolsPluginInjectingService service =
                    adapter.getContext().getBean(UltiToolsPluginInjectingService.class);

            assertThat(service).isNotNull();
            assertThat(service.getInjectedPlugin()).isSameAs(parentModule);
        }

        @Test
        @DisplayName("two connectors registered in sequence each keep their own instance")
        void twoConnectorsRegisteredInSequence_eachKeepTheirOwnInstance() throws Exception {
            SimpleContainer parentContext = newParentContextWithCoreDecoy(mock(JavaPlugin.class));
            PluginManager pm = newPluginManagerWithParent(parentContext);

            ConnectorPluginFixture connectorOne = MockBukkit.loadSimple(ConnectorPluginFixture.class);
            ExternalPluginAdapter adapterOne = new ExternalPluginAdapter(connectorOne);
            pm.registerExternal(adapterOne);

            // A second connector with an empty (top-level) scan package -- this test is only about
            // the two connectors' own child-container type registrations staying isolated from
            // each other, not about a second scanned bean.
            JavaPlugin connectorTwo = mock(JavaPlugin.class);
            PluginDescriptionFile descTwo = mock(PluginDescriptionFile.class);
            when(connectorTwo.getName()).thenReturn("ConnectorTwo");
            when(connectorTwo.getDescription()).thenReturn(descTwo);
            when(descTwo.getVersion()).thenReturn("1.0.0");
            when(descTwo.getAuthors()).thenReturn(Collections.emptyList());
            when(descTwo.getMain()).thenReturn("ConnectorTwoMain");
            when(connectorTwo.getDataFolder()).thenReturn(new File("/tmp/ConnectorTwo"));
            when(connectorTwo.getLogger()).thenReturn(Logger.getLogger("ConnectorTwo"));

            ExternalPluginAdapter adapterTwo = new ExternalPluginAdapter(connectorTwo);
            pm.registerExternal(adapterTwo);

            assertThat(adapterTwo.getContext().getBean(JavaPlugin.class)).isSameAs(connectorTwo);

            JavaPluginInjectingService serviceOne =
                    adapterOne.getContext().getBean(JavaPluginInjectingService.class);
            assertThat(serviceOne.getInjectedPlugin()).isSameAs(connectorOne);
            assertThat(serviceOne.getInjectedPlugin()).isNotSameAs(connectorTwo);
        }
    }

    /**
     * WR-01 (05-REVIEW.md): {@code PluginManager.registerExternal(...)} -- the External Plugin
     * API's own registration path -- never reached
     * {@code PluginManager.validateCommandExecutorContracts(...)}, the SILENT-11 load-time
     * refusal that {@code register(UltiToolsPlugin)}/{@code initializePlugin} already enforce
     * (called from the last line of {@code assemblePluginContainer}). An external Bukkit
     * plugin's {@code BaseCommandExecutor} could declare {@code @CmdCD}/{@code @UsageLimit}
     * against a chain that omits the required validator and load silently unenforced --
     * SILENT-11, reached through the one command-registration surface the original fix did not
     * cover.
     * <p>
     * Two-sided per the proof-form rule: {@code registerExternal_withUnenforceableAnnotation_isRefused}
     * proves the gap is closed, and
     * {@code registerExternal_withSatisfiedContract_stillRegistersNormally} proves the fix does
     * not just refuse everything that comes through this path -- a one-sided assertion cannot
     * distinguish "the external path now validates" from "the external path now refuses
     * everything".
     * <p>
     * Uses the SAME MockBukkit-backed {@code registerExternal(...)} path as {@link
     * ChildContainerInjectionTests}, not a hand-mocked {@code JavaPlugin}: a bare Mockito mock
     * cannot reach far enough into {@code CommandManager.registerAllExternal} to exercise the
     * bean lookup this fix's placement depends on. The satisfied-contract test stops short of
     * asserting the FULL Bukkit {@code CommandMap} registration succeeds -- {@code
     * CommandManager.getCommandMap()}'s reflective {@code instanceof SimplePluginManager} check
     * returns {@code null} under MockBukkit's own {@code PluginManagerMock} regardless of this
     * fix (see {@code CommandManagerTest.GetCommandMapSimplePluginManagerTests}, a pre-existing,
     * documented environment limitation, not a WR-01 regression) -- and instead asserts the two
     * facts WR-01 actually claims: no {@code PluginModuleException} was thrown, and the
     * executor bean was constructed into the adapter's container.
     */
    @Nested
    @DisplayName("WR-01: registerExternal contract enforcement (post-review gap closure)")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective reset of UltiTools.ultiTools between tests
    class Wr01ContractEnforcementTests {

        @BeforeEach
        void setUpMockBukkit() {
            com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
            MockBukkit.mock();
        }

        @AfterEach
        void tearDownMockBukkit() throws Exception {
            resetUltiToolsInstance();
            com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        }

        private void resetUltiToolsInstance() throws Exception {
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        }

        private PluginManager newPluginManager() {
            SimpleContainer parentContext = new SimpleContainer();
            parentContext.refresh();

            DependenceManagers mockDependenceManagers = mock(DependenceManagers.class);
            when(mockDependenceManagers.getContext()).thenReturn(parentContext);

            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
                when(ultiTools.getDependenceManagers()).thenReturn(mockDependenceManagers);
                when(ultiTools.getCommandManager()).thenReturn(new CommandManager());
                when(ultiTools.getListenerManager()).thenReturn(new ListenerManager());
                // wireAop resolves a DataSource through UltiTools.getInstance().getDataStore() for
                // the @Transactional advisor -- see ChildContainerInjectionTests' identical stub.
                when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
                // CommandManager.register(...) reads getDescription().getName() to register the
                // Bukkit command under UltiTools' own plugin name -- unrelated to WR-01 itself,
                // but required for registerExternal(...) to reach that far without an unrelated
                // NullPointerException masking the assertion under test.
                PluginDescriptionFile ultiToolsDescription = mock(PluginDescriptionFile.class);
                when(ultiToolsDescription.getName()).thenReturn("UltiTools");
                when(ultiTools.getDescription()).thenReturn(ultiToolsDescription);
            });

            return new PluginManager();
        }

        @Test
        @DisplayName("An executor whose chain cannot enforce its declared @CmdCD is refused, exactly like the internal path")
        void registerExternal_withUnenforceableAnnotation_isRefused() throws Exception {
            PluginManager pm = newPluginManager();

            ConnectorPluginFixtureBroken connectorPlugin = MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class);
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(connectorPlugin);
            assertThat(adapter.getScanPackage())
                    .isEqualTo("com.ultikits.testfixtures.wr01contractgap.broken");

            assertThatThrownBy(() -> pm.registerExternal(adapter))
                    .isInstanceOf(PluginModuleException.class)
                    .hasMessageContaining("UnenforceableExternalCommandExecutor")
                    .hasMessageContaining("CmdCD");
        }

        @Test
        @DisplayName("An executor whose chain CAN enforce its declared @CmdCD still registers normally through the same path")
        void registerExternal_withSatisfiedContract_stillRegistersNormally() throws Exception {
            PluginManager pm = newPluginManager();

            ConnectorPluginFixtureOk connectorPlugin = MockBukkit.loadSimple(ConnectorPluginFixtureOk.class);
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(connectorPlugin);
            assertThat(adapter.getScanPackage())
                    .isEqualTo("com.ultikits.testfixtures.wr01contractgap.ok");

            // See this class's javadoc: registerExternal(...) reaches WR-01's validation and
            // MUST NOT refuse a satisfied contract -- but the LATER Bukkit CommandMap step
            // throws a pre-existing, environment-specific NullPointerException under MockBukkit
            // regardless of this fix, so a bare doesNotThrowAnyException() would be too strict.
            Throwable thrown = catchThrowable(() -> pm.registerExternal(adapter));
            assertThat(thrown).isNotInstanceOf(PluginModuleException.class);

            // The container is assembled, refresh()'d, and validated (WR-01) BEFORE the Bukkit
            // registration step that throws -- so its presence, with the executor bean inside,
            // proves construction and validation both succeeded.
            assertThat(adapter.getContext()).isNotNull();
            assertThat(adapter.getContext().getBeanNamesForType(org.bukkit.command.CommandExecutor.class))
                    .isNotEmpty();
        }
    }
}
