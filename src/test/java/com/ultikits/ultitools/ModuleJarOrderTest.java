package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.utils.ModuleFileTransactions;

/**
 * Module JARs are discovered in file-name order, whatever order the file system lists them in
 * (#476). {@code File#listFiles} promises no order -- measured on ext4 it is hash order, neither
 * alphabetical nor creation order -- so the same folder could load its duplicate copies, and name
 * its duplicate warnings, differently after a restore or on another file system.
 *
 * <p>The listing order is taken out of the file system's hands with a folder whose
 * {@code listFiles} answers in a fixed, unsorted order; two different such orders must give the
 * same result.
 */
@DisplayName("Module JARs are discovered in file-name order for the class loader (#476)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleJarOrderTest {

    private static final List<String> SORTED =
            Arrays.asList("alpha.jar", "bravo.jar", "charlie.jar", "delta.jar", "echo.jar");

    @TempDir
    File modules;

    /** A folder of {@code dir}'s path whose listing is exactly {@code order}, filtered as asked. */
    static File listingInOrder(File dir, List<String> order) {
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

    private void writeJars() throws IOException {
        for (String name : SORTED) {
            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(new File(modules, name)))) {
                out.putNextEntry(new JarEntry("plugin.yml"));
                out.write(("name: " + name + "\nmain: com.example.Main\n").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static List<String> names(File[] files) {
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName());
        }
        return names;
    }

    private static List<String> names(List<URL> urls) {
        List<String> names = new ArrayList<>();
        for (URL url : urls) {
            String path = url.getPath();
            names.add(path.substring(path.lastIndexOf('/') + 1));
        }
        return names;
    }

    @Test
    @DisplayName("the modules-folder listing is in file-name order, whatever order the file system gives")
    void listing_isInFileNameOrder() throws IOException {
        writeJars();

        File[] first = ModuleFileTransactions.moduleJars(listingInOrder(modules,
                Arrays.asList("delta.jar", "alpha.jar", "echo.jar", "charlie.jar", "bravo.jar")));
        File[] second = ModuleFileTransactions.moduleJars(listingInOrder(modules,
                Arrays.asList("echo.jar", "delta.jar", "charlie.jar", "bravo.jar", "alpha.jar")));

        assertThat(names(first)).containsExactlyElementsOf(SORTED);
        assertThat(names(second)).containsExactlyElementsOf(SORTED);
    }

    @Test
    @DisplayName("the module class loader's URLs are in file-name order, whatever order the file system gives")
    void classLoaderUrls_areInFileNameOrder() throws IOException {
        writeJars();

        List<URL> first = UltiTools.collectModuleJarUrls(listingInOrder(modules,
                Arrays.asList("charlie.jar", "echo.jar", "alpha.jar", "delta.jar", "bravo.jar")));
        List<URL> second = UltiTools.collectModuleJarUrls(listingInOrder(modules,
                Arrays.asList("bravo.jar", "delta.jar", "echo.jar", "alpha.jar", "charlie.jar")));

        assertThat(names(first)).containsExactlyElementsOf(SORTED);
        assertThat(names(second)).containsExactlyElementsOf(SORTED);
    }

    @Test
    @DisplayName("the real file system: five JARs created in reverse order are listed in file-name order")
    void realFolder_isListedInFileNameOrder() throws IOException {
        List<String> reversed = new ArrayList<>(SORTED);
        java.util.Collections.reverse(reversed);
        for (String name : reversed) {
            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(new File(modules, name)))) {
                out.putNextEntry(new JarEntry("plugin.yml"));
                out.write("name: x\n".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        assertThat(names(ModuleFileTransactions.moduleJars(modules))).containsExactlyElementsOf(SORTED);
        assertThat(names(UltiTools.collectModuleJarUrls(modules))).containsExactlyElementsOf(SORTED);
    }
}
