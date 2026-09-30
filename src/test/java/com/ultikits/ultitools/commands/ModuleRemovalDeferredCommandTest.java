package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.Collections;
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
import com.ultikits.ultitools.utils.TestHelper;

/**
 * The {@code /upm uninstall} reply when a JAR could not be deleted now and was recorded for the next
 * start (#518): it says the file is held open or not writable and will be deleted at the next start
 * before modules load -- not "delete it by hand", which the running server prevents on Windows.
 */
@DisplayName("/upm uninstall reply for a deletion deferred to the next start (#518)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
        org.mockito.Mockito.doAnswer(invocation -> messages.add(invocation.getArgument(0)))
                .when(sender).sendMessage(anyString());
        utils = mockStatic(PluginInstallUtils.class);
        utils.when(() -> PluginInstallUtils.cancelStagedUpdates(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
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
        PluginInstallUtils.RemovalDeferredException deferred = PluginInstallUtils.RemovalDeferredException.of(
                Collections.singletonList("/srv/plugins/UltiTools/plugins/demo-1.0.jar"),
                "FileSystemException: The process cannot access the file because it is being used by another process");
        utils.when(() -> PluginInstallUtils.uninstallPluginReporting("Demo")).thenThrow(deferred);

        executor.uninstallPlugin(sender, "Demo");

        String all = String.join("\n", messages);
        assertThat(all).contains("以下模块 JAR 暂时无法删除（被占用或不可写：FileSystemException")
                .contains("已记录：下次启动会在加载模块之前删除它们，仍无法删除时会在启动日志中报告：/srv/plugins/UltiTools/plugins/demo-1.0.jar")
                .doesNotContain("请手动删除");
        // The deferred JAR counts as removed: an update of the module waiting on it is cancelled.
        utils.verify(() -> PluginInstallUtils.cancelStagedUpdates("Demo", Collections.singletonList("demo-1.0.jar")));
    }
}
