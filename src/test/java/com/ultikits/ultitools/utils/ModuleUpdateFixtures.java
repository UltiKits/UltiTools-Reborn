package com.ultikits.ultitools.utils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Shared fixtures for the module update transaction tests (#505): a server layout on a temporary
 * folder, module JARs built with {@link JarOutputStream}, and "loaded" modules that report a
 * version, an identify-string and the JAR they came from -- what the observation reads after the
 * modules load.
 */
public final class ModuleUpdateFixtures {

    private ModuleUpdateFixtures() {
    }

    /**
     * The framework's data folder inside a temporary server root, laid out as on a real server:
     * {@code <server root>/plugins/UltiTools}. The update records live under the server root
     * ({@code <server root>/.ultikits/upm-transactions}), so a bare temporary folder must never be
     * used as the data folder: its grandparent is outside the test's own folder.
     *
     * @param serverRoot the temporary server root
     * @return the data folder, created
     */
    public static File dataFolderIn(File serverRoot) {
        File dataFolder = new File(new File(serverRoot, "plugins"), "UltiTools");
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("cannot create " + dataFolder);
        }
        return dataFolder;
    }

    /**
     * Writes a module JAR whose {@code plugin.yml} declares the given name, version and
     * identify-string, plus one class-shaped entry so it is an ordinary module archive.
     *
     * <p>Its {@code main:} is derived from the identify-string, so JARs of different modules
     * declare different main classes, as real modules do, and every copy of one module declares
     * the same one: {@code com.example.<identify-string>.DemoModule} ({@code com.example.demo.DemoModule}
     * for {@code demo} or none). Staging treats another JAR declaring the same {@code main:} as a
     * copy of the module (maintainer follow-up 19).
     *
     * @param file           where to write it
     * @param name           the {@code name:} value
     * @param version        the {@code version:} value
     * @param identifyString the {@code identify-string:} value, or {@code null} for none
     * @return the file
     * @throws IOException when it cannot be written
     */
    public static File moduleJar(File file, String name, String version, String identifyString) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        String mainPackage = "com.example." + (identifyString == null ? "demo"
                : identifyString.replaceAll("[^A-Za-z0-9]", "_"));
        StringBuilder yml = new StringBuilder()
                .append("name: ").append(name).append('\n')
                .append("version: '").append(version).append("'\n")
                .append("main: ").append(mainPackage).append(".DemoModule\n");
        if (identifyString != null) {
            yml.append("identify-string: ").append(identifyString).append('\n');
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(mainPackage.replace('.', '/') + "/DemoModule.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
        return file;
    }

    /**
     * A loaded module as the observation sees it.
     *
     * @param name           its runtime name
     * @param version        the version it reports
     * @param identifyString its identify-string
     * @return the instance
     */
    public static UltiToolsPlugin loadedModule(String name, String version, String identifyString) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn(name);
        when(plugin.getVersion()).thenReturn(version);
        when(plugin.getIdentifyString()).thenReturn(identifyString);
        return plugin;
    }

    /** Maps loaded instances to the JARs they "came from", by identity. */
    public static final class CodeSources implements Function<UltiToolsPlugin, File> {
        private final Map<UltiToolsPlugin, File> sources = new IdentityHashMap<>();

        /**
         * Records where an instance came from.
         *
         * @param plugin the instance
         * @param jar    its JAR
         * @return this
         */
        public CodeSources with(UltiToolsPlugin plugin, File jar) {
            sources.put(plugin, jar);
            return this;
        }

        @Override
        public File apply(UltiToolsPlugin plugin) {
            return sources.get(plugin);
        }
    }

    /** A catalogue with one module at one latest version. */
    public static ModuleFileTransactions.Catalogue catalogue(String identifyString, String latest) {
        return new ModuleFileTransactions.Catalogue() {
            @Override
            public String latestVersion(String id) {
                return identifyString.equals(id) ? latest : null;
            }

            @Override
            public String downloadLink(String id, String version) {
                return identifyString.equals(id) && latest.equals(version) ? "https://example.invalid/" + id : null;
            }
        };
    }

    /** A downloader that writes a module JAR with the given metadata under the requested name. */
    public static ModuleFileTransactions.Downloader downloading(String name, String version, String identifyString) {
        return (link, fileName, dir) -> moduleJar(new File(dir, fileName), name, version, identifyString);
    }

    /** A downloader that fails the way a network error does. */
    public static ModuleFileTransactions.Downloader failingDownload(String message) {
        return (link, fileName, dir) -> {
            throw new IOException(message);
        };
    }

    /**
     * The names of the regular files directly in a folder, sorted.
     *
     * @param folder the folder
     * @return its file names
     * @throws IOException when it cannot be listed
     */
    public static List<String> namesIn(File folder) throws IOException {
        if (!folder.isDirectory()) {
            return Collections.emptyList();
        }
        try (Stream<java.nio.file.Path> entries = Files.list(folder.toPath())) {
            return entries.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    /**
     * Every file under a folder, relative to it, sorted.
     *
     * @param folder the folder
     * @return the relative paths
     * @throws IOException when it cannot be walked
     */
    public static List<String> treeOf(File folder) throws IOException {
        if (!folder.exists()) {
            return new ArrayList<>();
        }
        try (Stream<java.nio.file.Path> entries = Files.walk(folder.toPath())) {
            return entries.filter(Files::isRegularFile)
                    .map(p -> folder.toPath().relativize(p).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
