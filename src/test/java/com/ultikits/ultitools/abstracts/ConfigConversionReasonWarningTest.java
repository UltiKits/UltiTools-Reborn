package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConfigConverterFor;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.utils.PackageScanUtils;

/**
 * #590: when a converter rejects a value, the operator's skip warning carries the converter's
 * reason, redacted under the same secret rules as the value and kept on one line.
 */
@DisplayName("A converter's rejection reason reaches the operator's skip warning (#590)")
class ConfigConversionReasonWarningTest {
    private static final String PATH = "config/reason.yml";
    private static final String REASON = "Expected only a text label containing text or null";

    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;
    private Values loaded;

    /** A module value type with its own converter. */
    public static final class Label {
        final String text;
        Label(String text) { this.text = text; }
    }

    /** Rejects anything that is not text, with the documented reason. */
    @ConfigConverterFor(Label.class)
    public static class LabelConverter implements ConfigConverter<Label> {
        @Override
        public Object toPlain(Label value, ConversionContext ctx) { return value.text; }
        @Override
        public Label fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
            if (plain instanceof String) { return new Label((String) plain); }
            if ("multi".equals(String.valueOf(plain instanceof Map ? ((Map<?, ?>) plain).get("kind") : null))) {
                throw new ConversionException("first line\nFORGED-SEVERE second line", ctx.file(), ctx.path(), ctx.declaredType());
            }
            if (plain instanceof Map && ((Map<?, ?>) plain).containsKey("leak")) {
                throw new ConversionException("rejected " + ((Map<?, ?>) plain).get("leak"), ctx.file(), ctx.path(), ctx.declaredType());
            }
            throw new ConversionException(REASON, ctx.file(), ctx.path(), ctx.declaredType());
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "title") Label title = new Label("default");
        @ConfigEntry(path = "labels") Map<String, Label> labels = new LinkedHashMap<>();
        @ConfigEntry(path = "limits") Map<String, Integer> limits = new LinkedHashMap<>();
        @ConfigEntry(path = "auth-label") Label authLabel = new Label("hidden");
        public Values(String path) { super(path); }
    }

    @BeforeEach
    void setUp() {
        plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ReasonModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
    }

    private List<String> loadWarnings(String yaml) throws IOException {
        // Supply every fixture key: missing-key insertion is unrelated to diagnostic rendering.
        String completeYaml = yaml;
        String[] defaults = {"title: default\n", "labels: {}\n", "limits: {}\n", "auth-label: hidden\n"};
        for (String entry : defaults) {
            String key = entry.substring(0, entry.indexOf(':') + 1);
            if (!completeYaml.startsWith(key) && !completeYaml.contains("\n" + key)) { completeYaml += entry; }
        }
        Path file = tempDir.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.write(file, completeYaml.getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<PackageScanUtils> scanner = mockStatic(PackageScanUtils.class)) {
            scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                    .thenAnswer(call -> {
                        Class<? extends Annotation> annotation = call.getArgument(0);
                        return annotation == ConfigConverterFor.class
                                ? Collections.singleton(LabelConverter.class) : Collections.emptySet();
                    });
            ConverterRegistry.prepareModule(plugin, new String[]{"fixture.reason"}, getClass().getClassLoader());
        }
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            loaded = new Values(PATH);
            loaded.init(plugin);
            assertThat(Files.readAllBytes(file)).isEqualTo(completeYaml.getBytes(StandardCharsets.UTF_8));
            return warnings.messages();
        }
    }

    @Test
    @DisplayName("a whole-field rejection names the converter's reason")
    void wholeFieldRejectionCarriesTheReason() throws IOException {
        List<String> warnings = loadWarnings("title:\n  nested: 1\n");
        assertThat(warnings).filteredOn(line -> line.contains("'title'")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains(PATH, REASON, "skipped or using the declared default"));
    }

    @Test
    @DisplayName("a rejected map entry names the converter's reason")
    void mapEntryRejectionCarriesTheReason() throws IOException {
        List<String> warnings = loadWarnings("labels:\n  good: text\n  bad: [1, 2]\n");
        assertThat(warnings).filteredOn(line -> line.contains("labels.bad")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains(REASON));
    }

    @Test
    @DisplayName("a built-in converter's reason is shown too")
    void builtInReasonIsShown() throws IOException {
        List<String> warnings = loadWarnings("limits:\n  a: 1\n  c: lots\n");
        assertThat(warnings).filteredOn(line -> line.contains("limits.c")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("lots", "Number is not exactly representable"));
    }

    @Test
    @DisplayName("a reason under a secret-shaped key is redacted with the value")
    void secretReasonIsRedacted() throws IOException {
        List<String> warnings = loadWarnings("auth-label:\n  leak: secret-specimen\n");
        assertThat(warnings).filteredOn(line -> line.contains("auth-label")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("<redacted>").doesNotContain("secret-specimen"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\r", "\\n", "\\r\\n", "\\t", "\\0", "\\x7f", "\\N", "\\L", "\\P"})
    @DisplayName("raw field and entry specimens use the reason's single-line policy (#593)")
    void rawSpecimensStayOnOneLine(String yamlEscape) throws IOException {
        String specimen = "\"first" + yamlEscape + "second\"";
        List<String> warnings = loadWarnings("title: {nested: " + specimen + "}\n"
                + "limits: {good: 1, bad: " + specimen + "}\n");
        assertThat(warnings).filteredOn(line -> line.contains("'title'") || line.contains("limits.bad"))
                .hasSize(2).allSatisfy(line -> {
                    assertThat(line).contains(PATH, "first second", "(reason: ",
                            "skipped or using the declared default");
                    assertThat(line).doesNotContainPattern("[\\p{Cntrl}\\u0085\\u2028\\u2029]");
                });
        assertThat(loaded.title.text).isEqualTo("default");
        assertThat(loaded.limits).containsEntry("good", 1).doesNotContainKey("bad");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\r", "\\n", "\\r\\n", "\\t", "\\0", "\\x7f", "\\N", "\\L", "\\P"})
    @DisplayName("an escaped map key in the displayed key also stays on one line (#825)")
    void escapedMapKeysStayOnOneLine(String yamlEscape) throws IOException {
        List<String> warnings = loadWarnings("limits: {good: 1, \"first" + yamlEscape + "second\": lots}\n");
        assertThat(warnings).filteredOn(line -> line.contains("limits.first"))
                .singleElement().satisfies(line -> {
                    assertThat(line).contains(PATH, "limits.first second", "skipped or using the declared default");
                    assertThat(line).doesNotContainPattern("[\\p{Cntrl}\\u0085\\u2028\\u2029]");
                });
        assertThat(loaded.limits).containsEntry("good", 1).hasSize(1);
    }

    @Test
    @DisplayName("block scalar warnings are single-line without changing accepted values or file bytes")
    void blockScalarIsOnlyNormalizedInWarnings() throws IOException {
        List<String> warnings = loadWarnings("title: |-\n  first\n  second\n"
                + "limits:\n  bad: |-\n    first\n    second\n");
        assertThat(loaded.title.text).isEqualTo("first\nsecond");
        assertThat(warnings).filteredOn(line -> line.contains("limits.bad")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("first second")
                        .doesNotContainPattern("[\\p{Cntrl}\\u0085\\u2028\\u2029]"));
    }

    @Test
    @DisplayName("multi-line secret keys and nested credentials remain fully redacted")
    void multilineSecretsRemainRedacted() throws IOException {
        List<String> warnings = loadWarnings("auth-label: {leak: \"secret-first\\nsecret-second\"}\n"
                + "title: {password: \"nested-first\\n nested-second\"}\n");
        assertThat(warnings).filteredOn(line -> line.contains("auth-label") || line.contains("'title'"))
                .hasSize(2).allSatisfy(line -> assertThat(line).contains("<redacted>")
                        .doesNotContain("secret-first", "secret-second", "nested-first", "nested-second")
                        .doesNotContainPattern("[\\p{Cntrl}\\u0085\\u2028\\u2029]"));
        assertThat(loaded.authLabel.text).isEqualTo("hidden");
    }

    @Test
    @DisplayName("a multi-line reason stays on one log line")
    void reasonStaysOnOneLine() throws IOException {
        List<String> warnings = loadWarnings("title:\n  kind: multi\n");
        assertThat(warnings).filteredOn(line -> line.contains("'title'")).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("first line", "FORGED-SEVERE second line")
                        .doesNotContain("\n").doesNotContain("\r"));
    }
}
