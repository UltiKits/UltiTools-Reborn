package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.testfixtures.conditionaltwoforms.PlainSiblingService;
import com.ultikits.testfixtures.conditionaltwoforms.TwoFormsConditionalService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/**
 * A {@code @ConditionalOnConfig} path written in two forms refuses the module during the component scan, even when no
 * config entity declares that path, instead of being logged as "Failed to scan package" while the module loads (#612,
 * gate-1 R613-01).
 */
class ComponentScannerConditionalTwoFormsTest {
    private static final String PACKAGE = "com.ultikits.testfixtures.conditionaltwoforms";
    @TempDir Path tempDir;
    private SimpleContainer container;
    private UltiToolsPlugin plugin;

    @BeforeEach void setUp() {
        container = new SimpleContainer();
        plugin = mock(UltiToolsPlugin.class);
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        container.registerType(UltiToolsPlugin.class, plugin);
    }

    @AfterEach void tearDown() {
        ConditionalRegistrationEvaluator.clear(plugin);
        container.close();
    }

    private void write(String text) throws IOException {
        Path file = tempDir.resolve("config/twoforms.yml");
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void aConditionalPathWrittenInTwoFormsRefusesTheScan() throws IOException {
        write("features.chat: false\nfeatures:\n  chat: true\n");
        assertThatThrownBy(() -> new ComponentScanner(container).scanPackage(PACKAGE))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("config" + File.separator + "twoforms.yml").hasMessageContaining("'features.chat'")
                .hasMessageContaining("two forms")
                .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("true").doesNotContain("false"));
    }

    @Test void aFlatConditionalPathScansNormally() throws IOException {
        write("features.chat: true\n");
        new ComponentScanner(container).scanPackage(PACKAGE);
        assertThat(container.getBeanNamesForType(TwoFormsConditionalService.class)).isNotEmpty();
        assertThat(container.getBeanNamesForType(PlainSiblingService.class)).isNotEmpty();
    }
}
