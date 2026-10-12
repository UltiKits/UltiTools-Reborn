package com.ultikits.ultitools;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * {@code UltiTools#onDisable} stops the start-up update check (Phase 19, D19-032), before it shuts
 * anything down: a check still in flight must not look anything up or log anything once the
 * framework is disabling, and the classes it would need may already be unloadable.
 * <br>
 * {@code onDisable} 在关闭其他组件之前先停止启动时的更新检查。
 */
@DisplayName("D19-032: onDisable stops the start-up update check first")
class UltiToolsDisableStopsUpdateCheckTest {

    private UltiTools ultiTools;
    private UpdateManager updateManager;
    private CommandManager commandManager;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        ultiTools = mock(UltiTools.class);
        doCallRealMethod().when(ultiTools).onDisable();
        when(ultiTools.getLogger()).thenReturn(Logger.getLogger("UltiToolsDisableStopsUpdateCheckTest"));
        commandManager = mock(CommandManager.class);
        when(ultiTools.getCommandManager()).thenReturn(commandManager);
        updateManager = mock(UpdateManager.class);
        setField("updateManager", updateManager);
    }

    @AfterEach
    void tearDown() {
        PluginInitiationUtils.startAcceptingPanelMessages();
        MockBukkitHelper.safeUnmock();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void setField(String name, Object value) throws Exception {
        Field field = UltiTools.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(ultiTools, value);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertions are Mockito InOrder verifications
    @DisplayName("the update check is stopped, and before the command manager closes")
    void onDisableStopsTheUpdateCheckFirst() {
        ultiTools.onDisable();

        InOrder order = inOrder(updateManager, commandManager);
        order.verify(updateManager).stop();
        order.verify(commandManager).close();
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertion is a Mockito verification
    @DisplayName("no update manager (disabled before the start-up task ran): onDisable completes")
    void onDisableWithoutUpdateManager() throws Exception {
        setField("updateManager", null);

        ultiTools.onDisable();

        org.mockito.Mockito.verify(commandManager).close();
    }
}
