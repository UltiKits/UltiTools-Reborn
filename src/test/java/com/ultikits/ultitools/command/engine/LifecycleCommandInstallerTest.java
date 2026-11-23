package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;

class LifecycleCommandInstallerTest {
    @Test void installationIsIdempotent() {
        LifecycleEventManager<Plugin> manager = mock(LifecycleEventManager.class);
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        var installer = new LifecycleCommandInstaller(() -> manager, bindings, Logger.getAnonymousLogger());
        installer.ensureInstalled();
        installer.ensureInstalled();
        verify(manager, times(1)).registerEventHandler(any(LifecycleEventHandlerConfiguration.class));
        assertThat(installer.isInstalled()).isTrue();
    }
    @Test void aLifecycleRefusalIsLoggedAndCanBeRetried() {
        LifecycleEventManager<Plugin> manager = mock(LifecycleEventManager.class);
        doThrow(new IllegalStateException("outside onEnable")).doNothing()
                .when(manager).registerEventHandler(any(LifecycleEventHandlerConfiguration.class));
        Logger logger = mock(Logger.class);
        var installer = new LifecycleCommandInstaller(() -> manager, new CommandBindings(logger, () -> {}), logger);
        assertThatCode(installer::ensureInstalled).doesNotThrowAnyException();
        assertThat(installer.isInstalled()).isFalse();
        verify(logger).severe(contains("outside onEnable"));
        installer.ensureInstalled();
        assertThat(installer.isInstalled()).isTrue();
    }
}
