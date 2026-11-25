package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;

/**
 * Proves conditional command registration at the Paper commands-event boundary. Both fixtures pass
 * through the real component scanner, container refresh and bean-resolving CommandManager path.
 * The true-condition sibling is the positive control: an absent false-condition root alone could
 * otherwise hide a broken scan or registration path. MockBukkit does not fire this Paper event, so
 * the test invokes the engine's captured public callback with a real Brigadier-backed registrar.
 *
 * <p>The former reflective ListenerManager scan overload was removed in 6.3.0. Its surviving
 * bean-resolving path relies on the same container gate, covered by the container tests.
 */
@DisplayName("@ConditionalOnConfig at the Paper commands-event boundary")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(CommandTestExtension.class)
class ConditionalOnConfigRegistrationIntegrationTest {

    private UltiTools previousUltiTools;

    @BeforeEach
    void setUp() {
        previousUltiTools = UltiTools.getInstance();
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            // This test publishes a disabled mock; it must not close later tests' scheduling gate.
            publishUltiToolsInstance(previousUltiTools);
        } finally {
            com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        }
    }

    private void writeConfig(File tempDir, String content) throws Exception {
        File configFile = new File(tempDir, "config/config.yml");
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void publishUltiToolsInstance(UltiTools mock) throws Exception {
        java.lang.reflect.Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, mock);
    }

    @Nested
    @DisplayName("Command half: the standard bean-resolving path")
    class CommandHalf {
        @Test
        @DisplayName("false-condition root is absent; the true-condition control reaches the registrar")
        void falseConditionCommandAbsentTrueConditionPresent(@TempDir File tempDir) throws Exception {
            writeConfig(tempDir, "enableFalseCommand: false\nenableTrueCommand: true\n");

            UltiTools mockUltiTools = mock(UltiTools.class);
            CommandManager commandManager = new CommandManager();
            var lifecycle = CommandTestSupport.lifecycle(mockUltiTools);
            when(mockUltiTools.getCommandManager()).thenReturn(commandManager);
            when(mockUltiTools.getLogger()).thenReturn(java.util.logging.Logger.getLogger(
                    "ConditionalOnConfigRegistrationIntegrationTest.CommandHalf"));
            when(mockUltiTools.getDescription())
                    .thenReturn(new PluginDescriptionFile("UltiTools", "1.0.0", "com.ultikits.ultitools.UltiTools"));
            publishUltiToolsInstance(mockUltiTools);

            UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
            when(mockPlugin.getPluginName()).thenReturn("conditional-module");
            when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
            when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

            SimpleContainer container = new SimpleContainer();
            try {
                // Register the module before scanning, as PluginManager.initializePlugin does.
                container.registerType(UltiToolsPlugin.class, mockPlugin);
                when(mockPlugin.getContext()).thenReturn(container);
                container.scanComponents("com.ultikits.testfixtures.conditionalcommand");
                container.refresh();
                commandManager.registerAll(mockPlugin);

                var dispatcher = lifecycle.fire().getDispatcher();
                assertThat(dispatcher.getRoot().getChild("conditionalfalsecmd"))
                        .as("a false condition must prevent delivery to Paper's registrar").isNull();
                assertThat(dispatcher.getRoot().getChild("conditionaltruecmd"))
                        .as("positive control: the same scan and registration must deliver the true sibling")
                        .isNotNull();
                assertThat(commandManager.getPluginByLabel("conditionalfalsecmd")).isNull();
                assertThat(commandManager.getPluginByLabel("conditionaltruecmd")).isSameAs(mockPlugin);
            } finally {
                commandManager.close();
                container.close();
            }
        }
    }
}
