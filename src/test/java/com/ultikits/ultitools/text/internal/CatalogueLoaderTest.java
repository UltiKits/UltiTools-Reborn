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
