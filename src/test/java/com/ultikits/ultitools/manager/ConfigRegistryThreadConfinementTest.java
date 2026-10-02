package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Every public registry entry refuses before accessing live registry structure off the server thread. */
class ConfigRegistryThreadConfinementTest {
    @TempDir Path directory;
    private ConfigManager manager;
    private UltiToolsPlugin owner;
    private Values entity;
    @BeforeEach void setup() throws Exception {
        manager = new ConfigManager(); owner = mock(UltiToolsPlugin.class);
        lenient().when(owner.getPluginName()).thenReturn("RegistryModule");
        lenient().when(owner.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(owner, directory.toFile());
        entity = spy(new Values("registry.yml")); manager.register(owner, entity);
        clearInvocations(entity);
    }
    @ParameterizedTest
    @ValueSource(strings = {"register", "package", "packages", "unregister", "save"})
    void voidEntryWarnsAndDoesNoWork(String operation) throws Exception {
        Logger logger = mock(Logger.class);
        try (MockedStatic<Bukkit> bukkit = offThread(logger)) {
            switch (operation) {
                case "register": manager.register(owner, new Values("extra.yml")); break;
                case "package": manager.registerAll(owner, "invalid.scan", getClass().getClassLoader()); break;
                case "packages": manager.registerAll(owner, new String[]{"invalid.scan"}, getClass().getClassLoader()); break;
                case "unregister": manager.unregisterAll(owner); break;
                case "save": manager.saveAll(); break;
                default: throw new AssertionError(operation);
            }
            verify(logger).log(eq(Level.WARNING), contains(Thread.currentThread().getName()));
            verifyNoInteractions(entity);
        }
        assertThat(manager.getAllConfigEntities(owner)).containsOnlyKeys("registry.yml");
    }
    @ParameterizedTest
    @ValueSource(strings = {"byType", "byPath", "list", "all", "json", "comments", "panel", "single"})
    void valueAndWriteEntriesRefuseExplicitlyRatherThanReturningFalseSuccess(String operation) throws Exception {
        Logger logger = mock(Logger.class);
        try (MockedStatic<Bukkit> bukkit = offThread(logger)) {
            assertThatThrownBy(() -> {
                switch (operation) {
                    case "byType": manager.getConfigEntity(owner, Values.class); break;
                    case "byPath": manager.getConfigEntity(owner, "registry.yml", Values.class); break;
                    case "list": manager.getConfigEntities(owner, Values.class); break;
                    case "all": manager.getAllConfigEntities(owner); break;
                    case "json": manager.toJson(); break;
                    case "comments": manager.getComments(); break;
                    case "panel": manager.loadFromJson("{}"); break;
                    case "single": manager.loadFromJson("registry.yml", "{}"); break;
                    default: throw new AssertionError(operation);
                }
            }).isInstanceOf(IllegalStateException.class).hasMessageContaining("server thread");
            verify(logger).log(eq(Level.WARNING), contains(Thread.currentThread().getName()));
            verifyNoInteractions(entity);
        }
    }
    @Test void returnedRegistryMapCannotMutateOrObserveLaterRegistryChanges() throws Exception {
        Map<String, AbstractConfigEntity> snapshot = manager.getAllConfigEntities(owner);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        manager.unregisterAll(owner);
        assertThat(snapshot).containsEntry("registry.yml", entity);
        assertThat(manager.getAllConfigEntities(owner)).isNull();
    }
    private MockedStatic<Bukkit> offThread(Logger logger) {
        MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class);
        when(Bukkit.getServer()).thenReturn(mock(Server.class));
        when(Bukkit.isPrimaryThread()).thenReturn(false); when(Bukkit.getLogger()).thenReturn(logger);
        return bukkit;
    }
    @ConfigEntity("registry.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry String value = "default";
        public Values(String path) { super(path); }
    }
}
