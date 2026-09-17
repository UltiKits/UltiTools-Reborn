package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review r4 WR-01: a crash or kill between moving a module's old jars into
 * {@code plugins/UltiTools/.upm-staging} and moving the new jar in left the module with no jar in
 * the modules folder, and nothing ever looked at the staging directory again. The boot-time
 * recovery runs where the module class path is assembled, {@link UltiTools#collectModuleJarUrls},
 * before any module jar is opened, so the outcome is observed as the class path the server boots
 * with.
 */
@DisplayName("Boot-time recovery of interrupted module updates (review r4 WR-01)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateRecoveryTest {

    private static final String ID = "fixture-module";
    private static final String UUID_TEXT = "8420a849-1c2d-4e5f-9a0b-1c2d3e4f5a6b";

    @TempDir
    File dataFolder;

    private File pluginsFolder;
    private File stagingFolder;
    private final List<LogRecord> logs = new ArrayList<>();
    private Handler capture;
    private Logger utilsLogger;

    @BeforeEach
    void setUp() {
        pluginsFolder = new File(dataFolder, "plugins");
        stagingFolder = new File(dataFolder, ".upm-staging");
        assertThat(pluginsFolder.mkdirs()).isTrue();
        assertThat(stagingFolder.mkdirs()).isTrue();
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        utilsLogger = Logger.getLogger("com.ultikits.ultitools.utils.PluginInstallUtils");
        utilsLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        utilsLogger.removeHandler(capture);
    }

    @Test
    @DisplayName("a set-aside jar whose module has no jar in the modules folder is moved back under its original name and loaded")
    void orphanedSetAsideJar_isRestoredBeforeTheClassPathIsBuilt() throws IOException {
        File aside = writeJar(new File(stagingFolder, ID + "-1.0.0.jar." + UUID_TEXT + ".old"), ID, "1.0.0");
        byte[] bytes = Files.readAllBytes(aside.toPath());

        List<URL> urls = UltiTools.collectModuleJarUrls(pluginsFolder);

        File restored = new File(pluginsFolder, ID + "-1.0.0.jar");
        assertThat(restored).as("moved back under its original name").hasBinaryContent(bytes);
        assertThat(aside).doesNotExist();
        assertThat(urls).as("the restored jar is on the class path of this boot").contains(restored.toURI().toURL());
        assertThat(warnings()).anyMatch(m -> m.contains(restored.getAbsolutePath()));
    }

    @Test
    @DisplayName("a set-aside jar whose module already has a jar in the modules folder is left and reported as a deletable leftover")
    void setAsideJarOfAModuleThatHasAJar_isLeftAsLeftover() throws IOException {
        File current = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = writeJar(new File(stagingFolder, ID + "-1.0.0.jar." + UUID_TEXT + ".old"), ID, "1.0.0");

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(aside).as("never moved back beside the module's current jar").exists();
        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).doesNotExist();
        assertThat(current).exists();
        assertThat(warnings()).anyMatch(m -> m.contains(aside.getAbsolutePath()) && m.contains("leftover"));
    }

    @Test
    @DisplayName("a stale partial download is deleted")
    void stalePartialDownload_isDeleted() throws IOException {
        File part = new File(stagingFolder, ID + "-2.0.0-" + UUID_TEXT + ".part");
        Files.write(part.toPath(), "partial".getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(part).doesNotExist();
        assertThat(logs).anyMatch(r -> r.getLevel() == Level.INFO && r.getMessage().contains(part.getAbsolutePath()));
    }

    @Test
    @DisplayName("names that do not match <original>.jar.<uuid>.old exactly are left untouched")
    void malformedSetAsideNames_areLeftUntouched() throws IOException {
        File noUuid = writeJar(new File(stagingFolder, ID + "-1.0.0.jar.old"), ID, "1.0.0");
        File badUuid = writeJar(new File(stagingFolder, ID + "-1.0.0.jar.not-a-uuid.old"), ID, "1.0.0");
        File noJar = writeJar(new File(stagingFolder, ID + "-1.0.0." + UUID_TEXT + ".old"), ID, "1.0.0");

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(noUuid).exists();
        assertThat(badUuid).exists();
        assertThat(noJar).exists();
        assertThat(pluginsFolder.list()).isEmpty();
    }

    @Test
    @DisplayName("a set-aside jar whose original name is taken by another file is left, and the occupant is untouched")
    void originalNameOccupied_setAsideJarIsLeft() throws IOException {
        File occupant = new File(pluginsFolder, ID + "-1.0.0.jar");
        Files.write(occupant.toPath(), "not a module".getBytes(StandardCharsets.UTF_8));
        File aside = writeJar(new File(stagingFolder, ID + "-1.0.0.jar." + UUID_TEXT + ".old"), ID, "1.0.0");

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(aside).exists();
        assertThat(occupant).hasContent("not a module");
        assertThat(warnings()).anyMatch(m -> m.contains(aside.getAbsolutePath()));
    }

    @Test
    @DisplayName("a staging path that is not a directory never breaks building the class path")
    void stagingPathIsAFile_neverBreaksBoot() throws IOException {
        assertThat(stagingFolder.delete()).isTrue();
        assertThat(stagingFolder.createNewFile()).isTrue();
        File module = writeJar(new File(pluginsFolder, ID + "-1.0.0.jar"), ID, "1.0.0");

        assertThatCode(() -> assertThat(UltiTools.collectModuleJarUrls(pluginsFolder))
                .contains(module.toURI().toURL())).doesNotThrowAnyException();
    }

    private List<String> warnings() {
        return logs.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .collect(Collectors.toList());
    }

    private static File writeJar(File file, String identifyString, String version) throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: " + version + "\nidentify-string: " + identifyString + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }
}
