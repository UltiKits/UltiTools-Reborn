package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.utils.ApiRateLimiter;
import com.ultikits.ultitools.utils.CloudTestAccess;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #740: {@code /ulticloud login} over RCON printed nothing, because the {@code @RunAsync} body left
 * the command's dispatch and RCON returns its reply when the dispatch returns.
 * <p>
 * Driven through the real dispatch path, {@link CloudLoginCommand#onCommand}, with the harness of
 * {@code InlineCommandDispatchTest}: {@code Bukkit.isPrimaryThread()} is true and the scheduler
 * captures every task and runs none, so "the sender heard something before the dispatch returned"
 * is observable directly. {@code CloudAuthManager} is the real class; the base URL is blank, so the
 * device-code request fails locally on the session's own executor and no network is touched.
 */
@DisplayName("ulticloud login over RCON answers inside the dispatch (#740)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudLoginRconDispatchTest {

    private static final String CONSOLE_NOTICE = ChatColor.AQUA
            + "Requesting a device code from UltiCloud. The code and the link will be printed in the server console.";

    @TempDir
    Path tempDir;

    private MockedStatic<Bukkit> bukkitMock;
    private BukkitScheduler scheduler;
    private final List<Runnable> deferred = new ArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private RemoteConsoleCommandSender rcon;
    private Command command;

    @BeforeEach
    void setUp() {
        CloudTestAccess.useTemporaryCredentials(tempDir);
        CloudTestAccess.setBaseUrl("");
        Logger logger = mock(Logger.class);
        // The real static instance, not a thread-local static mock: the device-code request runs on
        // the login session's own executor thread and logs through it there.
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));

        scheduler = mock(BukkitScheduler.class);
        lenient().when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            deferred.add(inv.getArgument(1));
            return null;
        });
        lenient().when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            deferred.add(inv.getArgument(1));
            return null;
        });
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);

        rcon = mock(RemoteConsoleCommandSender.class);
        when(rcon.isOp()).thenReturn(true);
        lenient().when(rcon.getName()).thenReturn("Rcon");
        doAnswer(inv -> {
            received.add(inv.getArgument(0));
            return null;
        }).when(rcon).sendMessage(anyString());

        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("ulticloud");
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the UltiTools singleton mocked in setUp
    void tearDown() throws Exception {
        bukkitMock.close();
        CloudTestAccess.reset();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private List<String> dispatchLogin() {
        new CloudLoginCommand().onCommand(rcon, command, "ulticloud", new String[]{"login"});
        // Snapshot at the moment the dispatch returned: later lines arrive from the session executor.
        return new ArrayList<>(received);
    }

    @Test
    @DisplayName("RCON receives the console notice before the dispatch returns, and nothing is deferred")
    void rconReceivesALineBeforeTheDispatchReturns() {
        List<String> atReturn = dispatchLogin();

        assertThat(atReturn)
                .as("the RCON sender must hear something before onCommand returns -- RCON sends its "
                        + "reply then; a body deferred to an async task is the #740 defect")
                .isNotEmpty();
        assertThat(atReturn.get(0)).isEqualTo(CONSOLE_NOTICE);
        verify(scheduler, never()).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        assertThat(deferred).isEmpty();
    }

    @Test
    @DisplayName("the login body carries neither @RunAsync nor @AsyncCommand")
    void loginIsNotAsync() throws NoSuchMethodException {
        Method login = CloudLoginCommand.class.getMethod("login", CommandSender.class);

        assertThat(login.isAnnotationPresent(RunAsync.class)).isFalse();
        assertThat(login.isAnnotationPresent(AsyncCommand.class)).isFalse();
    }

    @Test
    @DisplayName("an already linked server answers RCON with the already-linked line only")
    void alreadyLinkedAnswersOnlyThat() {
        CloudTestAccess.storeLinkedCredential("Survival", "01JTESTSERVERID0000000000");

        List<String> atReturn = dispatchLogin();

        assertThat(atReturn).containsExactly(ChatColor.YELLOW
                + "This server is already linked to UltiCloud as Survival. Use /ulticloud logout first to link it again.");
    }

    @Test
    @DisplayName("a rate-limited login answers RCON with the wait line only")
    void rateLimitedAnswersOnlyThat() {
        assertThat(ApiRateLimiter.isLoginAllowed()).as("consume the login window").isTrue();

        List<String> atReturn = dispatchLogin();

        assertThat(atReturn).hasSize(1);
        assertThat(atReturn.get(0)).startsWith(ChatColor.RED + "Please wait ")
                .endsWith(" seconds before trying again.");
    }
}
