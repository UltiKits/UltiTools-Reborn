package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogueLoaderTest {
    @TempDir Path root;

    private void file(String name, String text) throws Exception {
        Path messages = Files.createDirectories(root.resolve("messages"));
        Files.writeString(messages.resolve(name), text);
    }

    @Test
    void nestedAndDottedPaths_areEquivalentAndFirstDuplicateWins() throws Exception {
        file("en.yml", "home: {set: {success: first}}\nhome.set.success: second\nother.key: value\n");
        var result = new CatalogueLoader().load("sample", Locale.ENGLISH, root.toUri().toURL());
        assertThat(result.catalogue().lookup("home.set.success", List.of(Locale.ENGLISH))).contains("first");
        assertThat(result.catalogue().lookup("other.key", List.of(Locale.ENGLISH))).contains("value");
        assertThat(result.diagnostics()).singleElement().asString().contains("Duplicate flattened path");
    }

    @Test
    void duplicateYamlKeys_ignoreWholeFile() throws Exception {
        file("en.yml", "hello: first\nhello: second\n");
        file("zh.yml", "hello: Chinese\n");
        var result = new CatalogueLoader().load("sample", Locale.ENGLISH, root.toUri().toURL());
        assertThat(result.catalogue().isDefined("hello", Locale.ENGLISH)).isFalse();
        assertThat(result.catalogue().isDefined("hello", Locale.CHINESE)).isTrue();
        assertThat(result.diagnostics()).singleElement().asString().contains("en.yml", "line");
    }

    @Test
    void badEntriesAndFiles_areDiagnosedWithoutWrites() throws Exception {
        file("en.yml", "good: value\nBad: ignored\ncount: 3\nlist: [x]\n42: ignored\n");
        file("zh.yml", "# empty catalogue\n");
        file("not-a-locale.yml", "good: ignored\n");
        file("de.yml", "broken: [\n");
        file("fr.yml", "[not, mapping]\n");
        file("en_US.yml", "hello: first\n");
        file("en_us.yml", "hello: second\n");
        Path reference = Files.createDirectories(root.resolve("messages/reference"));
        Files.writeString(reference.resolve("en.yml"), "reference: excluded\n");
        var result = new CatalogueLoader().load("sample", Locale.ENGLISH, root.toUri().toURL());
        assertThat(result.catalogue().isDefined("good", Locale.ENGLISH)).isTrue();
        assertThat(result.catalogue().hasLocale(Locale.CHINESE)).isTrue();
        assertThat(result.catalogue().contains("reference")).isFalse();
        assertThat(result.diagnostics()).hasSize(8);
        assertThat(Files.readString(reference.resolve("en.yml"))).isEqualTo("reference: excluded\n");
        assertThat(new CatalogueLoader().load("sample", Locale.ENGLISH, null).diagnostics()).isEmpty();
        assertThat(new CatalogueLoader().load("sample", Locale.ENGLISH, root.resolve("absent.jar").toUri().toURL())
                .diagnostics()).hasSize(1);
    }

    @Test
    void jarLoading_usesOnlyItsOwnTopLevelMessages() throws Exception {
        Path jar = root.resolve("module + space.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : Map.of("messages/en.yml", "greeting: own", "messages/reference/en.yml", "bad: hidden",
                    "lang/en.yml", "bad: old").entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        var loaded = new CatalogueLoader().load("sample", Locale.ENGLISH, jar.toUri().toURL());
        assertThat(loaded.catalogue().lookup("greeting", List.of(Locale.ENGLISH))).contains("own");
        assertThat(loaded.shippedFiles()).containsOnlyKeys("en.yml");
        assertThat(loaded.diagnostics()).isEmpty();
    }

    @Test
    void operatorOverrides_addLanguagesWarnOnUnknownKeysAndNeverWrite() throws Exception {
        file("en.yml", "greeting: shipped\nother: retained\n");
        Path operator = Files.createDirectories(root.resolve("operator"));
        String edited = "# operator bytes\ngreeting: overridden\nunknown: accepted\n";
        Files.writeString(operator.resolve("en.yml"), edited);
        Files.writeString(operator.resolve("fr.yml"), "greeting: bonjour\n");
        Files.createDirectories(operator.resolve("reference"));
        Files.writeString(operator.resolve("reference/de.yml"), "greeting: ignored\n");
        CatalogueLoader loader = new CatalogueLoader();
        var shipped = loader.load("sample", Locale.ENGLISH, root.toUri().toURL());
        var loaded = loader.withOverrides(shipped, operator);
        assertThat(loaded.catalogue().lookup("greeting", List.of(Locale.ENGLISH))).contains("overridden");
        assertThat(loaded.catalogue().lookup("other", List.of(Locale.ENGLISH))).contains("retained");
        assertThat(loaded.catalogue().lookup("greeting", List.of(Locale.FRENCH))).contains("bonjour");
        assertThat(loaded.catalogue().hasLocale(Locale.GERMAN)).isFalse();
        assertThat(loaded.catalogue().lookup("unknown", List.of(Locale.ENGLISH))).contains("accepted");
        assertThat(loaded.diagnostics()).singleElement().asString().contains("unknown");
        assertThat(Files.readString(operator.resolve("en.yml"))).isEqualTo(edited);
        assertThat(loaded.shippedFiles()).isEqualTo(shipped.shippedFiles());
        Path absent = root.resolve("absent");
        loader.withOverrides(shipped, absent);
        assertThat(absent).doesNotExist();
    }

    @Test
    void references_refreshOwnedCopiesWithoutTouchingOperatorBytes() throws Exception {
        String bundled = "# shipped\r\ngreeting: '你好'\r\n";
        file("en.yml", bundled);
        CatalogueLoader loader = new CatalogueLoader();
        var shipped = loader.load("sample", Locale.ENGLISH, root.toUri().toURL());
        Path operator = Files.createDirectories(root.resolve("reference-operator"));
        String override = "greeting: Operator\n";
        Files.writeString(operator.resolve("en.yml"), override);
        Path reference = Files.createDirectories(operator.resolve("reference"));
        Files.writeString(reference.resolve("fr.yml"), "stale: removed\n");
        Files.writeString(reference.resolve("en.yml"), "edited: ineffective\n");
        assertThat(loader.writeReferences(shipped, operator)).isEmpty();
        String header;
        try (var input = getClass().getResourceAsStream("/messages/reference-header.txt")) {
            assertThat(input).as("The bilingual reference header is a bundled UTF-8 resource").isNotNull();
            header = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String copy = Files.readString(reference.resolve("en.yml"));
        assertThat(copy).startsWith(header).endsWith(bundled);
        assertThat(copy).contains("edits here have no effect", "修改此处无效", "同名覆盖文件");
        assertThat(Files.readAllBytes(reference.resolve("en.yml")))
                .isEqualTo((header + bundled).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(reference.resolve("fr.yml")).doesNotExist();
        assertThat(Files.readString(operator.resolve("en.yml"))).isEqualTo(override);
        assertThat(loader.withOverrides(shipped, operator).catalogue().lookup("greeting", List.of(Locale.ENGLISH)))
                .contains("Operator");
    }

    @Test
    void referenceWriteFailure_isANonfatalDiagnostic() throws Exception {
        Path blocked = root.resolve("not-a-directory");
        Files.writeString(blocked, "owned by another file\n");
        CatalogueLoader loader = new CatalogueLoader();
        var shipped = loader.load("sample", Locale.ENGLISH, null);
        assertThat(loader.writeReferences(shipped, blocked)).singleElement().asString().contains("reference");
        assertThat(Files.readString(blocked)).isEqualTo("owned by another file\n");
    }

    @Test
    void catalogueSnapshots_areImmutableAndOverridesAreLanguageSpecific() {
        var builder = new Catalogue.Builder("sample", Locale.ENGLISH).put(Locale.ENGLISH, "hello", "old");
        Catalogue snapshot = builder.build();
        builder.put(Locale.ENGLISH, "hello", "new");
        Catalogue overrides = new Catalogue.Builder("sample", Locale.ENGLISH).put(Locale.FRENCH, "hello", "bonjour")
                .put(Locale.ENGLISH, "hello", "override").build();
        assertThat(snapshot.lookup("hello", List.of(Locale.ENGLISH))).contains("old");
        assertThat(snapshot.withOverrides(overrides).lookup("hello", List.of(Locale.ENGLISH))).contains("override");
        assertThat(snapshot.withOverrides(overrides).isDefined("hello", Locale.FRANCE)).isTrue();
    }
}
