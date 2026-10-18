package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;
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
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.ProxyFactory;

/**
 * Case 36 (CORR-08): a mapping inherited from a base class that carries a class-level
 * {@code @CmdCD} or {@code @UsageLimit}, once the executor is an AOP proxy (the mapping carries
 * {@code @ExceptionCatch}), must keep that base class's metadata at dispatch -- exactly as the
 * same executor does without a proxy.
 * <p>
 * The proxy overrides the intercepted mapping, so the mapping the dispatch table stores is
 * declared by the generated class, not by the base class that carries the annotation. Every
 * proxied test first asserts that precondition: if the stored mapping is not declared by a
 * generated proxy, the test is not reproducing the case.
 * <p>
 * Fixtures are public static nested classes of this outer class so the proxy factory can subclass
 * them, and because tests compile with {@code -source 1.8}, where a {@code @Nested} (inner) class
 * cannot declare static nested classes.
 */
@DisplayName("A proxied inherited mapping keeps its base class's class-level metadata (case 36)")
class ProxiedInheritedMappingMetadataTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private Command command;

    // ========== Fixtures: cooldown ==========

    /** Carries the class-level cooldown; declares the intercepted mapping. */
    @CmdCD(60)
    public abstract static class CooldownBase extends BaseCommandExecutor {
        public final AtomicInteger bodies = new AtomicInteger();

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @ExceptionCatch
        @CmdMapping(format = "go")
        public void go(CommandSender sender) {
            bodies.incrementAndGet();
        }
    }

    /** Declares nothing: the mapping and the cooldown are both inherited. */
    public static class CooldownInheritor extends CooldownBase {
    }

    // ========== Fixtures: usage limit ==========

    /** Carries the class-level server-wide lock; declares the intercepted mapping. */
    @UsageLimit(UsageLimit.LimitType.ALL)
    public abstract static class LockBase extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @ExceptionCatch
        @CmdMapping(format = "work")
        public void work(CommandSender sender) {
            // Not invoked: the lock is exercised directly.
        }
    }

    /** Declares nothing: the mapping and the lock are both inherited. */
    public static class LockInheritor extends LockBase {
    }

    @BeforeEach
    void setUp() {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);
        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("inherited");
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
        ultiToolsMock.close();
    }

    // ========== Helpers ==========

    @SuppressWarnings("unchecked")
    static <T> T proxyOf(Class<T> type) throws Exception {
        Class<? extends T> proxyClass = new ProxyFactory(Collections.emptyList())
                .createProxyClass(type, AopEligibility.findAopAnnotatedMethods(type));
        return proxyClass.getDeclaredConstructor().newInstance();
    }

    /**
     * The case-36 precondition: the mapping the dispatch table stores under {@code format} is the
     * generated proxy's override, not the base class's declaration.
     */
    static void assertStoredMappingIsAProxyOverride(BaseCommandExecutor executor, String format) {
        Method stored = executor.getMappings().get(format);
        assertTrue(stored != null && ProxyFactory.isProxyClass(stored.getDeclaringClass()),
                "precondition: the mapping stored under '" + format + "' must be declared by a generated "
                        + "proxy, otherwise this test does not reproduce case 36; stored: " + stored);
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private CommandContext lockContext(BaseCommandExecutor executor, CommandSender sender) {
        Method mapping = executor.getMappings().get("work");
        return CommandContext.builder()
                .sender(sender)
                .command(command)
                .alias("inherited")
                .rawArgs(new String[]{"work"})
                .executorClass(executor.getClass())
                .executor(executor)
                .build()
                .withMatchedMethod(mapping, "work");
    }

    private void assertCooldownRefusesTheSecondDispatch(CooldownBase executor) {
        Player player = player();

        executor.onCommand(player, command, "inherited", new String[]{"go"});
        executor.onCommand(player, command, "inherited", new String[]{"go"});

        assertEquals(1, executor.bodies.get(),
                "the base class's class-level @CmdCD(60) must refuse the same player's second dispatch");
    }

    private void assertUsageLimitSerialises(BaseCommandExecutor executor) {
        CommandContext a = lockContext(executor, player());
        CommandContext b = lockContext(executor, player());

        assertTrue(executor.getLockValidator().acquireLock(a), "player A takes the server-wide lock");
        assertFalse(executor.getLockValidator().acquireLock(b),
                "the base class's class-level @UsageLimit(ALL) must refuse player B while A holds the lock");
        executor.getLockValidator().releaseLock(a);
        assertTrue(executor.getLockValidator().acquireLock(b), "player B takes the lock once A releases it");
    }

    // ========== Tests ==========

    @Test
    @DisplayName("proxied: a base class's class-level @CmdCD refuses the second dispatch within the cooldown")
    void proxiedInheritedMappingHonoursBaseClassCooldown() throws Exception {
        CooldownInheritor executor = proxyOf(CooldownInheritor.class);
        assertStoredMappingIsAProxyOverride(executor, "go");

        assertCooldownRefusesTheSecondDispatch(executor);
    }

    @Test
    @DisplayName("control, ordinary: a base class's class-level @CmdCD refuses the second dispatch")
    void ordinaryInheritedMappingHonoursBaseClassCooldown() {
        assertCooldownRefusesTheSecondDispatch(new CooldownInheritor());
    }

    @Test
    @DisplayName("proxied: a base class's class-level @UsageLimit(ALL) serialises the inherited mapping")
    void proxiedInheritedMappingHonoursBaseClassUsageLimit() throws Exception {
        LockInheritor executor = proxyOf(LockInheritor.class);
        assertStoredMappingIsAProxyOverride(executor, "work");

        assertUsageLimitSerialises(executor);
    }

    @Test
    @DisplayName("control, ordinary: a base class's class-level @UsageLimit(ALL) serialises the inherited mapping")
    void ordinaryInheritedMappingHonoursBaseClassUsageLimit() {
        assertUsageLimitSerialises(new LockInheritor());
    }
}
