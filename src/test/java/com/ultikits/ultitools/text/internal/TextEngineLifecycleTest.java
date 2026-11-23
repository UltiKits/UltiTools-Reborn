package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.text.MessageKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextEngineLifecycleTest {
    @TempDir Path temporary;
    private TextEngine engine;
    private final List<LogRecord> diagnostics = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { diagnostics.add(record); }
            @Override public void flush() { /* In-memory records only. */ }
            @Override public void close() { /* No resources owned. */ }
        });
        engine = new TextEngine(new ConfiguredLocaleResolver(() -> "auto", () -> "zh", logger), logger);
    }

    @AfterEach
    void tearDown() { engine.close(); }

    @Test
    void prepare_isReadOnlyAndDefersDiagnosticsUntilPublish() throws Exception {
        Path source = source("first", "greeting: Hello\nbad: 123\n");
        Path overrides = temporary.resolve("operator/messages");
        var prepared = engine.prepare("sample", "Sample", source.toUri().toURL(), overrides, Locale.ENGLISH);
        assertThat(engine.catalogue("sample")).isNull();
        assertThat(overrides).doesNotExist();
        assertThat(diagnostics).isEmpty();
        engine.publish(prepared, this);
        assertThat(text()).isEqualTo("Hello");
        assertThat(diagnostics).hasSize(1);
        engine.publish(prepared, this);
        assertThat(diagnostics).hasSize(1);
    }

    @Test
    void replacement_oldOwnerCannotWithdrawNewCatalogue() throws Exception {
        Object older = new Object();
        Object newer = new Object();
        engine.publish(prepare("old", "greeting: Old\n"), older);
        engine.publish(prepare("new", "greeting: New\n"), newer);
        engine.withdraw("sample", older);
        assertThat(text()).isEqualTo("New");
        engine.withdraw("sample", newer);
        assertThat(engine.catalogue("sample")).isNull();
    }

    @Test
    void differentModuleNamesCannotShareANamespaceAndFrameworkNameIsReserved() throws Exception {
        var accepted = engine.prepare("sample_", "Sample!", source("one", "greeting: Hello\n").toUri().toURL(),
                temporary.resolve("override-one"), Locale.ENGLISH);
        engine.publish(accepted, this);
        assertThatThrownBy(() -> engine.prepare("sample_", "Sample?", null,
                temporary.resolve("override-two"), Locale.ENGLISH))
                .isInstanceOf(PluginModuleException.class).hasMessageContaining("Sample!", "Sample?", "sample_");
        assertThatThrownBy(() -> engine.prepare("ultitools", "UltiTools", null,
                temporary.resolve("override-core"), Locale.ENGLISH))
                .isInstanceOf(PluginModuleException.class).hasMessageContaining("UltiTools framework", "ultitools");
    }

    private TextEngine.Prepared prepare(String folder, String contents) throws Exception {
        return engine.prepare("sample", "Sample", source(folder, contents).toUri().toURL(),
                temporary.resolve("operator/messages"), Locale.ENGLISH);
    }

    private Path source(String name, String contents) throws Exception {
        Path root = temporary.resolve(name);
        Files.createDirectories(root.resolve("messages"));
        Files.writeString(root.resolve("messages/en.yml"), contents);
        return root;
    }

    private String text() {
        return PlainTextComponentSerializer.plainText().serialize(
                engine.messagesFor("sample").render(MessageKey.of("sample", "greeting"), Locale.US));
    }
}
