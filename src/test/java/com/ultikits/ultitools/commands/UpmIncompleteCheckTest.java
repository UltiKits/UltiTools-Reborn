package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;

/**
 * {@code /upm check} and {@code /upm update} read the start-up check's result. When a lookup in
 * that check failed, "No updates available." is not known to be true, so they say the check is
 * incomplete instead, naming what could not be confirmed (Phase 19, D19-032: the same signal as
 * the start-up terminal line, handled at every place that reads it).
 * <br>
 * {@code /upm check} 与 {@code /upm update} 在启动检查有查询失败时不再说"没有可用的更新"。
 */
@DisplayName("D19-032: /upm check and /upm update say the update check is incomplete instead of 'No updates available.'")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UpmIncompleteCheckTest {

    private static final String NONE = "没有可用的更新。";
    private static final String INCOMPLETE = "更新检查未完成：无法确认 %s 的最新版本。";

    private MockedStatic<UltiTools> ultiMock;
    private UpdateManager updateManager;
    private CommandSender sender;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ultiMock = mockStatic(UltiTools.class);
        UltiTools instance = mock(UltiTools.class);
        when(instance.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ultiMock.when(UltiTools::getInstance).thenReturn(instance);
        updateManager = mock(UpdateManager.class);
        when(instance.getUpdateManager()).thenReturn(updateManager);
        when(updateManager.getModuleUpdates()).thenReturn(new HashMap<>());
        sender = mock(CommandSender.class);
        doAnswer(inv -> said.add(inv.getArgument(0))).when(sender).sendMessage(anyString());
    }

    @AfterEach
    void tearDown() {
        ultiMock.close();
    }

    private String all() {
        return String.join("\n", said);
    }

    @Test
    @DisplayName("/upm check, nothing found and the framework lookup failed: incomplete, not No updates available")
    void checkWithFailedLookup() {
        when(updateManager.hasAnyUpdates()).thenReturn(false);
        when(updateManager.getUnconfirmed()).thenReturn(Arrays.asList("UltiTools-API", "Demo"));

        new PluginInstallCommands().checkUpdates(sender);

        assertThat(all()).doesNotContain(NONE);
        assertThat(all()).contains(String.format(INCOMPLETE, "UltiTools-API, Demo"));
    }

    @Test
    @DisplayName("/upm check, an update found and another lookup failed: the update is listed, then incomplete")
    void checkWithUpdateAndFailedLookup() {
        when(updateManager.hasAnyUpdates()).thenReturn(true);
        Map<String, UpdateInfo> updates = new HashMap<>();
        UpdateInfo info = new UpdateInfo();
        info.setPluginName("Demo");
        info.setCurrentVersion("1.0");
        info.setLatestVersion("1.1");
        updates.put("Demo", info);
        when(updateManager.getModuleUpdates()).thenReturn(updates);
        when(updateManager.getUnconfirmed()).thenReturn(Collections.singletonList("UltiTools-API"));

        new PluginInstallCommands().checkUpdates(sender);

        assertThat(all()).contains("Demo 1.0 → 1.1");
        assertThat(said.get(said.size() - 1)).contains(String.format(INCOMPLETE, "UltiTools-API"));
    }

    @Test
    @DisplayName("control: /upm check with every lookup confirmed and nothing found still says No updates available")
    void checkConfirmedNone() {
        when(updateManager.hasAnyUpdates()).thenReturn(false);
        when(updateManager.getUnconfirmed()).thenReturn(Collections.<String>emptyList());

        new PluginInstallCommands().checkUpdates(sender);

        assertThat(all()).contains(NONE);
        assertThat(all()).doesNotContain("更新检查未完成");
    }

    @Test
    @DisplayName("/upm update <module> whose lookup failed: incomplete naming that module, not No updates available")
    void updateOneWithFailedLookup() {
        when(updateManager.getUnconfirmed()).thenReturn(Collections.singletonList("Demo"));

        new PluginInstallCommands().updatePlugin(sender, "Demo");

        assertThat(all()).doesNotContain(NONE);
        assertThat(all()).contains(String.format(INCOMPLETE, "Demo"));
    }

    @Test
    @DisplayName("control: /upm update <module> whose lookup succeeded with nothing newer still says No updates available")
    void updateOneConfirmedNone() {
        when(updateManager.getUnconfirmed()).thenReturn(Collections.singletonList("Other"));

        new PluginInstallCommands().updatePlugin(sender, "Demo");

        assertThat(all()).contains(NONE);
    }

    @Test
    @DisplayName("/upm update all with no module update found and a module lookup failed: incomplete, naming modules only")
    void updateAllWithFailedLookup() {
        when(updateManager.getUnconfirmed()).thenReturn(Arrays.asList("UltiTools-API", "Demo"));

        new PluginInstallCommands().updatePlugin(sender, "all");

        assertThat(all()).doesNotContain(NONE);
        assertThat(all()).contains(String.format(INCOMPLETE, "Demo"));
    }

    @Test
    @DisplayName("control: /upm update all when only the framework lookup failed still says No updates available (it updates modules only)")
    void updateAllOnlyFrameworkFailed() {
        when(updateManager.getUnconfirmed()).thenReturn(Collections.singletonList("UltiTools-API"));

        new PluginInstallCommands().updatePlugin(sender, "all");

        assertThat(all()).contains(NONE);
    }
}
