package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.ultikits.ultitools.UltiTools;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UltiToolsPluginTextWiringTest {
    @TempDir Path temporary;

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void frameworkLegacyAuto_keepsTheOperatorFallbackLanguage() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("fallback-language", "zh");
        UltiTools core = mock(UltiTools.class, CALLS_REAL_METHODS);
        doReturn(config).when(core).getConfig();
        doReturn(temporary.toFile()).when(core).getDataFolder();
        doReturn(Logger.getAnonymousLogger()).when(core).getLogger();
        Method initialize = UltiTools.class.getDeclaredMethod("initLanguage");
        initialize.setAccessible(true); // NOPMD - Exercise the private legacy startup seam with real resources.
        String key = "Module '%s' reloaded.";
        config.set("language", "en");
        initialize.invoke(core);
        assertThat(core.i18n(key)).as("existing explicit English remains byte-for-byte unchanged").isEqualTo(key);
        config.set("language", "zh");
        initialize.invoke(core);
        assertThat(core.i18n(key)).as("existing explicit Chinese remains byte-for-byte unchanged")
                .isEqualTo("模块 '%s' 已重载。");
        config.set("language", "auto");
        initialize.invoke(core);
        assertThat(core.i18n(key)).as("auto must keep the configured Chinese fallback")
                .isEqualTo("模块 '%s' 已重载。");
        config.set("fallback-language", null);
        initialize.invoke(core);
        assertThat(core.i18n(key)).as("auto without fallback uses English").isEqualTo(key);
    }
}
