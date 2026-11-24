package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.utils.CodeSourceLocations;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

/** Reads module-owned YAML resources without logging, publishing or writing files. */
@ApiStatus.Internal
public final class CatalogueLoader {
    private static final Pattern LANGUAGE_FILE = Pattern.compile("[A-Za-z0-9_-]+\\.yml");

    /** Parsed entries, deferred diagnostics and unmodified shipped UTF-8 contents. */
    @ApiStatus.Internal
    public record Loaded(Catalogue catalogue, List<String> diagnostics, Map<String, String> shippedFiles) {
        /** Makes the result collections immutable. */
        public Loaded {
            diagnostics = List.copyOf(diagnostics);
            shippedFiles = Map.copyOf(shippedFiles);
        }
    }

    /** Loads only resources from the explicitly supplied module code source. */
    public Loaded load(String namespace, Locale sourceLocale, @Nullable URL location) {
        List<String> diagnostics = new ArrayList<>();
        Map<String, String> files = new LinkedHashMap<>();
        if (location != null) {
            Path source = CodeSourceLocations.toFile(location).toPath();
            try {
                if (Files.isDirectory(source)) {
                    readDirectory(source.resolve("messages"), files, diagnostics);
                } else {
                    readJar(source, files, diagnostics);
                }
            } catch (IOException failure) {
                diagnostics.add(source + ": " + failure.getMessage());
            }
        }
        Catalogue catalogue = parseFiles(namespace, sourceLocale, files, diagnostics);
        warnMissingBaseLanguages(catalogue, files, diagnostics);
        return new Loaded(catalogue, diagnostics, files);
    }

    /** Reads operator-owned overrides without creating or rewriting any operator file. */
    public Loaded withOverrides(Loaded shipped, Path directory) {
        List<String> diagnostics = new ArrayList<>(shipped.diagnostics());
        Map<String, String> files = new LinkedHashMap<>();
        try {
            readDirectory(directory, files, diagnostics);
        } catch (IOException failure) {
            diagnostics.add(directory + ": " + failure.getMessage());
        }
        Catalogue base = shipped.catalogue();
        Catalogue overrides = parseFiles(base.namespace(), base.sourceLocale(), files, diagnostics, base);
        return new Loaded(base.withOverrides(overrides), diagnostics, shipped.shippedFiles());
    }

    /**
     * Refreshes framework-owned references only, never the operator-owned files in the parent directory.
     * These copies are informational and excluded from catalogue loading; startup may overwrite them.
     * @param shipped original bundled UTF-8 files
     * @param overrideDirectory operator directory whose reference child is exclusively framework-owned
     * @return nonfatal write or cleanup diagnostics
     */
    public List<String> writeReferences(Loaded shipped, Path overrideDirectory) {
        List<String> diagnostics = new ArrayList<>();
        Path reference = overrideDirectory.resolve("reference").toAbsolutePath().normalize();
        String header;
        try {
            header = readReferenceHeader();
            Files.createDirectories(reference);
            removeStaleReferences(reference, shipped.shippedFiles());
        } catch (IOException failure) {
            diagnostics.add(reference + ": " + failure.getMessage());
            return diagnostics;
        }
        shipped.shippedFiles().forEach((name, text) -> {
            if (!catalogueFile(name, reference + "/" + name, diagnostics)
                    || LocaleNames.parse(name.substring(0, name.length() - 4)).isEmpty()) {
                return;
            }
            Path target = reference.resolve(name).normalize();
            if (!target.startsWith(reference)) {
                diagnostics.add(target + ": Reference path escapes the reference directory");
                return;
            }
            try {
                Files.writeString(target, header + text, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                diagnostics.add(target + ": " + failure.getMessage());
            }
        });
        return diagnostics;
    }

    private static String readReferenceHeader() throws IOException {
        try (var input = CatalogueLoader.class.getResourceAsStream("/messages/reference-header.txt")) {
            if (input == null) {
                throw new IOException("Missing bundled reference header: messages/reference-header.txt");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void removeStaleReferences(Path reference, Map<String, String> shipped) throws IOException {
        try (var paths = Files.list(reference)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (LANGUAGE_FILE.matcher(name).matches() && !shipped.containsKey(name)) {
                    Files.delete(path);
                }
            }
        }
    }

    private static boolean catalogueFile(String name, String display, List<String> diagnostics) {
        if (name.endsWith(".yaml")) {
            diagnostics.add(display + ": Unsupported catalogue extension; only .yml files are read");
            return false;
        }
        if (!name.endsWith(".yml")) {
            return false;
        }
        if (!LANGUAGE_FILE.matcher(name).matches()) {
            diagnostics.add(display + ": Invalid catalogue filename; expected letters, digits, '_' or '-' followed by .yml");
            return false;
        }
        return true;
    }

    private static void readDirectory(Path directory, Map<String, String> files, List<String> diagnostics)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = path.getFileName().toString();
                if (catalogueFile(name, path.toString(), diagnostics)) {
                    try {
                        files.put(name, Files.readString(path, StandardCharsets.UTF_8));
                    } catch (CharacterCodingException invalidEncoding) {
                        diagnostics.add(path + ": Catalogue files must be UTF-8");
                    } catch (IOException failure) {
                        diagnostics.add(path + ": " + failure.getMessage());
                    }
                }
            }
        }
    }

    private static void readJar(Path path, Map<String, String> files, List<String> diagnostics) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            for (var entry : jar.stream().filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(JarEntry::getName)).toList()) {
                String name = entry.getName();
                if (!name.startsWith("messages/")) {
                    continue;
                }
                String file = name.substring("messages/".length());
                // Normal subdirectories are not catalogues; traversal-shaped names must still be diagnosed.
                if (file.contains("/") && !file.contains("..")) {
                    continue;
                }
                String display = path + "!/" + name;
                if (!catalogueFile(file, display, diagnostics)) {
                    continue;
                }
                try (var input = jar.getInputStream(entry)) {
                    files.put(file, StandardCharsets.UTF_8.newDecoder()
                            .decode(ByteBuffer.wrap(input.readAllBytes())).toString());
                } catch (CharacterCodingException invalidEncoding) {
                    diagnostics.add(display + ": Catalogue files must be UTF-8");
                } catch (IOException failure) {
                    diagnostics.add(display + ": " + failure.getMessage());
                }
            }
        }
    }

    private static void warnMissingBaseLanguages(Catalogue catalogue, Map<String, String> files,
            List<String> diagnostics) {
        List<String> missing = files.keySet().stream()
                .map(file -> LocaleNames.parse(file.substring(0, file.length() - 4)))
                .flatMap(Optional::stream).filter(locale -> !locale.getCountry().isEmpty())
                .map(locale -> Locale.of(locale.getLanguage())).distinct()
                .filter(locale -> !catalogue.hasLocale(locale)).map(locale -> "messages/" + locale.getLanguage() + ".yml")
                .sorted().toList();
        if (!missing.isEmpty()) {
            diagnostics.add(catalogue.namespace() + ": Regional catalogues lack a base language; provide "
                    + String.join(", ", missing) + " for language-only and other-region requests");
        }
    }

    private static Catalogue parseFiles(String namespace, Locale sourceLocale, Map<String, String> files,
            List<String> diagnostics) {
        return parseFiles(namespace, sourceLocale, files, diagnostics, null);
    }

    private static Catalogue parseFiles(String namespace, Locale sourceLocale, Map<String, String> files,
            List<String> diagnostics, @Nullable Catalogue shipped) {
        Catalogue.Builder builder = new Catalogue.Builder(namespace, sourceLocale);
        Set<Locale> loaded = new HashSet<>();
        files.forEach((file, text) -> {
            var locale = LocaleNames.parse(file.substring(0, file.length() - 4));
            if (locale.isEmpty()) {
                diagnostics.add(file + ": Invalid catalogue locale");
            } else if (!loaded.add(locale.orElseThrow())) {
                diagnostics.add(file + ": Duplicate normalized catalogue locale");
            } else {
                parseFile(namespace, file, text, diagnostics).ifPresent(values -> {
                    if (shipped != null) {
                        values.keySet().stream().filter(path -> !shipped.contains(path)).forEach(path ->
                                diagnostics.add(file + ": Override key not present in shipped catalogues: " + path));
                    }
                    builder.putAll(locale.orElseThrow(), values);
                });
            }
        });
        return builder.build();
    }

    private static Optional<Map<String, String>> parseFile(String namespace, String file, String text,
            List<String> diagnostics) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            SafeConstructor strings = new SafeConstructor(options) {
                @Override
                protected Object constructObject(Node node) {
                    // Message scalars are text, including explicit YAML scalar tags; collection nodes retain their shape.
                    return node instanceof ScalarNode scalar ? constructScalar(scalar) : super.constructObject(node);
                }
            };
            Object root = new Yaml(strings).load(text);
            Map<String, String> values = new LinkedHashMap<>();
            if (root != null) {
                if (!(root instanceof Map<?, ?> mapping)) {
                    throw new IllegalArgumentException("Catalogue root must be a mapping");
                }
                flatten(namespace, file, "", mapping, values, diagnostics);
            }
            return Optional.of(values);
        } catch (RuntimeException malformed) {
            diagnostics.add(file + ": " + malformed.getMessage());
            return Optional.empty();
        }
    }

    private static void flatten(String namespace, String file, String prefix, Map<?, ?> mapping,
            Map<String, String> values, List<String> diagnostics) {
        mapping.forEach((key, value) -> flattenEntry(namespace, file, prefix, key, value, values, diagnostics));
    }

    private static void flattenEntry(String namespace, String file, String prefix, Object key, @Nullable Object value,
            Map<String, String> values, List<String> diagnostics) {
        String path = prefix.isEmpty() ? String.valueOf(key) : prefix + '.' + key;
        try {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException("Catalogue keys must be strings");
            }
            new MessageKey(namespace, path);
            if (value instanceof Map<?, ?> nested) {
                flatten(namespace, file, path, nested, values, diagnostics);
            } else if (value instanceof String template) {
                if (values.putIfAbsent(path, template) != null) {
                    diagnostics.add(file + ": Duplicate flattened path " + path);
                }
            } else {
                diagnostics.add(file + ": Non-string value at " + path);
            }
        } catch (IllegalArgumentException invalid) {
            diagnostics.add(file + ": " + invalid.getMessage());
        }
    }
}
