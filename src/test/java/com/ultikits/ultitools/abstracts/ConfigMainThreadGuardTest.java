package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Off-thread guards refuse before acquiring an entity monitor; no-server harnesses remain supported. */
class ConfigMainThreadGuardTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    private ConfigManager manager;
    private Values entity;

    @BeforeEach void setup() throws Exception {
        plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("GuardModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        manager = new ConfigManager(); entity = new Values("guard.yml"); manager.register(plugin, entity);
        Files.write(directory.resolve("guard.yml"), "value: operator\n".getBytes(StandardCharsets.UTF_8));
        entity.value = "memory";
    }

    @ParameterizedTest
    @ValueSource(strings = {"init", "reload", "reloadConfigs", "reloadSelf"})
    void offThreadEntryWarnsOnceAndChangesNothing(String operation) throws Exception {
        Logger logger = mock(Logger.class);
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenReturn(false);
            when(Bukkit.getLogger()).thenReturn(logger);
            assertThatCode(() -> invoke(operation)).doesNotThrowAnyException();
            assertThat(entity.value).isEqualTo("memory");
            assertThat(new String(Files.readAllBytes(directory.resolve("guard.yml")), StandardCharsets.UTF_8))
                    .isEqualTo("value: operator\n");
            ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
            Mockito.verify(logger).log(Mockito.eq(Level.WARNING), warning.capture());
            assertThat(warning.getValue()).contains("GuardModule", Thread.currentThread().getName());
            if (operation.equals("init") || operation.equals("reload")) {
                assertThat(warning.getValue()).contains("guard.yml");
            }
        }
    }

    @Test void guardRunsBeforeMonitorAndCannotWaitForServerThreadHoldingEntity() throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
                when(Bukkit.getServer()).thenReturn(mock(Server.class));
                when(Bukkit.isPrimaryThread()).thenReturn(false);
                when(Bukkit.getLogger()).thenReturn(mock(Logger.class));
                entity.reload(); finished.complete(null);
            } catch (Exception failure) { finished.completeExceptionally(failure); }
        }, "panel-guard-probe");
        synchronized (entity) {
            worker.start();
            assertThatCode(() -> finished.get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
        }
        worker.join(2000);
    }

    @Test void noServerAndServerThreadReloadStillApplyDisk() throws Exception {
        entity.reload(); assertThat(entity.value).isEqualTo("operator");
        Files.write(directory.resolve("guard.yml"), "value: next\n".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            when(Bukkit.getServer()).thenReturn(mock(Server.class));
            when(Bukkit.isPrimaryThread()).thenReturn(true);
            entity.reload(); assertThat(entity.value).isEqualTo("next");
        }
    }

    private void invoke(String operation) throws Exception {
        switch (operation) {
            case "init": entity.init(plugin); break;
            case "reload": entity.reload(); break;
            case "reloadConfigs": manager.reloadConfigs(plugin); break;
            case "reloadSelf": Mockito.doCallRealMethod().when(plugin).reloadSelf(); plugin.reloadSelf(); break;
            default: throw new AssertionError(operation);
        }
    }
    @ConfigEntity("guard.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry String value = "default";
        public Values(String path) { super(path); }
    }
}
