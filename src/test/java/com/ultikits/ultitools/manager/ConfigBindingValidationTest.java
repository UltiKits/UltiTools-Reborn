package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.UltiToolsException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.testutil.BindingTimingConfig;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * #531 load-time and reload-time handling of config bindings on {@code @Scheduled} and
 * {@code @CmdCD}, driven through {@link PluginManager}'s package-private seams.
 * <p>
 * Load: a binding that cannot work refuses the one module, naming the key and the value, before
 * any Bukkit side effect -- the same place and the same granularity as the existing
 * command-executor contract check. {@code 0} does not mean "off" (maintainer decision 2), and the
 * default lives only in the config field, so a literal next to a binding is refused (decision 3).
 * <p>
 * Reload: an invalid value keeps the running one and logs a WARNING; a {@code @CmdCD} value is
 * cached per validator and changes only when the reload step runs.
 */
@DisplayName("Config-bound @Scheduled/@CmdCD validation and reload (#531)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // publishes/clears the UltiTools singleton and reaches private assembly
class ConfigBindingValidationTest {

    private ConfigManager configManager;
    private BindingTimingConfig config;
    private UltiToolsPlugin module;
    private UltiTools ultiTools;
    private final List<LogRecord> logs = new ArrayList<>();
    private Handler logCapture;

    // === Fixtures: @Scheduled beans ===

    public static class ValidScheduledBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", delayKey = "timer.delay")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class UndeclaredKeyBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.nope")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class FieldWithoutConfigEntryBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "notAnEntry")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class DoubleFieldBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.ratio")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class StringFieldBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.name")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class LiteralPeriodAndKeyBean {
        @Scheduled(config = BindingTimingConfig.class, period = 6000, periodKey = "timer.period")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class LiteralDelayAndKeyBean {
        @Scheduled(config = BindingTimingConfig.class, delay = 100, periodKey = "timer.period",
                delayKey = "timer.delay")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class KeyWithoutConfigBean {
        @Scheduled(periodKey = "timer.period")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class ConfigWithoutKeyBean {
        @Scheduled(config = BindingTimingConfig.class, period = 20)
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class BoxedBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.boxed")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class HugeBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.huge")
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class BoundAsyncBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", async = true)
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class LiteralAsyncBean {
        @Scheduled(period = 20, async = true)
        public void tick() {
            // Validation is what is asserted.
        }
    }

    public static class LiteralOnlyBean {
        @Scheduled(period = 20)
        public void tick() {
            // Validation is what is asserted.
        }
    }

    // === Fixtures: executors ===

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"boundwild"})
    static class BoundCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"classbound"})
    static class ClassLevelBoundCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"literalandkey"})
    static class LiteralAndKeyCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(value = 60, config = BindingTimingConfig.class, key = "cooldown.wild")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"undeclaredcd"})
    static class UndeclaredKeyCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "cooldown.nope")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"literalcd"})
    static class LiteralCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(5)
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"boxedcd"})
    static class BoxedCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "timer.boxed")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"hugecd"})
    static class HugeCooldownExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "timer.huge")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * One of two executors sharing a validator chain (WR-02). No {@code @CmdExecutor}: the
     * binding pass finds executors by type, and the annotation would pull this
     * constructor-injected fixture into another test's component scan.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class SharedChainWildExecutor extends BaseCommandExecutor {
        SharedChainWildExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    /** The other executor sharing the validator chain (WR-02). */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class SharedChainBoxedExecutor extends BaseCommandExecutor {
        SharedChainBoxedExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "timer.boxed")
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * Points the component scan at a package with no classes, so assembling this fixture sees only
     * the beans a test registers -- the default (this class's own package) would pick up every
     * other test's executor fixture in {@code manager}.
     */
    @UltiToolsModule(scanBasePackages = {"com.ultikits.testfixtures.configbinding531.empty"})
    abstract static class ModuleFixture extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        config = new BindingTimingConfig();
        configManager = mock(ConfigManager.class);
        module = mock(ModuleFixture.class);
        lenient().when(module.getPluginName()).thenReturn("TimingModule");
        lenient().when(module.getMinUltiToolsVersion()).thenReturn(630);
        lenient().when(configManager.getConfigEntities(module, BindingTimingConfig.class))
                .thenReturn(Collections.singletonList(config));

        ultiTools = mock(UltiTools.class);
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        lenient().when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, Answers.CALLS_REAL_METHODS));
        lenient().when(ultiTools.getConfigManager()).thenReturn(configManager);
        setUltiToolsInstance(ultiTools);

        logCapture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
                // Records are appended straight to the in-memory list.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Bukkit.getLogger().addHandler(logCapture);
    }

    @AfterEach
    void tearDown() throws Exception {
        Bukkit.getLogger().removeHandler(logCapture);
        setUltiToolsInstance(null);
        MockBukkitHelper.safeUnmock();
    }

    private static void setUltiToolsInstance(UltiTools instance) throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, instance);
    }

    private SimpleContainer containerWith(Object... beans) {
        SimpleContainer container = new SimpleContainer();
        int i = 0;
        for (Object bean : beans) {
            container.registerSingleton("bean" + i++, bean);
        }
        return container;
    }

    /** Runs the load-time pass and returns its refusal's message. */
    private String refusalOf(Object... beans) {
        SimpleContainer container = containerWith(beans);
        UltiToolsException refused = assertThrows(UltiToolsException.class,
                () -> PluginManager.validateConfigBindings(module, container));
        return refused.getMessage();
    }

    private static void assertMentions(String message, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(message.contains(fragment), "expected '" + fragment + "' in: " + message);
        }
    }

    private List<String> warnings() {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : logs) {
            if (Level.WARNING.equals(record.getLevel())) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    private static CooldownValidator cooldownValidatorOf(BaseCommandExecutor executor) {
        for (CommandValidator validator : executor.getValidatorChain().getValidators()) {
            if (validator instanceof CooldownValidator) {
                return (CooldownValidator) validator;
            }
        }
        throw new AssertionError("the default chain carries a CooldownValidator");
    }

    private static String boxedKey() throws NoSuchMethodException {
        return CooldownValidator.bindingKey(
                BoxedCooldownExecutor.class.getMethod("doGo", Player.class).getAnnotation(CmdCD.class));
    }

    private static String wildKey() throws NoSuchMethodException {
        return CooldownValidator.bindingKey(
                BoundCooldownExecutor.class.getMethod("doGo", Player.class).getAnnotation(CmdCD.class));
    }

    // === Load: valid ===

    @Nested
    @DisplayName("a valid binding loads")
    class ValidAtLoad {

        @Test
        @DisplayName("bound @Scheduled period and delay with valid values pass")
        void validScheduledBindingPasses() {
            assertDoesNotThrow(() -> PluginManager.validateConfigBindings(module, containerWith(new ValidScheduledBean())));
        }

        @Test
        @DisplayName("a bound @CmdCD is resolved into the executor's CooldownValidator at load")
        void boundCooldownIsResolvedIntoTheValidatorAtLoad() throws Exception {
            config.setWildCooldown(45);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();

            PluginManager.validateConfigBindings(module, containerWith(executor));

            assertEquals(Collections.singletonMap(wildKey(), 45), cooldownValidatorOf(executor).getBoundCooldownSeconds());
        }

        @Test
        @DisplayName("a class-level bound @CmdCD is resolved too")
        void classLevelBoundCooldownIsResolved() throws Exception {
            config.setWildCooldown(12);
            ClassLevelBoundCooldownExecutor executor = new ClassLevelBoundCooldownExecutor();

            PluginManager.validateConfigBindings(module, containerWith(executor));

            assertEquals(Collections.singletonMap(wildKey(), 12), cooldownValidatorOf(executor).getBoundCooldownSeconds());
        }

        @Test
        @DisplayName("a module with only literal usages never reads configuration")
        void literalOnlyModuleNeverReadsConfiguration() {
            LiteralCooldownExecutor executor = new LiteralCooldownExecutor();

            PluginManager.validateConfigBindings(module, containerWith(new LiteralOnlyBean(), executor));

            verifyNoInteractions(configManager);
            assertTrue(cooldownValidatorOf(executor).getBoundCooldownSeconds().isEmpty());
        }
    }

    // === Load: developer errors ===

    @Nested
    @DisplayName("a binding that names something that cannot work refuses the module at load")
    class ShapeRefusedAtLoad {

        @Test
        @DisplayName("a config class not registered for this module is refused")
        void configClassNotRegisteredIsRefused() {
            when(configManager.getConfigEntities(module, BindingTimingConfig.class)).thenReturn(Collections.emptyList());

            assertMentions(refusalOf(new ValidScheduledBean()), "TimingModule", "BindingTimingConfig", "not registered");
        }

        @Test
        @DisplayName("a config class registered more than once is refused")
        void configClassRegisteredMoreThanOnceIsRefused() {
            when(configManager.getConfigEntities(module, BindingTimingConfig.class))
                    .thenReturn(Arrays.asList(config, new BindingTimingConfig()));

            assertMentions(refusalOf(new ValidScheduledBean()), "BindingTimingConfig", "2 times");
        }

        @Test
        @DisplayName("a key that matches no @ConfigEntry path is refused")
        void undeclaredKeyIsRefused() {
            assertMentions(refusalOf(new UndeclaredKeyBean()), "UndeclaredKeyBean.tick", "timer.nope");
        }

        @Test
        @DisplayName("a field name without @ConfigEntry is not a key")
        void fieldWithoutConfigEntryIsNotAKey() {
            assertMentions(refusalOf(new FieldWithoutConfigEntryBean()), "notAnEntry");
        }

        @Test
        @DisplayName("a non-integral field is refused")
        void nonIntegralFieldIsRefused() {
            assertMentions(refusalOf(new DoubleFieldBean()), "timer.ratio", "double");
            assertMentions(refusalOf(new StringFieldBean()), "timer.name", "String");
        }

        @Test
        @DisplayName("a literal period together with periodKey is refused, naming both")
        void literalPeriodTogetherWithPeriodKeyIsRefused() {
            assertMentions(refusalOf(new LiteralPeriodAndKeyBean()), "period=6000", "timer.period");
        }

        @Test
        @DisplayName("a literal delay together with delayKey is refused, naming both")
        void literalDelayTogetherWithDelayKeyIsRefused() {
            assertMentions(refusalOf(new LiteralDelayAndKeyBean()), "delay=100", "timer.delay");
        }

        @Test
        @DisplayName("a key without a config class is refused")
        void keyWithoutConfigClassIsRefused() {
            assertMentions(refusalOf(new KeyWithoutConfigBean()), "KeyWithoutConfigBean.tick", "no config class");
        }

        @Test
        @DisplayName("a config class without any key is refused")
        void configClassWithoutAnyKeyIsRefused() {
            assertMentions(refusalOf(new ConfigWithoutKeyBean()), "ConfigWithoutKeyBean.tick", "periodKey");
        }

        @Test
        @DisplayName("a @CmdCD literal together with a key is refused, naming both")
        void cooldownLiteralTogetherWithKeyIsRefused() {
            assertMentions(refusalOf(new LiteralAndKeyCooldownExecutor()), "value=60", "cooldown.wild");
        }

        @Test
        @DisplayName("a bound async @Scheduled is refused at load, telling the author to bind a sync task")
        void boundAsyncScheduledIsRefused() {
            assertMentions(refusalOf(new BoundAsyncBean()), "BoundAsyncBean.tick", "async",
                    "runTaskAsynchronously");
        }

        @Test
        @DisplayName("a literal async @Scheduled still loads")
        void literalAsyncScheduledLoads() {
            assertDoesNotThrow(() -> PluginManager.validateConfigBindings(module, containerWith(new LiteralAsyncBean())));
        }

        @Test
        @DisplayName("a @CmdCD key that matches no @ConfigEntry path is refused")
        void cooldownUndeclaredKeyIsRefused() {
            assertMentions(refusalOf(new UndeclaredKeyCooldownExecutor()), "cooldown.nope");
        }
    }

    // === Load: operator errors ===

    @Nested
    @DisplayName("an invalid bound value refuses the module at load, naming key and value")
    class ValueRefusedAtLoad {

        @Test
        @DisplayName("a bound period of 0 is refused -- 0 does not mean off")
        void zeroPeriodIsRefused() {
            config.setPeriodSeconds(0);

            assertMentions(refusalOf(new ValidScheduledBean()), "TimingModule", "timer.period", "value 0");
        }

        @Test
        @DisplayName("a negative bound delay is refused")
        void negativeDelayIsRefused() {
            config.setDelaySeconds(-5L);

            assertMentions(refusalOf(new ValidScheduledBean()), "timer.delay", "value -5");
        }

        @Test
        @DisplayName("a null boxed bound value is refused")
        void nullBoxedValueIsRefused() {
            config.setBoxedSeconds(null);

            assertMentions(refusalOf(new BoxedBean()), "timer.boxed", "value null");
        }

        @Test
        @DisplayName("a bound value that overflows once converted to ticks is refused")
        void overflowingValueIsRefused() {
            config.setHugeSeconds(Long.MAX_VALUE);

            assertMentions(refusalOf(new HugeBean()), "timer.huge", "value " + Long.MAX_VALUE);
        }

        @Test
        @DisplayName("a negative bound @CmdCD is refused")
        void negativeCooldownIsRefused() {
            config.setWildCooldown(-1);

            assertMentions(refusalOf(new BoundCooldownExecutor()), "cooldown.wild", "value -1");
        }

        @Test
        @DisplayName("a null bound @CmdCD is refused")
        void nullCooldownIsRefused() {
            config.setBoxedSeconds(null);

            assertMentions(refusalOf(new BoxedCooldownExecutor()), "timer.boxed", "value null");
        }

        @Test
        @DisplayName("a bound @CmdCD above Integer.MAX_VALUE seconds is refused")
        void overflowingCooldownIsRefused() {
            config.setHugeSeconds(Integer.MAX_VALUE + 1L);

            assertMentions(refusalOf(new HugeCooldownExecutor()), "timer.huge", "value " + (Integer.MAX_VALUE + 1L));
        }

        @Test
        @DisplayName("a bound period at the ceiling is accepted; one second more is refused")
        void boundPeriodCeiling() {
            config.setHugeSeconds((long) (Integer.MAX_VALUE / 20));
            assertDoesNotThrow(() -> PluginManager.validateConfigBindings(module, containerWith(new HugeBean())));

            config.setHugeSeconds((long) (Integer.MAX_VALUE / 20) + 1L);
            assertMentions(refusalOf(new HugeBean()), "timer.huge", "value " + ((long) (Integer.MAX_VALUE / 20) + 1L));
        }
    }

    // === Maintainer ruling: a bound cooldown of 0 means no cooldown ===

    @Nested
    @DisplayName("a bound @CmdCD of 0 means no cooldown")
    class ZeroCooldownMeansNoCooldown {

        @Test
        @DisplayName("0 loads, and the resolved cooldown is 0")
        void zeroCooldownLoads() throws Exception {
            config.setWildCooldown(0);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();

            assertDoesNotThrow(() -> PluginManager.validateConfigBindings(module, containerWith(executor)));

            assertEquals(Integer.valueOf(0), cooldownValidatorOf(executor).getBoundCooldownSeconds().get(wildKey()));
        }

        @Test
        @DisplayName("0 on reload is applied, not kept out, and logs no WARNING")
        void zeroCooldownOnReloadIsApplied() throws Exception {
            config.setWildCooldown(60);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();
            SimpleContainer container = containerWith(executor);
            lenient().when(module.getContext()).thenReturn(container);
            PluginManager.validateConfigBindings(module, container);

            config.setWildCooldown(0);
            new PluginManager().applyReloadedConfigBindings(module);

            assertEquals(Integer.valueOf(0), cooldownValidatorOf(executor).getBoundCooldownSeconds().get(wildKey()));
            assertTrue(warnings().isEmpty(), warnings().toString());
        }
    }

    // === WR-03: the api-version floor ===

    @Nested
    @DisplayName("a module using a binding must declare api-version 630 or higher")
    class ApiVersionFloor {

        @Test
        @DisplayName("a bound @Scheduled in a module declaring api-version 620 is refused, naming module, binding and floor")
        void boundScheduledBelowTheFloorIsRefused() {
            when(module.getMinUltiToolsVersion()).thenReturn(620);

            assertMentions(refusalOf(new ValidScheduledBean()), "TimingModule", "ValidScheduledBean.tick",
                    "api-version", "620", "630");
        }

        @Test
        @DisplayName("a bound @CmdCD in a module declaring api-version 620 is refused")
        void boundCooldownBelowTheFloorIsRefused() {
            when(module.getMinUltiToolsVersion()).thenReturn(620);

            assertMentions(refusalOf(new BoundCooldownExecutor()), "TimingModule", "BoundCooldownExecutor",
                    "api-version", "630");
        }

        @Test
        @DisplayName("a literal-only module declaring api-version 620 still loads")
        void literalOnlyModuleBelowTheFloorLoads() {
            when(module.getMinUltiToolsVersion()).thenReturn(620);

            assertDoesNotThrow(() -> PluginManager.validateConfigBindings(module,
                    containerWith(new LiteralOnlyBean(), new LiteralCooldownExecutor())));
        }
    }

    // === WR-02: shared validators, completeness, dispatch-time miss ===

    @Nested
    @DisplayName("bound cooldowns in a shared validator chain")
    class SharedChains {

        @Test
        @DisplayName("two executors sharing one chain keep both bindings, at load and after a reload")
        void sharedChainKeepsBothBindings() throws Exception {
            config.setWildCooldown(60);
            config.setBoxedSeconds(15);
            CooldownValidator shared = new CooldownValidator();
            ValidatorChain chain = ValidatorChain.builder().add(shared).build();
            SharedChainWildExecutor wild = new SharedChainWildExecutor(chain);
            SharedChainBoxedExecutor boxed = new SharedChainBoxedExecutor(chain);
            SimpleContainer container = containerWith(wild, boxed);
            lenient().when(module.getContext()).thenReturn(container);

            PluginManager.validateConfigBindings(module, container);

            assertEquals(Integer.valueOf(60), shared.getBoundCooldownSeconds().get(wildKey()));
            assertEquals(Integer.valueOf(15), shared.getBoundCooldownSeconds().get(boxedKey()));

            config.setWildCooldown(30);
            config.setBoxedSeconds(20);
            new PluginManager().applyReloadedConfigBindings(module);

            assertEquals(Integer.valueOf(30), shared.getBoundCooldownSeconds().get(wildKey()));
            assertEquals(Integer.valueOf(20), shared.getBoundCooldownSeconds().get(boxedKey()));
        }

        @Test
        @DisplayName("a bound declaration its validator does not hold after load refuses the module, naming the key")
        void anUnresolvedDeclarationAfterLoadRefusesTheModule() {
            CooldownValidator forgetful = new CooldownValidator() {
                @Override
                public Map<String, Integer> getBoundCooldownSeconds() {
                    return Collections.emptyMap();
                }
            };
            SharedChainWildExecutor executor = new SharedChainWildExecutor(
                    ValidatorChain.builder().add(forgetful).build());

            assertMentions(refusalOf(executor), "SharedChainWildExecutor", "cooldown.wild");
        }
    }

    // === IN-01 round 2: sources are owned per module ===

    @Nested
    @DisplayName("a validator shared across modules re-reads only the reloading module's config")
    class SourcesOwnedPerModule {

        @Test
        @DisplayName("reloading module A never re-reads module B's field, so B's refused value never takes effect")
        void reloadingOneModuleDoesNotReadAnotherModulesConfig() throws Exception {
            UltiToolsPlugin moduleB = mock(ModuleFixture.class);
            lenient().when(moduleB.getPluginName()).thenReturn("OtherModule");
            lenient().when(moduleB.getMinUltiToolsVersion()).thenReturn(630);
            BindingTimingConfig configB = new BindingTimingConfig();
            configB.setBoxedSeconds(15);
            lenient().when(configManager.getConfigEntities(moduleB, BindingTimingConfig.class))
                    .thenReturn(Collections.singletonList(configB));
            config.setWildCooldown(60);

            CooldownValidator shared = new CooldownValidator();
            ValidatorChain chain = ValidatorChain.builder().add(shared).build();
            SimpleContainer containerA = containerWith(new SharedChainWildExecutor(chain));
            SimpleContainer containerB = containerWith(new SharedChainBoxedExecutor(chain));
            lenient().when(module.getContext()).thenReturn(containerA);
            lenient().when(moduleB.getContext()).thenReturn(containerB);
            PluginManager.validateConfigBindings(module, containerA);
            PluginManager.validateConfigBindings(moduleB, containerB);

            // B's own reload was refused by validateFields(): the refused 5 is left in B's field and
            // B's binding step never ran. A later reload of A must not pick it up.
            configB.setBoxedSeconds(5);
            config.setWildCooldown(30);
            new PluginManager().applyReloadedConfigBindings(module);

            assertEquals(Integer.valueOf(30), shared.getBoundCooldownSeconds().get(wildKey()), "A's own value applies");
            assertEquals(Integer.valueOf(15), shared.getBoundCooldownSeconds().get(boxedKey()),
                    "B's refused value must not be applied by A's reload");
        }
    }

    // === IN-01 / IN-02: reload-step robustness ===

    @Nested
    @DisplayName("the reload step is isolated per module and runs only on the main thread")
    class ReloadStepRobustness {

        @Test
        @DisplayName("a failing timer half is logged against the module and the cooldown half still runs")
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes") // the injected failure IS the test
        void aFailingTimerHalfIsIsolated() throws Exception {
            config.setWildCooldown(60);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();
            SimpleContainer container = containerWith(executor);
            lenient().when(module.getContext()).thenReturn(container);
            PluginManager.validateConfigBindings(module, container);
            PluginManager pluginManager = new PluginManager();
            TaskManager taskManager = mock(TaskManager.class);
            doThrow(new RuntimeException("simulated reschedule failure")).when(taskManager).rescheduleBound(module);
            Field field = PluginManager.class.getDeclaredField("taskManager");
            field.setAccessible(true);
            field.set(pluginManager, taskManager);

            config.setWildCooldown(30);
            assertDoesNotThrow(() -> pluginManager.applyReloadedConfigBindings(module));

            assertEquals(Integer.valueOf(30), cooldownValidatorOf(executor).getBoundCooldownSeconds().get(wildKey()));
            assertTrue(warnings().stream().anyMatch(w -> w.contains("TimingModule") && w.contains("simulated reschedule failure")),
                    warnings().toString());
        }

        @Test
        @DisplayName("a reload re-reads the load-time config instance, not the registry, so a registry change cannot break it")
        void reloadUsesTheLoadTimeInstance() throws Exception {
            config.setWildCooldown(60);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();
            SimpleContainer container = containerWith(executor);
            lenient().when(module.getContext()).thenReturn(container);
            PluginManager.validateConfigBindings(module, container);
            when(configManager.getConfigEntities(module, BindingTimingConfig.class))
                    .thenReturn(Arrays.asList(config, new BindingTimingConfig()));

            config.setWildCooldown(45);
            assertDoesNotThrow(() -> new PluginManager().applyReloadedConfigBindings(module));

            assertEquals(Integer.valueOf(45), cooldownValidatorOf(executor).getBoundCooldownSeconds().get(wildKey()));
            assertTrue(warnings().isEmpty(), warnings().toString());
        }

        @Test
        @DisplayName("a reload step called off the main thread touches nothing and logs a WARNING")
        void offMainThreadTouchesNothing() throws Exception {
            config.setWildCooldown(60);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();
            SimpleContainer container = containerWith(executor);
            lenient().when(module.getContext()).thenReturn(container);
            PluginManager.validateConfigBindings(module, container);
            PluginManager pluginManager = new PluginManager();
            TaskManager taskManager = mock(TaskManager.class);
            Field field = PluginManager.class.getDeclaredField("taskManager");
            field.setAccessible(true);
            field.set(pluginManager, taskManager);
            config.setWildCooldown(30);

            Thread worker = new Thread(() -> pluginManager.applyReloadedConfigBindings(module), "fw531-off-main");
            worker.start();
            worker.join(10_000L);

            assertEquals(Integer.valueOf(60), cooldownValidatorOf(executor).getBoundCooldownSeconds().get(wildKey()));
            verifyNoInteractions(taskManager);
            assertTrue(warnings().stream().anyMatch(w -> w.contains("TimingModule") && w.contains("main thread")),
                    warnings().toString());
        }
    }

    // === Load: granularity ===

    @Nested
    @DisplayName("a refusal is module-granular")
    class ModuleGranularity {

        @Test
        @DisplayName("the offending module fails assembly while a sibling module's assembly still completes")
        void offendingModuleFailsAssemblyWhileSiblingStillCompletes() throws Exception {
            PluginManager pluginManager = new PluginManager();
            Method assemble = PluginManager.class.getDeclaredMethod("assemblePluginContainer",
                    SimpleContainer.class, UltiToolsPlugin.class, Class.class, ClassLoader.class);
            assemble.setAccessible(true);
            ClassLoader loader = getClass().getClassLoader();

            config.setPeriodSeconds(0);
            SimpleContainer badContainer = containerWith(new ValidScheduledBean());
            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> assemble.invoke(pluginManager, badContainer, module, ModuleFixture.class, loader));
            assertTrue(wrapped.getCause() instanceof UltiToolsException, String.valueOf(wrapped.getCause()));
            assertMentions(wrapped.getCause().getMessage(), "timer.period", "value 0");

            UltiToolsPlugin sibling = mock(ModuleFixture.class);
            when(sibling.getPluginName()).thenReturn("SiblingModule");
            SimpleContainer goodContainer = containerWith(new LiteralOnlyBean(), new LiteralCooldownExecutor());
            assertDoesNotThrow(() -> assemble.invoke(pluginManager, goodContainer, sibling, ModuleFixture.class, loader));
            assertTrue(goodContainer.getBeanNamesForType(CommandExecutor.class).length > 0);
        }
    }

    // === Outside modules ===

    @Nested
    @DisplayName("an external plugin cannot use a binding")
    class OutsideModules {

        @Test
        @DisplayName("a bound @Scheduled in an external plugin's container is refused")
        void boundScheduledInExternalContainerIsRefused() {
            SimpleContainer container = containerWith(new ValidScheduledBean());

            UltiToolsException refused = assertThrows(UltiToolsException.class,
                    () -> PluginManager.refuseConfigBindingsOutsideModules(container));
            assertMentions(refused.getMessage(), "ValidScheduledBean.tick");
        }

        @Test
        @DisplayName("a bound @CmdCD in an external plugin's container is refused")
        void boundCooldownInExternalContainerIsRefused() {
            SimpleContainer container = containerWith(new BoundCooldownExecutor());

            UltiToolsException refused = assertThrows(UltiToolsException.class,
                    () -> PluginManager.refuseConfigBindingsOutsideModules(container));
            assertMentions(refused.getMessage(), "BoundCooldownExecutor");
        }

        @Test
        @DisplayName("literal usages in an external plugin's container are accepted")
        void literalUsagesInExternalContainerAreAccepted() {
            SimpleContainer container = containerWith(new LiteralOnlyBean(), new LiteralCooldownExecutor());

            assertDoesNotThrow(() -> PluginManager.refuseConfigBindingsOutsideModules(container));
        }
    }

    // === Reload ===

    @Nested
    @DisplayName("/ul reload refreshes cached @CmdCD values and hands @Scheduled to the TaskManager")
    class Reload {

        private BoundCooldownExecutor loadedExecutor() {
            config.setWildCooldown(60);
            BoundCooldownExecutor executor = new BoundCooldownExecutor();
            SimpleContainer container = containerWith(executor);
            lenient().when(module.getContext()).thenReturn(container);
            PluginManager.validateConfigBindings(module, container);
            return executor;
        }

        @Test
        @DisplayName("a changed field is not seen until the reload step runs, then it is")
        void aBoundCooldownIsRefreshedOnlyByTheReloadStep() throws Exception {
            BoundCooldownExecutor executor = loadedExecutor();
            CooldownValidator validator = cooldownValidatorOf(executor);

            config.setWildCooldown(30);
            assertEquals(Integer.valueOf(60), validator.getBoundCooldownSeconds().get(wildKey()),
                    "the validator must not read the field per call -- a refused reload leaves the refused value in it");

            new PluginManager().applyReloadedConfigBindings(module);

            assertEquals(Integer.valueOf(30), validator.getBoundCooldownSeconds().get(wildKey()));
        }

        @Test
        @DisplayName("an invalid cooldown on reload keeps the running value and logs one WARNING")
        void anInvalidCooldownOnReloadKeepsTheRunningValueAndWarns() throws Exception {
            BoundCooldownExecutor executor = loadedExecutor();

            config.setWildCooldown(-1);
            new PluginManager().applyReloadedConfigBindings(module);

            assertEquals(Integer.valueOf(60), cooldownValidatorOf(executor).getBoundCooldownSeconds().get(wildKey()));
            List<String> warnings = warnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertMentions(warnings.get(0), "TimingModule", "cooldown.wild", "value -1", "keeping 60s");
        }

        @Test
        @DisplayName("the reload step asks the TaskManager to reschedule this module's bound tasks")
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertion IS the Mockito verify(...)
        void theReloadStepReschedulesThroughTheTaskManager() throws Exception {
            lenient().when(module.getContext()).thenReturn(new SimpleContainer());
            PluginManager pluginManager = new PluginManager();
            TaskManager taskManager = mock(TaskManager.class);
            Field field = PluginManager.class.getDeclaredField("taskManager");
            field.setAccessible(true);
            field.set(pluginManager, taskManager);

            pluginManager.applyReloadedConfigBindings(module);

            verify(taskManager).rescheduleBound(module);
        }

        @Test
        @DisplayName("an unbound executor's validator is left untouched by a reload")
        void anUnboundExecutorIsLeftUntouched() {
            LiteralCooldownExecutor executor = new LiteralCooldownExecutor();
            SimpleContainer container = containerWith(executor);
            lenient().when(module.getContext()).thenReturn(container);
            PluginManager.validateConfigBindings(module, container);
            Map<String, Integer> before = cooldownValidatorOf(executor).getBoundCooldownSeconds();

            new PluginManager().applyReloadedConfigBindings(module);

            assertEquals(before, cooldownValidatorOf(executor).getBoundCooldownSeconds());
            verifyNoInteractions(configManager);
        }
    }
}
