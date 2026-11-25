package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.UpmKeys;
import com.ultikits.ultitools.text.Arg;
import net.kyori.adventure.text.Component;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * What {@code /upm update} and {@code /upm uninstall} tell the operator about the update
 * transaction (#505): "staged, takes effect at the next start" -- never "updated" -- a staged
 * update already waiting, a previous apply that failed, update-all per module, and a staged update
 * cancelled by an uninstall. The command bodies are called directly, so the replies are read
 * synchronously; i18n returns the key, so the assertions read the catalogue keys.
 */
@DisplayName("/upm update and uninstall replies for the update transaction (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(CommandTestExtension.class)
class ModuleUpdateCommandTest {

    private MockedStatic<PluginInstallUtils> utils;
    private PluginInstallCommands executor;
    private CommandSender sender;
    private final List<String> messages = new ArrayList<>();
    private final Map<String, UpdateInfo> updates = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        UltiTools ultiTools = TestHelper.mockUltiToolsInstance();
        UpdateManager updateManager = mock(UpdateManager.class);
        when(updateManager.isCheckComplete()).thenReturn(true);
        when(updateManager.getModuleUpdates()).thenReturn(updates);
        when(ultiTools.getUpdateManager()).thenReturn(updateManager);
        sender = mock(CommandSender.class);
        org.mockito.Mockito.doAnswer(invocation -> messages.add(invocation.getArgument(0)))
                .when(sender).sendMessage(anyString());
        org.mockito.Mockito.doAnswer(call -> messages.add(CommandMessageTestSupport.plain(call.getArgument(0))))
                .when(sender).sendMessage(any(Component.class));
        utils = mockStatic(PluginInstallUtils.class);
        utils.when(() -> PluginInstallUtils.updateResult(anyString(), any())).thenCallRealMethod();
        executor = new PluginInstallCommands();
    }

    @AfterEach
    void tearDown() {
        utils.close();
        MockBukkitHelper.safeUnmock();
    }

    private void available(String name, String identify, String current, String latest) {
        UpdateInfo info = new UpdateInfo();
        info.setPluginName(name);
        info.setIdentifyString(identify);
        info.setCurrentVersion(current);
        info.setLatestVersion(latest);
        updates.put(name, info);
    }

    private static ModuleFileTransactions.StageResult result(ModuleFileTransactions.StageResult.Outcome outcome,
                                                             String oldVersion, String newVersion,
                                                             String previousFailure) {
        ModuleFileTransactions.StageResult result = mock(ModuleFileTransactions.StageResult.class);
        when(result.getOutcome()).thenReturn(outcome);
        when(result.getOldVersion()).thenReturn(oldVersion);
        when(result.getNewVersion()).thenReturn(newVersion);
        when(result.getPreviousFailure()).thenReturn(previousFailure);
        when(result.getReasonKey()).thenReturn(UpmKeys.REASON_DOWNLOAD_FAILED);
        when(result.getReasonArgs()).thenReturn(new Arg[]{Arg.string("error", "IOException: connection reset")});
        return result;
    }

    private String all() {
        return String.join("\n", messages);
    }

    @Test
    @DisplayName("a staged update says it takes effect at the next start and names the version that is restored otherwise")
    void stagedUpdate_saysItTakesEffectAtTheNextStart() {
        available("Demo", "demo", "1.0", "1.1");
        ModuleFileTransactions.StageResult stagedDemo = result(ModuleFileTransactions.StageResult.Outcome.STAGED, "1.0", "1.1", null);
        utils.when(() -> PluginInstallUtils.stageUpdate("demo", sender)).thenReturn(stagedDemo);

        executor.updatePlugin(sender, "Demo");

        assertThat(all()).contains("Demo 将在下次重启时从 1.0 更新到 1.1").contains("下次重启时").contains("恢复为 1.0")
                .doesNotContain("更新成功");
    }

    @Test
    @DisplayName("an update already staged is named, and nothing is reported as changed")
    void alreadyStaged_isNamed() {
        available("Demo", "demo", "1.0", "1.1");
        ModuleFileTransactions.StageResult stagedDemo = result(ModuleFileTransactions.StageResult.Outcome.ALREADY_STAGED, "1.0", "1.1", null);
        utils.when(() -> PluginInstallUtils.stageUpdate("demo", sender)).thenReturn(stagedDemo);

        executor.updatePlugin(sender, "Demo");

        assertThat(all()).contains("Demo 1.1 已在等待下次重启生效").contains("没有做任何改动");
    }

    @Test
    @DisplayName("a previous apply that failed is reported first")
    void previousFailure_isReportedFirst() {
        available("Demo", "demo", "1.0", "1.1");
        ModuleFileTransactions.StageResult stagedDemo = result(ModuleFileTransactions.StageResult.Outcome.STAGED, "1.0", "1.1",
                        "/srv/plugins/UltiTools/plugins/demo-1.0.jar: AccessDeniedException");
        utils.when(() -> PluginInstallUtils.stageUpdate("demo", sender)).thenReturn(stagedDemo);

        executor.updatePlugin(sender, "Demo");

        assertThat(messages.get(1)).contains("上一次 Demo 的变更在重启时没能应用").contains("AccessDeniedException");
        assertThat(messages.get(2)).contains("已加入待生效清单：Demo");
    }

    @Test
    @DisplayName("a failure to stage names the cause and says nothing changed")
    void failureToStage_namesTheCause() {
        available("Demo", "demo", "1.0", "1.1");
        ModuleFileTransactions.StageResult stagedDemo = result(ModuleFileTransactions.StageResult.Outcome.FAILED, null, null, null);
        utils.when(() -> PluginInstallUtils.stageUpdate("demo", sender)).thenReturn(stagedDemo);

        executor.updatePlugin(sender, "Demo");

        assertThat(all()).contains("未能把 Demo 的更新加入待生效清单").contains("connection reset").contains("没有做任何改动");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "zh"})
    void stagedReadFailureUsesTheShippedTranslation(String language) throws Exception {
        available("Demo", "demo", "1.0", "1.1");
        var failure = result(ModuleFileTransactions.StageResult.Outcome.FAILED, null, null, null);
        when(failure.getReasonKey()).thenReturn(UpmKeys.REASON_STAGED_UNREADABLE);
        when(failure.getReasonArgs()).thenReturn(new Arg[]{Arg.string("file", "demo-1.1.jar"),
                Arg.string("error", "IOException: read failed")});
        utils.when(() -> PluginInstallUtils.stageUpdate("demo", sender)).thenReturn(failure);
        executor.updatePlugin(sender, "Demo");
        assertThat(all()).contains("demo-1.1.jar", "IOException: read failed");
    }

    @Test
    @DisplayName("update-all stages each module independently and reports each, then the counts")
    void updateAll_reportsEachModule() {
        available("Alpha", "alpha", "1.0", "1.1");
        available("Beta", "beta", "2.0", "2.1");
        ModuleFileTransactions.StageResult stagedAlpha = result(ModuleFileTransactions.StageResult.Outcome.STAGED, "1.0", "1.1", null);
        utils.when(() -> PluginInstallUtils.stageUpdate("alpha", sender)).thenReturn(stagedAlpha);
        ModuleFileTransactions.StageResult stagedBeta = result(ModuleFileTransactions.StageResult.Outcome.FAILED, null, null, null);
        utils.when(() -> PluginInstallUtils.stageUpdate("beta", sender)).thenReturn(stagedBeta);

        executor.updatePlugin(sender, "all");

        assertThat(all()).contains("Alpha 将在下次重启时从 1.0 更新到 1.1").contains("未能把 Beta 的更新加入待生效清单")
                .contains("全部处理完成：1 个已加入待生效清单，1 个未能加入");
    }

    @Test
    @DisplayName("an uninstall that goes ahead says which staged update of the module it cancelled")
    void uninstall_cancelsTheStagedUpdate() throws Exception {
        utils.when(() -> PluginInstallUtils.requestUninstall("Demo", sender)).thenReturn(PendingCommandTestSupport.replacesUpdate());
        executor.uninstallPlugin(sender, "Demo");
        assertThat(all()).contains("Demo", "1.1");
    }

    @Test
    @DisplayName("gate 1 I-04: an uninstall that ends in an unexpected exception still says which update it cancelled")
    void uninstallThatThrowsUnexpectedly_stillReportsTheCancelledUpdate() {
        utils.when(() -> PluginInstallUtils.requestUninstall("Demo", sender)).thenReturn(PendingCommandTestSupport.refusal(UpmKeys.UNINSTALL_NOT_RECORDED, "Demo", "error", "record denied"));
        executor.uninstallPlugin(sender, "Demo");
        assertThat(all()).contains("Demo", "record denied");
    }

    @Test
    @DisplayName("an uninstall that is refused reports no cancelled update")
    void refusedUninstall_cancelsNothing() throws Exception {
        utils.when(() -> PluginInstallUtils.requestUninstall("Demo", sender)).thenReturn(PendingCommandTestSupport.refusal(UpmKeys.UNINSTALL_NOT_RECORDED, "Demo", "error", "record denied"));
        executor.uninstallPlugin(sender, "Demo");
        assertThat(all()).contains("Demo", "record denied");
    }
}
