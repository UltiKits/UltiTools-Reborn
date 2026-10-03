package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The start-up scan reads the module JARs in file-name order, the same order the module class
 * loader is built in (#476), so which copy of a duplicated module is read first no longer depends
 * on the file system's listing order.
 *
 * <p>Each fixture JAR carries no {@code plugin.yml}, so the scan refuses each one with a SEVERE line
 * naming it; the order of those lines is the order the scan read the folder in.
 */
@DisplayName("The start-up scan reads module JARs in file-name order (#476)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerDiscoveryOrderTest {

    private static final List<String> SORTED =
            Arrays.asList("alpha.jar", "bravo.jar", "charlie.jar", "delta.jar", "echo.jar");
    private static final Pattern REFUSED_JAR = Pattern.compile("Module '([^']+)' has no readable plugin\\.yml");

    @TempDir
    File modules;

    private final List<LogRecord> logs = new ArrayList<>();
    private Handler capture;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
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
                // nothing held
            }
        };
        Bukkit.getLogger().addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(capture);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /** A folder of {@code dir}'s path whose listing is exactly {@code order}, filtered as asked. */
    private static File listingInOrder(File dir, List<String> order) {
        return new File(dir.getPath()) {
            private static final long serialVersionUID = 1L;

            @Override
            public File[] listFiles(FileFilter filter) {
                List<File> listed = new ArrayList<>();
                for (String name : order) {
                    File file = new File(dir, name);
                    if (filter == null || filter.accept(file)) {
                        listed.add(file);
                    }
                }
                return listed.toArray(new File[0]);
            }

            @Override
            public File[] listFiles() {
                return listFiles((FileFilter) null);
            }
        };
    }

    private List<String> scanOrder(List<String> listing) {
        logs.clear();
        new PluginManager().discoverModuleClasses(listingInOrder(modules, listing));
        List<String> order = new ArrayList<>();
        for (LogRecord record : logs) {
            Matcher matcher = REFUSED_JAR.matcher(String.valueOf(record.getMessage()));
            if (Level.SEVERE.equals(record.getLevel()) && matcher.find()) {
                order.add(matcher.group(1));
            }
        }
        return order;
    }

    @Test
    @DisplayName("two different listing orders of the same five JARs are scanned in the same, file-name order")
    void scan_readsJarsInFileNameOrder() throws IOException {
        for (String name : SORTED) {
            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(new File(modules, name)))) {
                out.putNextEntry(new JarEntry("com/example/Nothing.class"));
                out.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                out.closeEntry();
            }
        }

        assertThat(scanOrder(Arrays.asList("delta.jar", "alpha.jar", "echo.jar", "charlie.jar", "bravo.jar")))
                .containsExactlyElementsOf(SORTED);
        assertThat(scanOrder(Arrays.asList("echo.jar", "charlie.jar", "alpha.jar", "bravo.jar", "delta.jar")))
                .containsExactlyElementsOf(SORTED);
    }
}
