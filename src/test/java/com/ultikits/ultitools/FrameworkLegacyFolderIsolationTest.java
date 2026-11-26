package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameworkLegacyFolderIsolationTest {
    @TempDir Path temporary;

    @Test
    void startupTextInitializationDoesNotCreateALegacyFolder() throws Throwable {
        Path data = Files.createDirectories(temporary.resolve("plugins/UltiTools"));
        initializeStartupText(data);
        assertThat(data.resolve("lang")).doesNotExist();
    }

    @Test
    void startupTextInitializationLeavesAnExistingLegacyFileUntouched() throws Throwable {
        Path data = temporary.resolve("plugins/UltiTools");
        Path legacy = Files.createDirectories(data.resolve("lang")).resolve("zh.json");
        byte[] edited = "{\"Module '%s' reloaded.\":\"operator edit\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(legacy, edited);
        Files.setLastModifiedTime(legacy, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        FileTime originalTime = Files.getLastModifiedTime(legacy);

        initializeStartupText(data);

        assertThat(legacy).hasBinaryContent(edited);
        assertThat(Files.getLastModifiedTime(legacy)).isEqualTo(originalTime);
    }

    private static void initializeStartupText(Path data) throws Throwable {
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "auto");
        config.set("fallback-language", "zh");
        Logger logger = Logger.getAnonymousLogger();
        ConfiguredLocaleResolver resolver = ConfiguredLocaleResolver.forConfig(() -> config, logger);
        SimpleContainer root = new SimpleContainer();
        try (TextEngine engine = new TextEngine(resolver, logger)) {
            root.registerSingleton("textEngine", engine);
            DependenceManagers managers = mock(DependenceManagers.class);
            doReturn(root).when(managers).getContext();
            UltiTools core = mock(UltiTools.class, CALLS_REAL_METHODS);
            doReturn(config).when(core).getConfig();
            doReturn(data.toFile()).when(core).getDataFolder();
            doReturn(logger).when(core).getLogger();
            doReturn(managers).when(core).getDependenceManagers();

            MethodHandles.privateLookupIn(UltiTools.class, MethodHandles.lookup())
                    .findVirtual(UltiTools.class, "initializeTextFallback", MethodType.methodType(void.class)).invokeExact(core);
            assertThat(engine.fallback()).isEqualTo(Locale.CHINESE);
        } finally {
            root.close();
        }
    }
}
