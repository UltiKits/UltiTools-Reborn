package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.UpmKeys;
import net.kyori.adventure.text.Component;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * The {@code /upm uninstall} reply when a JAR could not be deleted now and was recorded for the next
 * start (#518): it says the file is held open or not writable and will be deleted at the next start
 * before modules load -- not "delete it by hand", which the running server prevents on Windows.
 */
@DisplayName("/upm uninstall reply for a deletion deferred to the next start (#518)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(CommandTestExtension.class)
class ModuleRemovalDeferredCommandTest {

    private MockedStatic<PluginInstallUtils> utils;
    private PluginInstallCommands executor;
    private CommandSender sender;
    private final List<String> messages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        sender = mock(CommandSender.class);
        org.mockito.Mockito.doAnswer(invocation -> messages.add(CommandMessageTestSupport.plain(invocation.getArgument(0))))
                .when(sender).sendMessage(any(Component.class));
        utils = mockStatic(PluginInstallUtils.class);
        executor = new PluginInstallCommands();
    }

    @AfterEach
    void tearDown() {
        utils.close();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("the reply names the file and says it is deleted at the next start before modules load")
    void deferredDeletion_isReportedAsSuch() {
        utils.when(() -> PluginInstallUtils.requestUninstall("Demo", sender))
                .thenReturn(PendingCommandTestSupport.remove("Demo", "Demo.jar"));
        executor.uninstallPlugin(sender, "Demo");
        assertThat(messages).singleElement().asString().contains("Demo.jar", "/upm revoke Demo");
    }

    @Test
    @DisplayName("ultitools.upm.uninstall.deferred-unrecorded: a deletion that could not be recorded either lists only the JAR to delete, and the record failure on its own line")
    void unrecordedDeletion_replyListsTheJarAndTheRecordFailureSeparately() {
        utils.when(() -> PluginInstallUtils.requestUninstall("Demo", sender))
                .thenReturn(PendingCommandTestSupport.refusal(UpmKeys.UNINSTALL_NOT_RECORDED, "Demo", "error", "write denied"));
        executor.uninstallPlugin(sender, "Demo");
        assertThat(messages).singleElement().asString().contains("Demo", "write denied");
    }
}
