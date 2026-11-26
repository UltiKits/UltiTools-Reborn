package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleTextsTest {
    private static final String NAMESPACE = "fixture";
    private static final String PATH = "command.probe.description";
    private static final String ENGLISH = "Run the probe";
    private static final String CHINESE = "运行探针";
    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @TempDir Path temp;

    @Test
    void preparedCatalogueIncludesOverridesBeforePublication() throws Exception {
        Path jar = moduleJar(Map.of("en", PATH + ": '<green>" + ENGLISH + "</green>'\n"));
        Path overrides = Files.createDirectory(temp.resolve("overrides"));
        Files.writeString(overrides.resolve("en.yml"), PATH + ": '<bold>Override</bold> \\<name>'\n");
        try (var engine = engine(() -> "en")) {
            ModuleTexts texts = new ModuleTexts(engine, prepare(engine, jar, overrides));
            assertThat(engine.catalogue(NAMESPACE)).isNull();
            assertThat(texts.resolve(PATH)).contains("Override <name>");
            assertThat(overrides.resolve("reference")).doesNotExist();
        }
    }

    @Test
    void publishedCatalogueWinsAndTracksOverridesAndFallback() throws Exception {
        Path jar = moduleJar(Map.of("en", PATH + ": '<green>" + ENGLISH + "</green>'\n",
                "zh", PATH + ": '<gold>" + CHINESE + "</gold>'\n"));
        Path overrides = Files.createDirectory(temp.resolve("overrides"));
        var fallback = new AtomicReference<>("en");
        var resolver = new ConfiguredLocaleResolver(() -> "auto", fallback::get, LOGGER);
        try (var engine = new TextEngine(resolver, LOGGER)) {
            var prepared = prepare(engine, jar, overrides);
            var texts = new ModuleTexts(engine, prepared);
            engine.publish(prepared, new Object());
            assertThat(texts.resolve(PATH)).contains(ENGLISH);

            Files.writeString(overrides.resolve("en.yml"), PATH + ": '<bold>Updated</bold> \\<name>'\n");
            engine.reloadOverrides(NAMESPACE);
            assertThat(texts.resolve(PATH)).contains("Updated <name>");
            assertMatchesMessages(engine, texts, PATH);

            fallback.set("zh-HK");
            resolver.reload();
            assertThat(texts.resolve(PATH)).contains(CHINESE);
            assertMatchesMessages(engine, texts, PATH);
        }
    }

    @Test
    void followsTheSourceLocaleChainRatherThanAnyAvailableLanguage() throws Exception {
        Path jar = moduleJar(Map.of("fr_CA", "", "fr", PATH + ": Texte source\n", "de", "command.only_de: Deutsch\n"));
        try (var engine = engine(() -> "en")) {
            var prepared = engine.prepare(NAMESPACE, "Fixture", jar.toUri().toURL(), temp.resolve("overrides"), "fr-CA");
            var texts = new ModuleTexts(engine, prepared);
            engine.publish(prepared, new Object());
            assertThat(texts.resolve(PATH)).contains("Texte source");
            assertMatchesMessages(engine, texts, PATH);
            assertThat(texts.resolve("command.only_de")).isEmpty();
            assertThat(texts.resolve("command.never_defined")).isEmpty();
        }
    }

    @Test
    void invalidPathsDoNotReachTheCatalogue() {
        var engine = mock(TextEngine.class);
        var catalogue = new Catalogue.Builder(NAMESPACE, Locale.ENGLISH).build();
        var prepared = new TextEngine.Prepared("Fixture", null, temp,
                new CatalogueLoader.Loaded(catalogue, List.of(), Map.of()));
        var texts = new ModuleTexts(engine, prepared);
        for (String name : List.of("Upper.Case", "Ordinary sentence", "中文句子", "a..b", "", "a".repeat(129))) {
            assertThat(texts.resolve(name)).as(name).isEmpty();
            assertThat(texts.shippedTemplates(name)).as(name).isEmpty();
        }
        verifyNoInteractions(engine);
    }

    @Test
    void shippedTemplatesUseEveryJarLocaleInOrderWithoutOperatorEntriesOrWrites() throws Exception {
        Path jar = moduleJar(Map.of("en", PATH + ": '<green>" + ENGLISH + "</green>'\n",
                "fr", PATH + ": '<bold>" + ENGLISH + "</bold>'\n",
                "ja", PATH + ": Probe in Japanese\n",
                "zh", "command:\n  probe:\n    description: '<yellow>" + CHINESE + "</yellow>'\n"));
        Path overrides = Files.createDirectory(temp.resolve("overrides"));
        Path file = overrides.resolve("zh.yml");
        Files.writeString(file, PATH + ": Operator override\n");
        Path legacy = Files.createDirectory(temp.resolve("lang"));
        Path legacyFile = legacy.resolve("zh-myserver.yml");
        Files.writeString(legacyFile, PATH + ": Legacy override\n");
        var overrideTime = Files.getLastModifiedTime(file);
        byte[] jarBytes = Files.readAllBytes(jar);
        try (var engine = engine(() -> "zh")) {
            var texts = new ModuleTexts(engine, prepare(engine, jar, overrides));
            assertThat(texts.resolve(PATH)).contains("Operator override");
            assertThat(texts.shippedTemplates(PATH)).containsExactly(ENGLISH, "Probe in Japanese", CHINESE);
            assertThat(texts.shippedTemplates("command.never_defined")).isEmpty();
        }
        assertThat(jar).hasBinaryContent(jarBytes);
        assertThat(file).hasContent(PATH + ": Operator override");
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(overrideTime);
        assertThat(legacyFile).hasContent(PATH + ": Legacy override");
        assertThat(overrides.resolve("reference")).doesNotExist();
    }

    @Test
    void lookupFailuresAreTreatedAsMissing() {
        var engine = mock(TextEngine.class);
        var prepared = new TextEngine.Prepared("Fixture", null, temp, new CatalogueLoader.Loaded(
                new Catalogue.Builder(NAMESPACE, Locale.ENGLISH).put(Locale.ENGLISH, PATH, ENGLISH).build(),
                List.of(), Map.of()));
        when(engine.fallback()).thenThrow(new IllegalStateException("fallback unavailable"));
        assertThat(new ModuleTexts(engine, prepared).resolve(PATH)).isEmpty();
    }

    private static TextEngine engine(Supplier<String> fallback) {
        return new TextEngine(new ConfiguredLocaleResolver(() -> "auto", fallback, LOGGER), LOGGER);
    }

    private static TextEngine.Prepared prepare(TextEngine engine, Path jar, Path overrides) throws IOException {
        return engine.prepare(NAMESPACE, "Fixture", jar.toUri().toURL(), overrides, Locale.ENGLISH);
    }

    private Path moduleJar(Map<String, String> catalogues) throws IOException {
        Path jar = temp.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : catalogues.entrySet()) {
                output.putNextEntry(new JarEntry("messages/" + entry.getKey() + ".yml"));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    private static void assertMatchesMessages(TextEngine engine, ModuleTexts texts, String path) {
        var messages = engine.messagesFor(NAMESPACE);
        String plain = PlainTextComponentSerializer.plainText().serialize(
                messages.render(Text.of(MessageKey.of(NAMESPACE, path)), engine.fallback()));
        assertThat(texts.resolve(path)).contains(plain);
    }
}
