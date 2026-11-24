package com.ultikits.ultitools.command.engine;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

@ExtendWith(CommandTestExtension.class)
class LifecycleCommandInstallerTest {
    @Test void installationIsIdempotent() {
        CommandTestSupport.PluginLifecycle manager = mock(CommandTestSupport.PluginLifecycle.class);
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        var installer = new LifecycleCommandInstaller(() -> manager, bindings, Logger.getAnonymousLogger());
        installer.ensureInstalled();
        installer.ensureInstalled();
        verify(manager, times(1)).registerEventHandler(eq(LifecycleEvents.COMMANDS), any(LifecycleEventHandler.class));
        assertThat(installer.isInstalled()).isTrue();
    }
    @Test void aLifecycleRefusalIsLoggedAndCanBeRetried() {
        CommandTestSupport.PluginLifecycle manager = mock(CommandTestSupport.PluginLifecycle.class);
        doThrow(new IllegalStateException("outside onEnable")).doNothing()
                .when(manager).registerEventHandler(eq(LifecycleEvents.COMMANDS), any(LifecycleEventHandler.class));
        Logger logger = mock(Logger.class);
        var installer = new LifecycleCommandInstaller(() -> manager, new CommandBindings(logger, () -> {}), logger);
        assertThatCode(installer::ensureInstalled).doesNotThrowAnyException();
        assertThat(installer.isInstalled()).isFalse();
        verify(logger).severe(contains("outside onEnable"));
        installer.ensureInstalled();
        assertThat(installer.isInstalled()).isTrue();
    }
}
