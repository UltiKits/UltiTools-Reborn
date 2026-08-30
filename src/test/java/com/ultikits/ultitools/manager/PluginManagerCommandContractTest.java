package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Answers;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * SILENT-11 half 2 (D-01, D-04, 05-02): a {@code @CmdCD}/{@code @UsageLimit} declaration whose
 * validator chain holds no matching validator is refused at plugin load, naming the offending
 * class and (when known) the offending mapping method -- rather than silently loading with the
 * annotation unenforced, which is exactly what plan 05-01 left open as SILENT-11's second half.
 * <p>
 * Drives {@link PluginManager#validateCommandExecutorContract(BaseCommandExecutor)} and
 * {@link PluginManager#validateCommandExecutorContracts(SimpleContainer)} directly -- both are
 * package-private for exactly this reason, mirroring the test-seam rationale already used for
 * {@code PluginManager.logPluginInitializationFailure}.
 */
@DisplayName("PluginManager command-executor contract enforcement (SILENT-11 / D-01 half 2, D-04)")
class PluginManagerCommandContractTest {

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ==================== Fixtures ====================

    /**
     * Test 1 fixture: a {@code @CmdCD}-annotated mapping, constructed with a chain that
     * deliberately omits {@code CooldownValidator}.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"missingcooldown"})
    static class MissingCooldownValidatorExecutor extends BaseCommandExecutor {
        MissingCooldownValidatorExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "ping")
        @CmdCD(5)
        public void doPing(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * Test 2 fixture: a {@code @UsageLimit}-annotated mapping, constructed with a chain that
     * deliberately omits {@code UsageLockValidator}.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"missinglock"})
    static class MissingUsageLockValidatorExecutor extends BaseCommandExecutor {
        MissingUsageLockValidatorExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @UsageLimit(UsageLimit.LimitType.SENDER)
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * Test 3 fixture: default chain (carries both {@code CooldownValidator} and
     * {@code UsageLockValidator} per {@code createDefaultValidatorChain()}), with both
     * annotations present. Must register without throwing.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"validdefault"})
    static class ValidDefaultChainExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "ping")
        @CmdCD(5)
        public void doPing(Player player) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @UsageLimit(UsageLimit.LimitType.SENDER)
        public void doGo(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * Test 4 fixture: an empty chain (missing both validators) but no cooldown/usage-limit
     * annotation anywhere -- the check must be driven by the DECLARED annotation, not by chain
     * contents alone, so this must register without throwing.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"noannotation"})
    static class NoAnnotationEmptyChainExecutor extends BaseCommandExecutor {
        NoAnnotationEmptyChainExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "ping")
        public void doPing(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * Test 6 fixture: {@code @CmdCD} declared at the CLASS level (D-01's "a class or method"),
     * rather than on any {@code @CmdMapping} method, with a chain that omits
     * {@code CooldownValidator}.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"classlevelcooldown"})
    @CmdCD(5)
    static class ClassLevelCooldownExecutor extends BaseCommandExecutor {
        ClassLevelCooldownExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "ping")
        public void doPing(Player player) {
            // Test stub - not exercised
        }
    }

    /**
     * Module fixture used by the module-granularity tests (Test 5) -- {@code @ComponentScan} +
     * {@code @EnableAutoRegister} directly, mirroring {@code PluginManagerAutoRegisterAliasTest}'s
     * fixtures, rather than {@code @UltiToolsModule} (whose aliased, empty {@code scanBasePackages()}
     * could otherwise merge unpredictably with a direct {@code @ComponentScan} on the same class).
     * The scan package has no classes, so {@code assemblePluginContainer}'s own
     * {@code scanComponents} call is a harmless no-op -- the fixture executor is placed into each
     * test's container directly via {@code registerSingleton} instead.
     */
    @ComponentScan(basePackages = "com.ultikits.testfixtures.commandcontractempty")
    @EnableAutoRegister
    abstract static class ModuleFixture extends UltiToolsPlugin {
    }

    private ValidatorChain emptyChain() {
        return ValidatorChain.builder().build();
    }

    // ==================== Task 1, Test 1 ====================

    @Nested
    @DisplayName("Test 1: @CmdCD without CooldownValidator")
    class CmdCdWithoutCooldownValidator {

        @Test
        @DisplayName("is refused at load, naming the class and the method")
        void refusedNamingClassAndMethod() {
            MissingCooldownValidatorExecutor executor = new MissingCooldownValidatorExecutor(emptyChain());

            PluginModuleException thrown = assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContract(executor));

            assertEquals(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE, thrown.getErrorCode());
            assertTrue(thrown.getMessage().contains(MissingCooldownValidatorExecutor.class.getName()),
                    "message must name the offending class");
            assertTrue(thrown.getMessage().contains("doPing"),
                    "message must name the offending method");
        }
    }

    // ==================== Task 1, Test 2 ====================

    @Nested
    @DisplayName("Test 2: @UsageLimit without UsageLockValidator")
    class UsageLimitWithoutUsageLockValidator {

        @Test
        @DisplayName("is refused at load, naming the class and the method")
        void refusedNamingClassAndMethod() {
            MissingUsageLockValidatorExecutor executor = new MissingUsageLockValidatorExecutor(emptyChain());

            PluginModuleException thrown = assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContract(executor));

            assertEquals(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE, thrown.getErrorCode());
            assertTrue(thrown.getMessage().contains(MissingUsageLockValidatorExecutor.class.getName()),
                    "message must name the offending class");
            assertTrue(thrown.getMessage().contains("doGo"),
                    "message must name the offending method");
        }
    }

    // ==================== Task 1, Test 3 ====================

    @Test
    @DisplayName("Test 3: default chain with both annotations present registers without throwing")
    void defaultChainWithBothAnnotationsRegistersWithoutThrowing() {
        ValidDefaultChainExecutor executor = new ValidDefaultChainExecutor();

        assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
    }

    // ==================== Task 1, Test 4 ====================

    @Test
    @DisplayName("Test 4: empty chain with no cooldown/usage-limit annotation registers without throwing")
    void emptyChainWithNoAnnotationRegistersWithoutThrowing() {
        NoAnnotationEmptyChainExecutor executor = new NoAnnotationEmptyChainExecutor(emptyChain());

        assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
    }

    // ==================== Task 1, Test 6 ====================

    @Nested
    @DisplayName("Test 6: class-level @CmdCD")
    class ClassLevelCmdCd {

        @Test
        @DisplayName("triggers the same refusal as a method-level declaration")
        void refusedSameAsMethodLevel() {
            ClassLevelCooldownExecutor executor = new ClassLevelCooldownExecutor(emptyChain());

            PluginModuleException thrown = assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContract(executor));

            assertEquals(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE, thrown.getErrorCode());
            assertTrue(thrown.getMessage().contains(ClassLevelCooldownExecutor.class.getName()),
                    "message must name the offending class");
        }

        @Test
        @DisplayName("a class-level declaration with the required validator present registers without throwing")
        void classLevelWithValidatorPresentDoesNotThrow() {
            ClassLevelCooldownExecutor executor = new ClassLevelCooldownExecutor(
                    ValidatorChain.builder().add(new CooldownValidator()).build());

            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
        }
    }

    // ==================== Task 1, Test 5 (module granularity) ====================

    @Nested
    @DisplayName("Test 5: module granularity (D-04's no-opt-out escape hatch)")
    class ModuleGranularity {

        @Test
        @DisplayName("one container's refusal does not prevent a separate container from validating cleanly")
        void oneContainerFailingDoesNotBlockAnother() {
            SimpleContainer badContainer = new SimpleContainer();
            badContainer.registerSingleton("bad", new MissingCooldownValidatorExecutor(emptyChain()));

            SimpleContainer goodContainer = new SimpleContainer();
            goodContainer.registerSingleton("good", new ValidDefaultChainExecutor());

            assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContracts(badContainer));

            // The good container is validated in a completely separate call -- if the bad
            // container's refusal had corrupted any shared state, this would also throw.
            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContracts(goodContainer));
        }

        /**
         * Exercises {@code assemblePluginContainer} directly (via reflection) -- the actual
         * integration point both {@code register(Class)} and {@code register(UltiToolsPlugin)}
         * funnel through (WIRE-05/WIRE-06) and where {@code validateCommandExecutorContracts} is
         * hooked, right before {@code registerBukkit} would hand the module's commands to Bukkit.
         * Its own existing try/catch at every {@code assemblePluginContainer} call site is what
         * makes THIS refusal module-granular for free -- this test proves that guarantee holds
         * for the new check, not just that the check itself throws.
         */
        @Test
        @DisplayName("the offending module fails assembly while a sibling module's assembly still completes")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void offendingModuleFailsAssemblyWhileSiblingModuleStillCompletes() throws Exception {
            UltiTools ultiTools = mock(UltiTools.class);
            DependenceManagers dependenceManagers = mock(DependenceManagers.class);
            lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
            lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
            DataStore dataStore = mock(DataStore.class, Answers.CALLS_REAL_METHODS);
            lenient().when(ultiTools.getDataStore()).thenReturn(dataStore);
            lenient().when(ultiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));

            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, ultiTools);
            try {
                PluginManager pluginManager = new PluginManager();

                Method assemble = PluginManager.class.getDeclaredMethod("assemblePluginContainer",
                        SimpleContainer.class, UltiToolsPlugin.class, Class.class, ClassLoader.class);
                assemble.setAccessible(true);
                ClassLoader loader = getClass().getClassLoader();

                UltiToolsPlugin badPlugin = mock(ModuleFixture.class);
                when(badPlugin.getPluginName()).thenReturn("bad-module");
                SimpleContainer badContainer = new SimpleContainer();
                badContainer.registerSingleton("bad", new MissingCooldownValidatorExecutor(emptyChain()));

                InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                        () -> assemble.invoke(pluginManager, badContainer, badPlugin, ModuleFixture.class, loader));
                assertTrue(wrapped.getCause() instanceof PluginModuleException,
                        "the refusal must propagate out of assemblePluginContainer as a PluginModuleException");

                UltiToolsPlugin goodPlugin = mock(ModuleFixture.class);
                when(goodPlugin.getPluginName()).thenReturn("good-module");
                SimpleContainer goodContainer = new SimpleContainer();
                goodContainer.registerSingleton("good", new ValidDefaultChainExecutor());

                assertDoesNotThrow(
                        () -> assemble.invoke(pluginManager, goodContainer, goodPlugin, ModuleFixture.class, loader));
                assertTrue(Arrays.asList(goodContainer.getBeanNamesForType(CommandExecutor.class)).contains("good"),
                        "the surviving module's command executor bean must still be present after assembly");
            } finally {
                instanceField.set(null, null);
            }
        }
    }
}
