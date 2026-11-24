package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(result.catalogue().lookup("count", List.of(Locale.ENGLISH))).contains("3");
        assertThat(result.catalogue().lookup("42", List.of(Locale.ENGLISH))).contains("ignored");
        assertThat(result.diagnostics()).hasSize(6);
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
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
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
            header = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String copy = Files.readString(reference.resolve("en.yml"));
        assertThat(copy).startsWith(header).endsWith(bundled);
        assertThat(copy).contains("edits here have no effect", "修改此处无效", "同名覆盖文件");
        assertThat(Files.readAllBytes(reference.resolve("en.yml")))
                .isEqualTo((header + bundled).getBytes(StandardCharsets.UTF_8));
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

    @ParameterizedTest
    @ValueSource(strings = {"..\\..\\x.yml", "../x.yml", "C:x.yml", "en..yml"})
    void maliciousJarNames_areRejectedWithoutWritingOutsideReferences(String name) throws Exception {
        Path jar = root.resolve("malicious.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : Map.of("messages/" + name, "evil: ignored\n", "messages/en.yml", "good: retained\n").entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        CatalogueLoader loader = new CatalogueLoader();
        var loaded = loader.load("sample", Locale.ENGLISH, jar.toUri().toURL());
        Path overrides = root.resolve("operator/messages");
        assertThat(loaded.catalogue().lookup("good", List.of(Locale.ENGLISH))).contains("retained");
        assertThat(loaded.shippedFiles()).containsOnlyKeys("en.yml");
        assertThat(loaded.diagnostics()).singleElement().asString().contains("Invalid catalogue filename");
        assertThat(loader.writeReferences(loaded, overrides)).isEmpty();
        assertThat(overrides.resolve("reference/en.yml")).exists();
        assertThat(root.resolve("x.yml")).doesNotExist();
        assertThat(overrides.resolve("x.yml")).doesNotExist();
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).map(root::relativize).toList())
                    .containsExactlyInAnyOrder(Path.of("malicious.jar"), Path.of("operator/messages/reference/en.yml"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../x.yml", "..\\..\\x.yml", "en\u0000.yml"})
    void referenceWriter_revalidatesUntrustedPreparedFileNames(String name) throws Exception {
        Catalogue catalogue = new Catalogue.Builder("sample", Locale.ENGLISH).build();
        var loaded = new CatalogueLoader.Loaded(catalogue, List.of(), Map.of(name, "evil: ignored\n"));
        assertThat(new CatalogueLoader().writeReferences(loaded, root.resolve("operator/messages")))
                .singleElement().asString().contains("Invalid catalogue filename");
        assertThat(root.resolve("operator/x.yml")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Yes", "No", "true", "on", "123", "1.5", "null", "~", "!!int 123"})
    void everyScalar_isReadAsLiteralText(String scalar) throws Exception {
        file("en.yml", "value: " + scalar + "\nyes: Yes\nno: No\n1: 1\n");
        var loaded = new CatalogueLoader().load("sample", Locale.ENGLISH, root.toUri().toURL());
        assertThat(loaded.catalogue().lookup("value", List.of(Locale.ENGLISH)))
                .contains(scalar.equals("!!int 123") ? "123" : scalar);
        assertThat(loaded.catalogue().lookup("yes", List.of(Locale.ENGLISH))).contains("Yes");
        assertThat(loaded.catalogue().lookup("no", List.of(Locale.ENGLISH))).contains("No");
        assertThat(loaded.catalogue().lookup("1", List.of(Locale.ENGLISH))).contains("1");
        assertThat(loaded.diagnostics()).isEmpty();
    }

    @Test
    void yamlExtension_warnsForShippedJarAndOperatorFiles() throws Exception {
        Path jar = root.resolve("yaml.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("messages/en.yaml"));
            output.write("greeting: ignored\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        CatalogueLoader loader = new CatalogueLoader();
        assertThat(loader.load("sample", Locale.ENGLISH, jar.toUri().toURL()).diagnostics())
                .singleElement().asString().contains("en.yaml", "only .yml");
        Path overrides = Files.createDirectories(root.resolve("operator/messages"));
        Files.writeString(overrides.resolve("en.yaml"), "greeting: ignored\n");
        assertThat(loader.withOverrides(loader.load("sample", Locale.ENGLISH, null), overrides).diagnostics())
                .singleElement().asString().contains("en.yaml", "only .yml");
    }

    @Test
    void invalidUtf8_warnsWithTheExactPathAndRequiredEncoding() throws Exception {
        Path overrides = Files.createDirectories(root.resolve("operator/messages"));
        Path file = overrides.resolve("en.yml");
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});
        CatalogueLoader loader = new CatalogueLoader();
        var loaded = loader.withOverrides(loader.load("sample", Locale.ENGLISH, null), overrides);
        assertThat(loaded.diagnostics()).singleElement().asString().contains(file.toString(), "must be UTF-8");
        assertThat(loaded.catalogue().hasLocale(Locale.ENGLISH)).isFalse();
    }

    @Test
    void utf8Bom_isAcceptedWithoutChangingOperatorBytes() throws Exception {
        Path overrides = Files.createDirectories(root.resolve("operator/messages"));
        String original = "﻿greeting: Bonjour\n";
        Files.writeString(overrides.resolve("fr.yml"), original);
        CatalogueLoader loader = new CatalogueLoader();
        var loaded = loader.withOverrides(loader.load("sample", Locale.ENGLISH, null), overrides);
        assertThat(loaded.catalogue().lookup("greeting", List.of(Locale.FRENCH))).contains("Bonjour");
        assertThat(loaded.diagnostics()).singleElement().asString().contains("Override key not present");
        assertThat(Files.readString(overrides.resolve("fr.yml"))).isEqualTo(original);
    }

    @Test
    void regionalOnlyCatalogues_warnOncePerLanguageAndKeepTheLookupOrder() throws Exception {
        file("en.yml", "greeting: English\n");
        file("zh_CN.yml", "greeting: Chinese\n");
        file("zh_HK.yml", "greeting: Cantonese\n");
        var loaded = new CatalogueLoader().load("sample", Locale.ENGLISH, root.toUri().toURL());
        assertThat(loaded.diagnostics()).singleElement().asString().contains("zh.yml");
        assertThat(loaded.catalogue().lookup("greeting", List.of(Locale.TRADITIONAL_CHINESE, Locale.CHINESE)))
                .isEmpty();
        file("zh.yml", "greeting: Base Chinese\n");
        assertThat(new CatalogueLoader().load("sample", Locale.ENGLISH, root.toUri().toURL()).diagnostics()).isEmpty();
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
