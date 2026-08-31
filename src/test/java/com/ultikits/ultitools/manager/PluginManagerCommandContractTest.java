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
import java.util.Collections;
import java.util.List;

import org.bukkit.command.Command;
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
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ReflectionUtil;

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

    /**
     * WR-02 (05-REVIEW.md) fixtures: an abstract executor SUPERCLASS declaring the
     * {@code @CmdMapping} method, and a concrete SUBCLASS that inherits it without overriding --
     * so {@code Method#getDeclaringClass()} for {@code doPing} is always {@code
     * Wr02SharedMappingBase}, never the concrete subclass below, regardless of which subclass
     * dispatches. {@code @CmdCD} is declared ONLY on the concrete subclass -- exactly the WR-02
     * broken case: {@code PluginManager}'s load-time gate checks {@code executor.getClass()}
     * (the concrete subclass, where {@code @CmdCD} lives) and correctly refuses when the
     * validator is missing, but the pre-fix runtime resolution only ever checked {@code
     * method.getDeclaringClass()} (the superclass, which carries no {@code @CmdCD}) and so never
     * enforced it once the validator WAS present.
     */
    abstract static class Wr02SharedMappingBase extends BaseCommandExecutor {
        Wr02SharedMappingBase(ValidatorChain chain) {
            super(chain);
        }

        @CmdMapping(format = "ping")
        public void doPing(Player player) {
            // Test stub - not exercised
        }
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"wr02concretesubclassonly"})
    @CmdCD(20)
    static class Wr02ConcreteSubclassOnlyCooldownExecutor extends Wr02SharedMappingBase {
        Wr02ConcreteSubclassOnlyCooldownExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }
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

    // ==================== 05-06 Task 2: unknown @CmdParam.suggest key refused at load ====================

    /**
     * Fixture: {@code @CmdParam(suggest = "@nosuchkey")} -- no completer is registered under
     * this key, so the module must be refused at load (D-07).
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"unknownkey"})
    static class UnknownSuggestKeyExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(Player player,
                                 @CmdParam(value = "target", suggest = "@nosuchkey") String target) {
            // Test stub - not exercised
        }
    }

    /**
     * Fixture: {@code @CmdParam(suggest = "@players")} -- a built-in key, always registered.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"knownkey"})
    static class KnownSuggestKeyExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(Player player,
                                 @CmdParam(value = "target", suggest = "@players") String target) {
            // Test stub - not exercised
        }
    }

    /**
     * Fixture: plain method-name notation naming a method that does not exist -- keeps the
     * published i18n hint-text fallback and must NOT be refused (D-07 leaves this fallback
     * deliberately unchanged; out of this check's scope entirely).
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"methodname"})
    static class MethodNameSuggestExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(Player player,
                                 @CmdParam(value = "target", suggest = "suggestDoesNotExist") String target) {
            // Test stub - not exercised
        }
    }

    /**
     * Fixture: a key registered at runtime BEFORE this executor's own container is validated --
     * must be treated as known.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"runtimekey"})
    static class RuntimeKeySuggestExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(Player player,
                                 @CmdParam(value = "target", suggest = "@fixtureRuntimeKey") String target) {
            // Test stub - not exercised
        }
    }

    /**
     * Fixture: a {@code @PostConstruct} bean that registers its own completer key -- simulates a
     * module registering a completer during its own load (i.e. during {@code
     * pluginContext.refresh()}), which {@link SimpleContainer#registerSingleton(String, Object)}
     * invokes synchronously (see its own javadoc), the same as {@code refresh()} does for scanned
     * beans.
     */
    static class SelfRegisteringCompleterService {
        @PostConstruct
        public void registerCompleter() {
            TabCompletionManager.getInstance().register(
                    "@fixtureSelfRegisteredKey", ctx -> Collections.emptyList());
        }
    }

    /**
     * Fixture: uses the key {@link SelfRegisteringCompleterService} registers -- must NOT be
     * refused when both beans are in the same container (the ordering this task pins).
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"selfkey"})
    static class SelfKeySuggestExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(Player player,
                                 @CmdParam(value = "target", suggest = "@fixtureSelfRegisteredKey") String target) {
            // Test stub - not exercised
        }
    }

    @Nested
    @DisplayName("Task 2 (05-06): @CmdParam.suggest \"@key\" refused at load when unknown (D-07)")
    class UnknownSuggestKeyTests {

        @Test
        @DisplayName("Test 1: an unknown @key refuses the module, naming class, method and key")
        void unknownKeyRefusesLoad() {
            UnknownSuggestKeyExecutor executor = new UnknownSuggestKeyExecutor();

            PluginModuleException exception = assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContract(executor));

            assertTrue(exception.getMessage().contains(UnknownSuggestKeyExecutor.class.getName()));
            assertTrue(exception.getMessage().contains("giveCommand"));
            assertTrue(exception.getMessage().contains("@nosuchkey"));
        }

        @Test
        @DisplayName("Test 2: a known built-in key loads without incident")
        void knownKeyLoadsWithoutIncident() {
            KnownSuggestKeyExecutor executor = new KnownSuggestKeyExecutor();

            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
        }

        @Test
        @DisplayName("Test 3: method-name notation naming a non-existent method loads -- keeps the i18n hint fallback, never refused")
        void methodNameNotationNeverRefused() {
            MethodNameSuggestExecutor executor = new MethodNameSuggestExecutor();

            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
        }

        @Test
        @DisplayName("Test 4: one module's unknown-key refusal does not prevent a sibling module's container from validating cleanly")
        void oneModuleFailingDoesNotBlockAnother() {
            SimpleContainer badContainer = new SimpleContainer();
            badContainer.registerSingleton("bad", new UnknownSuggestKeyExecutor());

            SimpleContainer goodContainer = new SimpleContainer();
            goodContainer.registerSingleton("good", new KnownSuggestKeyExecutor());

            assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContracts(badContainer));
            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContracts(goodContainer));
        }

        @Test
        @DisplayName("Test 5: a key registered at runtime before the module loads is treated as known")
        void runtimeRegisteredKeyTreatedAsKnown() {
            TabCompletionManager.getInstance().register(
                    "@fixtureRuntimeKey", ctx -> Collections.emptyList());
            try {
                RuntimeKeySuggestExecutor executor = new RuntimeKeySuggestExecutor();

                assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
            } finally {
                TabCompletionManager.getInstance().unregister("@fixtureRuntimeKey");
            }
        }

        @Test
        @DisplayName("Test 6: a module that registers its own completer key during its own load, and uses it, is not refused")
        void moduleRegisteringOwnKeyDuringOwnLoadIsNotRefused() {
            // SimpleContainer.registerSingleton invokes @PostConstruct synchronously (its own
            // javadoc) -- registering the self-registering service into the SAME container
            // before the executor mirrors pluginContext.refresh()'s real ordering: every bean's
            // @PostConstruct runs during refresh(), and validateCommandExecutorContracts is only
            // ever called strictly AFTER refresh() completes (assemblePluginContainer). This test
            // pins that ordering explicitly rather than relying on it silently.
            try {
                SimpleContainer container = new SimpleContainer();
                container.registerSingleton("selfRegisteringService", new SelfRegisteringCompleterService());
                container.registerSingleton("executor", new SelfKeySuggestExecutor());

                assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContracts(container));
            } finally {
                TabCompletionManager.getInstance().unregister("@fixtureSelfRegisteredKey");
            }
        }
    }

    /**
     * WR-02 (05-REVIEW.md): pairs {@code PluginManager.validateCommandExecutorContract} (the
     * load-time gate) with {@code ReflectionUtil.resolveMethodOrClassAnnotation} (the runtime
     * resolution {@code CooldownValidator}/{@code UsageLockValidator} call) against the SAME
     * fixture, on the SAME production methods, so "the gate accepts this" and "the runtime
     * enforces this" are proven to be the same fact -- not inferred from two separately-tested
     * components that happen to agree today. {@link CooldownValidatorTest} and {@link
     * UsageLockValidatorTest} already cover the full four-combination matrix end-to-end through
     * the validators themselves; this class is the narrower, explicit tie between the two
     * production entry points the review's finding is actually about.
     */
    @Nested
    @DisplayName("WR-02: load-time gate and runtime resolution agree (post-review gap closure)")
    class GateAndRuntimeResolutionAgreement {

        @Test
        @DisplayName("Gate refuses a concrete-subclass-only @CmdCD when the validator is missing")
        void gateRefusesWhenValidatorMissing() {
            Wr02ConcreteSubclassOnlyCooldownExecutor executor =
                    new Wr02ConcreteSubclassOnlyCooldownExecutor(emptyChain());

            PluginModuleException exception = assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContract(executor));
            assertTrue(exception.getMessage().contains("Wr02ConcreteSubclassOnlyCooldownExecutor"));
            assertTrue(exception.getMessage().contains("CmdCD"));
        }

        @Test
        @DisplayName("Gate allows a concrete-subclass-only @CmdCD when the validator IS present, "
                + "and the SAME production resolution call the validator uses finds that SAME annotation")
        void gateAllowsWhenValidatorPresent_andRuntimeResolvesTheSameAnnotation() throws Exception {
            ValidatorChain chainWithCooldown = ValidatorChain.builder()
                    .add(new CooldownValidator())
                    .build();
            Wr02ConcreteSubclassOnlyCooldownExecutor executor =
                    new Wr02ConcreteSubclassOnlyCooldownExecutor(chainWithCooldown);

            // The gate: does not refuse, because CooldownValidator IS in the chain.
            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));

            // The runtime: the SAME resolution primitive CooldownValidator.getCooldownSeconds
            // calls internally, given the SAME (method, concrete executor class) pair the gate
            // just accepted. Before WR-02's fix, this returned null -- the gate's "fine to load"
            // was a false assurance because nothing would actually enforce the declaration.
            Method method = executor.getClass().getMethod("doPing", Player.class);
            CmdCD resolved = ReflectionUtil.resolveMethodOrClassAnnotation(method, executor.getClass(), CmdCD.class);

            assertTrue(resolved != null && resolved.value() == 20,
                    "Expected the concrete subclass's @CmdCD(20) to be resolvable via the SAME "
                            + "(method, executorClass) pair the load-time gate just accepted, but "
                            + "resolution returned: " + resolved);
        }
    }

    // ==================== UAT Fix (05-fix) Part 2: uninvocable suggest-method signature ====================

    /**
     * Fixture: {@code @CmdParam(suggest = "suggestBad")} names a method that DOES exist on this
     * class, but whose signature -- {@code (int)} -- is not one of the five shapes {@code
     * MethodInvocationCompleter} knows how to invoke. Before this fix, this loaded cleanly and
     * threw {@code IllegalArgumentException} only the first time a player pressed Tab (the
     * real-machine-UAT regression); this fixture pins the load-time refusal that replaces it.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"uninvocablesig"})
    static class UninvocableSuggestSignatureExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "give <target>")
        public void giveCommand(Player player,
                                 @CmdParam(value = "target", suggest = "suggestBad") String target) {
            // Test stub - not exercised
        }

        public List<String> suggestBad(int notSupported) {
            return Collections.emptyList();
        }
    }

    /**
     * Fixture: one mapping per invocable signature shape -- the positive counterpart to {@link
     * UninvocableSuggestSignatureExecutor}, pinning that the new check does not over-refuse a
     * module using any of the five shapes {@code MethodInvocationCompleter} actually supports.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"allshapes"})
    static class AllInvocableSuggestSignatureShapesExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "zero <target>")
        public void zeroCommand(Player player,
                                 @CmdParam(value = "target", suggest = "suggestZero") String target) {
            // Test stub - not exercised
        }

        public List<String> suggestZero() {
            return Collections.emptyList();
        }

        @CmdMapping(format = "playeronly <target>")
        public void playerOnlyCommand(Player player,
                                       @CmdParam(value = "target", suggest = "suggestPlayerOnly") String target) {
            // Test stub - not exercised
        }

        public List<String> suggestPlayerOnly(Player p) {
            return Collections.emptyList();
        }

        @CmdMapping(format = "stringonly <target>")
        public void stringOnlyCommand(Player player,
                                       @CmdParam(value = "target", suggest = "suggestStringOnly") String target) {
            // Test stub - not exercised
        }

        public List<String> suggestStringOnly(String s) {
            return Collections.emptyList();
        }

        @CmdMapping(format = "playerstring <target>")
        public void playerStringCommand(Player player,
                                         @CmdParam(value = "target", suggest = "suggestPlayerString") String target) {
            // Test stub - not exercised
        }

        public List<String> suggestPlayerString(Player p, String s) {
            return Collections.emptyList();
        }

        @CmdMapping(format = "playercmdargs <target>")
        public void playerCmdArgsCommand(Player player,
                @CmdParam(value = "target", suggest = "suggestPlayerCmdArgs") String target) {
            // Test stub - not exercised
        }

        public List<String> suggestPlayerCmdArgs(Player p, Command c, String[] a) {
            return Collections.emptyList();
        }
    }

    @Nested
    @DisplayName("UAT Fix (05-fix): @CmdParam.suggest method-name signature refused at load when uninvocable")
    class UninvocableSuggestMethodSignatureTests {

        @Test
        @DisplayName("a suggest method whose signature the completer cannot invoke is refused at "
                + "load, naming the class, the mapping method and the offending signature")
        void uninvocableSignatureRefusesLoad() {
            UninvocableSuggestSignatureExecutor executor = new UninvocableSuggestSignatureExecutor();

            PluginModuleException exception = assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContract(executor));

            assertEquals(ErrorCode.COMMAND_SUGGEST_METHOD_UNINVOCABLE, exception.getErrorCode());
            assertTrue(exception.getMessage().contains(UninvocableSuggestSignatureExecutor.class.getName()));
            assertTrue(exception.getMessage().contains("giveCommand"));
            assertTrue(exception.getMessage().contains("suggestBad"));
            assertTrue(exception.getMessage().contains("int"));
        }

        @Test
        @DisplayName("each of the five invocable suggest-method signature shapes loads without incident")
        void allFiveInvocableShapesLoadCleanly() {
            AllInvocableSuggestSignatureShapesExecutor executor = new AllInvocableSuggestSignatureShapesExecutor();

            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
        }

        @Test
        @DisplayName("a suggest value naming a non-existent method still loads -- the unchanged "
                + "D-07 i18n hint fallback stays out of this check's scope")
        void nonExistentMethodNameStillLoadsCleanly() {
            // Reuses the pre-existing MethodNameSuggestExecutor fixture (Task 2's own D-07 test)
            // to pin that this NEW signature check does not regress the pre-existing "unknown
            // method name falls back to the i18n hint" behaviour.
            MethodNameSuggestExecutor executor = new MethodNameSuggestExecutor();

            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContract(executor));
        }

        @Test
        @DisplayName("one module's uninvocable-signature refusal does not prevent a sibling "
                + "module's container from validating cleanly")
        void oneModuleFailingDoesNotBlockAnother() {
            SimpleContainer badContainer = new SimpleContainer();
            badContainer.registerSingleton("bad", new UninvocableSuggestSignatureExecutor());

            SimpleContainer goodContainer = new SimpleContainer();
            goodContainer.registerSingleton("good", new AllInvocableSuggestSignatureShapesExecutor());

            assertThrows(PluginModuleException.class,
                    () -> PluginManager.validateCommandExecutorContracts(badContainer));
            assertDoesNotThrow(() -> PluginManager.validateCommandExecutorContracts(goodContainer));
        }
    }
}
