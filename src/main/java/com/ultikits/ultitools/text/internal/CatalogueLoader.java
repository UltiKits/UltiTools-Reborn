package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.utils.CodeSourceLocations;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads module-owned YAML resources without logging, publishing or writing files. */
@ApiStatus.Internal
public final class CatalogueLoader {
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
        return new Loaded(parseFiles(namespace, sourceLocale, files, diagnostics), diagnostics, files);
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
        Path reference = overrideDirectory.resolve("reference");
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
            if (LocaleNames.parse(name.substring(0, name.length() - 4)).isPresent()) {
                try {
                    Files.writeString(reference.resolve(name), header + text, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    diagnostics.add(reference.resolve(name) + ": " + failure.getMessage());
                }
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
                if (name.endsWith(".yml") && !shipped.containsKey(name)) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void readDirectory(Path directory, Map<String, String> files, List<String> diagnostics)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (path.getFileName().toString().endsWith(".yml")) {
                    try {
                        files.put(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException failure) {
                        diagnostics.add(path + ": " + failure.getMessage());
                    }
                }
            }
        }
    }

    private static void readJar(Path path, Map<String, String> files, List<String> diagnostics) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            for (var entry : jar.stream().filter(entry -> !entry.isDirectory()).sorted(
                    java.util.Comparator.comparing(java.util.jar.JarEntry::getName)).toList()) {
                String name = entry.getName();
                if (name.startsWith("messages/") && name.endsWith(".yml")
                        && name.indexOf('/', "messages/".length()) < 0) {
                    try (var input = jar.getInputStream(entry)) {
                        files.put(name.substring("messages/".length()), new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException failure) {
                        diagnostics.add(path + "!/" + name + ": " + failure.getMessage());
                    }
                }
            }
        }
    }

    private static Catalogue parseFiles(String namespace, Locale sourceLocale, Map<String, String> files,
            List<String> diagnostics) {
        return parseFiles(namespace, sourceLocale, files, diagnostics, null);
    }

    private static Catalogue parseFiles(String namespace, Locale sourceLocale, Map<String, String> files,
            List<String> diagnostics, @Nullable Catalogue shipped) {
        Catalogue.Builder builder = new Catalogue.Builder(namespace, sourceLocale);
        java.util.Set<Locale> loaded = new java.util.HashSet<>();
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

    private static java.util.Optional<Map<String, String>> parseFile(String namespace, String file, String text,
            List<String> diagnostics) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object root = new Yaml(new SafeConstructor(options)).load(text);
            Map<String, String> values = new LinkedHashMap<>();
            if (root != null) {
                if (!(root instanceof Map<?, ?> mapping)) {
                    throw new IllegalArgumentException("Catalogue root must be a mapping");
                }
                flatten(namespace, file, "", mapping, values, diagnostics);
            }
            return java.util.Optional.of(values);
        } catch (RuntimeException malformed) {
            diagnostics.add(file + ": " + malformed.getMessage());
            return java.util.Optional.empty();
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
