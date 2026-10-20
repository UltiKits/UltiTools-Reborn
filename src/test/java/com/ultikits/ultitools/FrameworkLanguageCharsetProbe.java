package com.ultikits.ultitools;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.mockito.Mockito;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Run in a child JVM by {@link FrameworkLanguageFileTest} with a non-UTF-8 default charset: builds the
 * framework language twice (official {@code zh}, then the custom {@code zh-myserver} in the data folder
 * given as the only argument) and prints the default charset and both texts of one key, Base64-encoded
 * as UTF-8 so the child's own output encoding cannot change them.
 */
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.SystemPrintln"}) // a probe main reporting to its parent
public final class FrameworkLanguageCharsetProbe {

    private static final String KEY = "Module '%s' reloaded.";

    private FrameworkLanguageCharsetProbe() {
    }

    public static void main(String[] args) throws Exception {
        // Run only by FrameworkLanguageFileTest, whose only argument is that test's @TempDir data folder.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        File dataFolder = new File(args[0]);
        YamlConfiguration config = new YamlConfiguration();
        Logger logger = Mockito.mock(Logger.class);
        UltiTools ultiTools = TestHelper.mockUltiToolsInstance(mock -> {
            Mockito.lenient().when(mock.getConfig()).thenReturn(config);
            Mockito.lenient().when(mock.getDataFolder()).thenReturn(dataFolder);
            Mockito.lenient().when(mock.getLogger()).thenReturn(logger);
        });
        System.out.println("CHARSET=" + Charset.defaultCharset().name());
        config.set("language", "zh");
        System.out.println("OFFICIAL=" + encoded(load(ultiTools).getLocalizedText(KEY)));
        config.set("language", "zh-myserver");
        System.out.println("CUSTOM=" + encoded(load(ultiTools).getLocalizedText(KEY)));
        System.exit(0);
    }

    private static Language load(UltiTools ultiTools) throws Exception {
        Method initLanguage = UltiTools.class.getDeclaredMethod("initLanguage");
        initLanguage.setAccessible(true);
        initLanguage.invoke(ultiTools);
        Field languageField = UltiTools.class.getDeclaredField("language");
        languageField.setAccessible(true);
        return (Language) languageField.get(ultiTools);
    }

    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
