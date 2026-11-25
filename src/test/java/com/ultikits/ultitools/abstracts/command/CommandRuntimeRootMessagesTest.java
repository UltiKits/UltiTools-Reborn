package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.ReflectionUtil;
import com.ultikits.ultitools.utils.TestHelper;
import java.nio.file.Path;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

/** No CommandTestExtension or messages override: drives the actual root registration and lookup. */
class CommandRuntimeRootMessagesTest {
    @TempDir
    Path temporary;

    @Test
    void realRootMessagesAreResolvedWithoutTheTestOverride() throws Exception {
        var root = new SimpleContainer();
        var dependencies = mock(DependenceManagers.class, Answers.CALLS_REAL_METHODS);
        ReflectionUtil.setFieldValue(dependencies, "context", root);
        var configuration = new YamlConfiguration();
        configuration.set("language", "auto");
        configuration.set("fallback-language", "en");
        UltiTools plugin = TestHelper.mockUltiToolsInstance(core -> {
            when(core.getDependenceManagers()).thenReturn(dependencies);
            when(core.getDataFolder()).thenReturn(temporary.toFile());
            when(core.getConfig()).thenReturn(configuration);
        });
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.global()).thenReturn(mock(TaskContext.class));
        FrameworkScheduling.install(scheduler);
        try {
            ReflectionUtil.invoke(dependencies,
                    DependenceManagers.class.getDeclaredMethod("initCoreServices", UltiTools.class), plugin);
            Messages messages = root.getBean(Messages.class);
            assertThat(messages).isNotNull();
            assertThat(CommandRuntime.messages()).isSameAs(messages);
            assertThat(CommandMessageTestSupport.plain(CommandRuntime.messages().render(
                    Text.of(CommandKeys.TIMEOUT), Locale.ENGLISH)))
                    .isEqualTo("Command execution timed out; no longer waiting. The command continues running in the background.");
        } finally {
            FrameworkScheduling.uninstall(scheduler);
            root.close();
        }
    }
}
