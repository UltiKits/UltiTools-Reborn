package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Case 48 (CORR-08): a module unload must release the {@code CooldownValidator} and
 * {@code UsageLockValidator} instances its executors actually dispatched through -- a chain passed
 * to {@code super(chain)} or returned by an overridden {@code createDefaultValidatorChain} -- and
 * not only the two field validators every executor constructs. Those validators register themselves
 * with {@link PlayerCacheManager} at their first dispatch; one left registered after its module is
 * gone is strongly held there, and a module's own validator subclass pins its old class loader.
 * <p>
 * Unload is driven the way a module unload drives it: each executor is held as a singleton by its
 * own {@link SimpleContainer}, and {@link SimpleContainer#close()} runs the executor's
 * {@code @PreDestroy} teardown. {@code PluginManager.unregister} reaches the same hook through
 * {@code plugin.getContext().close()}. Registration and release are genuine: the mocked
 * {@code PluginManager} hands out a fresh, real {@link PlayerCacheManager} per test.
 */
@DisplayName("Unload releases the validators of the chain an executor actually used (case 48)")
class ValidatorOwnershipReleaseTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private PlayerCacheManager manager;
    private Command command;

    /** A guarded mapping: 30 s cooldown and a per-sender lock; counts how often its body ran. */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"guarded"})
    public static class GuardedCommand extends BaseCommandExecutor {
        final AtomicInteger runs = new AtomicInteger();

        public GuardedCommand() {
            super();
        }

        public GuardedCommand(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdCD(30)
        @UsageLimit(UsageLimit.LimitType.SENDER)
        @CmdMapping(format = "go")
        public void go(CommandSender sender) {
            runs.incrementAndGet();
        }
    }

    /** Overrides the default chain with new validator instances instead of the two fields. */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"guarded"})
    public static class OverriddenChainCommand extends GuardedCommand {
        @Override
        protected ValidatorChain createDefaultValidatorChain() {
            return ValidatorChain.builder()
                    .add(new CooldownValidator())
                    .add(new UsageLockValidator())
                    .build();
        }
    }

    @BeforeEach
    void setUp() {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        manager = new PlayerCacheManager();
        PluginManager pluginManager = mock(PluginManager.class);
        lenient().when(pluginManager.getPlayerCacheManager()).thenReturn(manager);
        lenient().when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);
        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("guarded");
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
        ultiToolsMock.close();
    }

    private static Player newPlayer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    /** Loads {@code executor} into a container of its own, the way a module holds its executors. */
    private static SimpleContainer moduleOf(BaseCommandExecutor executor) {
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("executor", executor);
        return container;
    }

    private void dispatch(BaseCommandExecutor executor, Player player) {
        executor.onCommand(player, command, "guarded", new String[]{"go"});
    }

    /** A dispatch context for {@code executor}'s {@code go} mapping, as {@code onCommand} builds it. */
    private CommandContext goContext(BaseCommandExecutor executor, Player player) {
        return CommandContext.builder()
                .sender(player)
                .command(command)
                .alias("guarded")
                .rawArgs(new String[]{"go"})
                .executorClass(executor.getClass())
                .executor(executor)
                .build()
                .withMatchedMethod(executor.getMappings().get("go"), "go");
    }

    private static String goKey(BaseCommandExecutor executor) {
        return executor.getMappings().get("go").toString();
    }

    @Test
    @DisplayName("a chain passed to super(chain): its validators are released at unload")
    void customChainValidatorsAreReleasedAtUnload() {
        GuardedCommand executor = new GuardedCommand(ValidatorChain.builder()
                .add(new CooldownValidator())
                .add(new UsageLockValidator())
                .build());
        SimpleContainer module = moduleOf(executor);

        dispatch(executor, newPlayer());
        assertEquals(1, executor.runs.get(), "the guarded body runs once");
        assertEquals(2, manager.getTrackedBeanCount(),
                "the chain's two validators register themselves at their first dispatch");

        module.close();

        assertEquals(0, manager.getTrackedBeanCount(),
                "unload must release the validators of the chain the executor dispatched through");
    }

    @Test
    @DisplayName("a chain returned by an overridden createDefaultValidatorChain: its validators are released at unload")
    void overriddenDefaultChainValidatorsAreReleasedAtUnload() {
        OverriddenChainCommand executor = new OverriddenChainCommand();
        SimpleContainer module = moduleOf(executor);

        dispatch(executor, newPlayer());
        assertEquals(1, executor.runs.get(), "the guarded body runs once");
        assertEquals(2, manager.getTrackedBeanCount(),
                "the overriding chain's two validators register themselves at their first dispatch");

        module.close();

        assertEquals(0, manager.getTrackedBeanCount(),
                "unload must release the validators of the chain the executor dispatched through");
    }

    @Test
    @DisplayName("control: the default chain is released and its locks cleared at unload, as before")
    void defaultChainReleaseIsUnchanged() {
        GuardedCommand executor = new GuardedCommand();
        SimpleContainer module = moduleOf(executor);
        Player holder = newPlayer();

        dispatch(executor, newPlayer());
        assertEquals(2, manager.getTrackedBeanCount(), "the two field validators registered at first dispatch");
        assertTrue(executor.getLockValidator().validate(goContext(executor, holder)).isValid(),
                "the holder takes the per-sender lock");
        assertTrue(executor.getLockValidator().isLocked(holder.getUniqueId(), goKey(executor)));

        module.close();

        assertEquals(0, manager.getTrackedBeanCount(), "both field validators are released");
        assertFalse(executor.getLockValidator().isLocked(holder.getUniqueId(), goKey(executor)),
                "every lock is cleared at unload");
    }

    /**
     * Two loaded executors sharing one chain: A uses the default constructor, B is built over
     * A's chain; each sits in its own container. Player one dispatched on A, player two on B, and
     * player three holds a per-sender lock on B's mapping, taken through the shared lock validator.
     */
    public static final class SharedPair {
        GuardedCommand a;
        GuardedCommand b;
        SimpleContainer moduleA;
        SimpleContainer moduleB;
        Player one;
        Player two;
        Player three;
        UsageLockValidator sharedLock;
    }

    private SharedPair sharedPair() {
        SharedPair pair = new SharedPair();
        pair.a = new GuardedCommand();
        pair.b = new GuardedCommand(pair.a.getValidatorChain());
        pair.moduleA = moduleOf(pair.a);
        pair.moduleB = moduleOf(pair.b);
        pair.one = newPlayer();
        pair.two = newPlayer();
        pair.three = newPlayer();
        pair.sharedLock = pair.a.getLockValidator();

        dispatch(pair.a, pair.one);
        dispatch(pair.b, pair.two);
        assertEquals(1, pair.a.runs.get(), "player one's dispatch on A runs");
        assertEquals(1, pair.b.runs.get(), "player two's dispatch on B runs");
        assertEquals(2, manager.getTrackedBeanCount(), "the shared chain's two validators are registered once");
        assertTrue(pair.sharedLock.validate(goContext(pair.b, pair.three)).isValid(),
                "player three takes the per-sender lock on B's mapping");
        assertTrue(pair.sharedLock.isLocked(pair.three.getUniqueId(), goKey(pair.b)));
        return pair;
    }

    /** The shared validators are still registered, locking, enforcing cooldowns and swept on quit. */
    private void assertSharedValidatorsStillServe(SharedPair pair, GuardedCommand remaining, Player user) {
        assertEquals(2, manager.getTrackedBeanCount(),
                "the remaining sharer's validators must stay registered with PlayerCacheManager");
        assertTrue(pair.sharedLock.isLocked(pair.three.getUniqueId(), goKey(pair.b)),
                "player three's lock must stay held while a sharer is loaded");
        int before = remaining.runs.get();
        dispatch(remaining, user);
        assertEquals(before, remaining.runs.get(), "the remaining sharer's cooldown still refuses a second use");
        manager.onPlayerQuit(user.getUniqueId());
        dispatch(remaining, user);
        assertEquals(before + 1, remaining.runs.get(),
                "the quit sweep still reaches the shared cooldown validator and clears the cooldown");
    }

    @Test
    @DisplayName("unloading one sharer keeps the shared validators, locks, cooldowns and quit cleanup for the other")
    void remainingActiveSharerKeepsTheSharedValidators() {
        SharedPair pair = sharedPair();

        pair.moduleA.close();

        assertSharedValidatorsStillServe(pair, pair.b, pair.two);
    }

    @Test
    @DisplayName("the last active user's unload releases the shared validators and clears their locks")
    void lastActiveUserReleasesTheSharedValidators() {
        SharedPair pair = sharedPair();
        pair.moduleA.close();

        pair.moduleB.close();

        assertEquals(0, manager.getTrackedBeanCount(), "the last user's unload releases the shared validators");
        assertFalse(pair.sharedLock.isLocked(pair.three.getUniqueId(), goKey(pair.b)),
                "the last user's unload clears the shared locks");
    }

    @Test
    @DisplayName("unload order does not matter: B first keeps them for A, then A releases them")
    void unloadOrderDoesNotMatter() {
        SharedPair pair = sharedPair();

        pair.moduleB.close();
        assertSharedValidatorsStillServe(pair, pair.a, pair.one);

        pair.moduleA.close();
        assertEquals(0, manager.getTrackedBeanCount(), "the second unload releases the shared validators");
        assertFalse(pair.sharedLock.isLocked(pair.three.getUniqueId(), goKey(pair.b)),
                "the second unload clears the shared locks");
    }

    @Test
    @DisplayName("two modules over one custom chain unload independently")
    void twoModulesSharingAChainUnloadIndependently() {
        CooldownValidator cooldown = new CooldownValidator();
        UsageLockValidator lock = new UsageLockValidator();
        ValidatorChain chain = ValidatorChain.builder().add(cooldown).add(lock).build();
        GuardedCommand first = new GuardedCommand(chain);
        GuardedCommand second = new GuardedCommand(chain);
        SimpleContainer firstModule = moduleOf(first);
        SimpleContainer secondModule = moduleOf(second);
        Player one = newPlayer();
        Player two = newPlayer();
        Player holder = newPlayer();

        dispatch(first, one);
        dispatch(second, two);
        assertEquals(2, manager.getTrackedBeanCount(), "the shared chain's two validators are registered once");
        assertTrue(lock.validate(goContext(second, holder)).isValid(), "the holder takes the per-sender lock");

        firstModule.close();

        assertEquals(2, manager.getTrackedBeanCount(),
                "the second module still dispatches through the shared validators");
        assertTrue(lock.isLocked(holder.getUniqueId(), goKey(second)), "the holder's lock stays held");
        dispatch(second, two);
        assertEquals(1, second.runs.get(), "the shared cooldown still refuses player two's second use");

        secondModule.close();

        assertEquals(0, manager.getTrackedBeanCount(), "the last module's unload releases the shared validators");
        assertFalse(lock.isLocked(holder.getUniqueId(), goKey(second)), "the last module's unload clears the locks");
    }

    @Test
    @DisplayName("a sharer that never dispatched does not pin the validators and registers them again on first use")
    void neverDispatchedSharerDoesNotPinAndReRegistersOnFirstUse() {
        GuardedCommand a = new GuardedCommand();
        GuardedCommand b = new GuardedCommand(a.getValidatorChain());
        SimpleContainer moduleA = moduleOf(a);
        moduleOf(b);
        Player one = newPlayer();
        Player two = newPlayer();

        dispatch(a, one);
        assertEquals(2, manager.getTrackedBeanCount(), "A's dispatch registered the shared validators");

        moduleA.close();
        assertEquals(0, manager.getTrackedBeanCount(),
                "B never dispatched, so it does not keep the shared validators registered");

        dispatch(b, two);
        assertEquals(1, b.runs.get(), "B's first dispatch runs");
        assertEquals(2, manager.getTrackedBeanCount(), "B's first dispatch registers the shared validators again");
        dispatch(b, two);
        assertEquals(1, b.runs.get(), "the cooldown refuses player two's second use on B");
        manager.onPlayerQuit(two.getUniqueId());
        dispatch(b, two);
        assertEquals(2, b.runs.get(), "the quit sweep reaches the re-registered validator and clears the cooldown");
    }

    @Test
    @DisplayName("released validators register again when a later executor reuses their chain")
    void releasedValidatorsReRegisterWhenReused() {
        ValidatorChain chain = ValidatorChain.builder()
                .add(new CooldownValidator())
                .add(new UsageLockValidator())
                .build();
        GuardedCommand a = new GuardedCommand(chain);
        SimpleContainer moduleA = moduleOf(a);

        dispatch(a, newPlayer());
        assertEquals(2, manager.getTrackedBeanCount());
        moduleA.close();
        assertEquals(0, manager.getTrackedBeanCount(), "A's unload releases its chain's validators");

        GuardedCommand c = new GuardedCommand(chain);
        moduleOf(c);
        dispatch(c, newPlayer());

        assertEquals(1, c.runs.get(), "C's dispatch runs");
        assertEquals(2, manager.getTrackedBeanCount(), "the reused validators register again on C's dispatch");
    }
}
