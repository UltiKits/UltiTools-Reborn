package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The golden corpus under {@code src/test/resources/config-golden}, as listed in its {@code MANIFEST.md}. */
final class GoldenCorpus {

    /** The three fixture directories whose files must round-trip byte for byte. */
    static final String[] DIRECTORIES = {"bundled", "written-by-6.2", "hand-edited"};

    private static final Pattern ROW = Pattern.compile(
            "^\\| ((?:bundled|written-by-6\\.2|hand-edited)/[^ |]+) \\| ([^|]+) \\| ([^|]+) \\| ([0-9a-f]{64}) \\|$");

    private GoldenCorpus() {
    }

    /** One manifest row. */
    static final class Fixture {
        final String name;
        final String sha256;
        final Path file;

        Fixture(String name, String sha256, Path file) {
            this.name = name;
            this.sha256 = sha256;
            this.file = file;
        }

        byte[] bytes() throws IOException {
            return Files.readAllBytes(file);
        }

        String text() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Path root() {
        URL manifest = GoldenCorpus.class.getResource("/config-golden/MANIFEST.md");
        if (manifest == null) {
            throw new IllegalStateException("config-golden/MANIFEST.md is not on the test classpath");
        }
        try {
            return Paths.get(manifest.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Fixture> fixtures() throws IOException {
        Path root = root();
        List<Fixture> result = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("MANIFEST.md"), StandardCharsets.UTF_8)) {
            Matcher matcher = ROW.matcher(line);
            if (matcher.matches()) {
                result.add(new Fixture(matcher.group(1), matcher.group(4), root.resolve(matcher.group(1))));
            }
        }
        return result;
    }

    /** Every file actually present under the three fixture directories, relative to the corpus root. */
    static List<String> filesOnDisk() throws IOException {
        Path root = root();
        List<String> result = new ArrayList<>();
        for (String directory : DIRECTORIES) {
            try (Stream<Path> files = Files.walk(root.resolve(directory))) {
                result.addAll(files.filter(Files::isRegularFile)
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .collect(Collectors.toList()));
            }
        }
        return result;
    }
}
