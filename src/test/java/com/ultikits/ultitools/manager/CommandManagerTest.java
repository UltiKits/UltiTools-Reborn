package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.command.engine.CommandEngine;
import com.ultikits.ultitools.context.SimpleContainer;

/** Manager ownership/routing tests; lifecycle/proxy behaviour is tested with RecordingCommands. */
class CommandManagerTest {
    private CommandEngine engine;
    private CommandManager manager;
    private UltiToolsPlugin module;
    private SimpleContainer container;
    @BeforeEach void setup() {
        engine = mock(CommandEngine.class);
        manager = new CommandManager(engine);
        module = mock(UltiToolsPlugin.class);
        container = new AliasingContainer();
        when(module.getContext()).thenReturn(container);
        when(module.getPluginName()).thenReturn("module");
        when(module.i18n(anyString())).thenAnswer(call -> "translated:" + call.getArgument(0));
    }
    @Test void automaticallyRegistersOnlyEligibleAnnotationBeans() {
        var automatic = new Fixture();
        container.registerSingleton("automatic", automatic);
        container.registerSingleton("manual", new Manual());
        manager.registerAll(module);
        verify(engine).register(module, "module", automatic, "translated:description", "root.permission", false,
                List.of("test", "alias"));
        verifyNoMoreInteractions(engine);
    }
    @Test void aliasesOfTheSameBeanAreRegisteredOnce() {
        var automatic = new Fixture();
        container.registerSingleton("automatic", automatic);
        ((AliasingContainer) container).alias("alias", automatic);
        manager.registerAll(module);
        verify(engine, times(1)).register(eq(module), eq("module"), same(automatic), anyString(), anyString(), anyBoolean(), anyList());
    }
    @Test void theExplicitOverloadResolvesTheModulesOwnInstance() {
        Fixture own = new Fixture();
        container.registerSingleton("own", own);
        manager.register(module, Fixture.class, "custom", "custom description", "custom", "other");
        verify(engine).register(module, "module", own, "translated:custom description", "custom", false,
                List.of("custom", "other"));
    }
    @Test void explicitRegistrationWithoutAliasesUsesTheAnnotationLabels() {
        Fixture own = new Fixture();
        container.registerSingleton("own", own);
        manager.register(module, Fixture.class, "custom", "description");
        verify(engine).register(module, "module", own, "translated:description", "custom", false,
                List.of("test", "alias"));
    }
    @Test void theShortOverloadAllowsManualRegistration() {
        Manual manual = new Manual();
        container.registerSingleton("manual", manual);
        manager.register(module, Manual.class);
        verify(engine).register(module, "module", manual, "translated:", "", false, List.of("manual"));
    }
    @Test void coreCommandsHaveFrameworkOwnershipAndRawDescriptions() {
        var executor = new Fixture();
        manager.registerCoreCommand(executor);
        verify(engine).register("UltiTools", "UltiTools", executor, "description", "root.permission", false,
                List.of("test", "alias"));
    }
    @Test void externalCommandsUseTheirNameAndDoNotTranslateDescriptions() {
        var executor = new Fixture();
        container.registerSingleton("automatic", executor);
        ((AliasingContainer) container).alias("alias", executor);
        var adapter = mock(ExternalPluginAdapter.class);
        when(adapter.getContext()).thenReturn(container);
        when(adapter.getPluginName()).thenReturn("external");
        manager.registerAllExternal(adapter);
        verify(engine).register("external", "external", executor, "description", "root.permission", false,
                List.of("test", "alias"));
        verifyNoMoreInteractions(engine);
    }
    @Test void absentExternalContextHasNoRegistrations() {
        assertThatCode(() -> manager.registerAllExternal(mock(ExternalPluginAdapter.class))).doesNotThrowAnyException();
        verifyNoInteractions(engine);
    }
    @Test void moduleAndExternalUnloadsReleaseOnlyTheirOwnershipTokens() {
        manager.unregisterAll(module);
        manager.unregisterAllExternal("external");
        verify(engine).unregister(module);
        verify(engine).unregister("external");
    }
    @Test void closeReleasesEveryKindOfBinding() {
        manager.close();
        verify(engine).close();
    }
    @Test void labelOwnershipReturnsOnlyModules() {
        when(engine.ownerByLabel("alias")).thenReturn(module);
        when(engine.ownerByLabel("external")).thenReturn("external");
        assertThat(manager.getPluginByLabel("alias")).isSameAs(module);
        assertThat(manager.getPluginByLabel("external")).isNull();
        assertThat(manager.getPluginByLabel("missing")).isNull();
    }
    @CmdExecutor(alias = {"test", "alias"}, description = "description", permission = "root.permission")
    static class Fixture extends BaseCommandExecutor {
        @CmdMapping(format = "ping") public void ping() { /* Registration fixture. */ }
        @Override protected void handleHelp(CommandSender sender) { /* Registration fixture. */ }
    }
    @CmdExecutor(alias = "manual", manualRegister = true)
    static class Manual extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { /* Registration fixture. */ }
    }
    static class AliasingContainer extends SimpleContainer {
        void alias(String name, Object instance) { addSingleton(name, instance); }
    }
}
