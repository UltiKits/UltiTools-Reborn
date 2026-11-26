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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.command.engine.CommandEngine;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.text.ModuleCatalogueFixture;

/** Manager ownership/routing tests; lifecycle/proxy behaviour is tested with RecordingCommands. */
class CommandManagerTest {
    private static final String DESCRIPTION = "command.probe.description";
    private static final String EXPLICIT = "command.probe.explicit";
    private static final String SENTENCE = "Run this command";
    @TempDir Path tempDir;
    private ModuleCatalogueFixture catalogue;
    private CommandEngine engine;
    private CommandManager manager;
    private UltiToolsPlugin module;
    private SimpleContainer container;
    @BeforeEach void setup() throws Exception {
        engine = mock(CommandEngine.class);
        manager = new CommandManager(engine);
        module = mock(UltiToolsPlugin.class);
        container = new AliasingContainer();
        when(module.getContext()).thenReturn(container);
        when(module.getPluginName()).thenReturn("module");
        catalogue = new ModuleCatalogueFixture(tempDir);
        catalogue.shipped("en", Map.of(DESCRIPTION, "Run the probe", EXPLICIT, "Explicit description"));
        catalogue.shipped("zh", Map.of(DESCRIPTION, "<green>运行探针</green>", EXPLICIT, "显式命令说明"));
        catalogue.fallback("zh");
        when(module.moduleTexts()).thenReturn(Optional.of(catalogue.preparedTexts()));
    }
    @AfterEach void closeCatalogue() {
        catalogue.close();
    }
    @Test void automaticallyRegistersOnlyEligibleAnnotationBeans() {
        var automatic = new Fixture();
        container.registerSingleton("automatic", automatic);
        container.registerSingleton("manual", new Manual());
        manager.registerAll(module);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine).register(eq(module), eq("module"), same(automatic), description.capture(), eq("root.permission"),
                eq(false), eq(List.of("test", "alias")));
        assertThat(description.getValue()).isEqualTo("运行探针");
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
        manager.register(module, Fixture.class, "custom", EXPLICIT, "custom", "other");
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine).register(eq(module), eq("module"), same(own), description.capture(), eq("custom"), eq(false),
                eq(List.of("custom", "other")));
        assertThat(description.getValue()).isEqualTo("显式命令说明");
    }
    @Test void explicitRegistrationWithoutAliasesUsesTheAnnotationLabels() {
        Fixture own = new Fixture();
        container.registerSingleton("own", own);
        manager.register(module, Fixture.class, "custom", DESCRIPTION);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine).register(eq(module), eq("module"), same(own), description.capture(), eq("custom"), eq(false),
                eq(List.of("test", "alias")));
        assertThat(description.getValue()).isEqualTo("运行探针");
    }
    @Test void theShortOverloadAllowsManualRegistration() {
        Manual manual = new Manual();
        container.registerSingleton("manual", manual);
        manager.register(module, Manual.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine).register(eq(module), eq("module"), same(manual), description.capture(), eq(""), eq(false),
                eq(List.of("manual")));
        assertThat(description.getValue()).isEmpty();
    }
    @Test void aPlainSentenceRemainsTheDescriptionInEitherRegistrationPath() {
        var literal = new Literal();
        container.registerSingleton("literal", literal);
        manager.registerAll(module);
        manager.register(module, Literal.class, "", SENTENCE, "literal");
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine, times(2)).register(eq(module), eq("module"), same(literal), description.capture(), eq(""),
                eq(false), eq(List.of("literal")));
        assertThat(description.getAllValues()).containsExactly(SENTENCE, SENTENCE);
    }
    @Test void coreCommandsHaveFrameworkOwnershipAndRawDescriptions() {
        var executor = new Fixture();
        manager.registerCoreCommand(executor);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine).register(eq("UltiTools"), eq("UltiTools"), same(executor), description.capture(),
                eq("root.permission"), eq(false), eq(List.of("test", "alias")));
        assertThat(description.getValue()).isEqualTo(DESCRIPTION);
    }
    @Test void externalCommandsUseTheirNameAndDoNotTranslateDescriptions() {
        var executor = new Fixture();
        container.registerSingleton("automatic", executor);
        ((AliasingContainer) container).alias("alias", executor);
        var adapter = mock(ExternalPluginAdapter.class);
        when(adapter.getContext()).thenReturn(container);
        when(adapter.getPluginName()).thenReturn("external");
        manager.registerAllExternal(adapter);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(engine).register(eq("external"), eq("external"), same(executor), description.capture(),
                eq("root.permission"), eq(false), eq(List.of("test", "alias")));
        assertThat(description.getValue()).isEqualTo(DESCRIPTION);
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
    @CmdExecutor(alias = {"test", "alias"}, description = DESCRIPTION, permission = "root.permission")
    static class Fixture extends BaseCommandExecutor {
        @CmdMapping(format = "ping") public void ping() { /* Registration fixture. */ }
        @Override protected void handleHelp(CommandSender sender) { /* Registration fixture. */ }
    }
    @CmdExecutor(alias = "literal", description = SENTENCE)
    static class Literal extends BaseCommandExecutor {
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
