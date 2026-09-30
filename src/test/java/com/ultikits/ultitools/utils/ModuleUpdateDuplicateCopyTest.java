package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.ModuleJarIndex;

/**
 * {@code /upm update} refuses, before anything is downloaded, when another copy of the module --
 * another JAR in the modules folder whose {@code plugin.yml} declares the same {@code main:} --
 * would load instead of the new JAR at the next start (maintainer follow-up 19, gate-1 finding
 * B-I-03).
 *
 * <p>All module JARs share one class loader whose class path is in file-name order (#476), so a
 * copy whose file name sorts before the new JAR's supplies the module's classes, the new JAR is
 * refused as a duplicate, and the start-up observation rolls the update back -- every time. A copy
 * that sorts after the new JAR is harmless: the new JAR wins and the start-up duplicate warning
 * already names the copy. Only {@code plugin.yml} is read; no class is read out of any archive.
 */
@DisplayName("/upm update refuses when another copy of the module would load first (follow-up 19, B-I-03)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateDuplicateCopyTest {

    /** The main class every {@link ModuleUpdateFixtures#moduleJar} declares. */
    private static final String MAIN = "com.example.demo.DemoModule";
    private static final String OTHER_MAIN = "com.example.other.OtherModule";

    @TempDir
    File serverRoot;

    private File dataFolder;
    private File modules;
    private File oldJar;
    private UltiToolsPlugin loadedOld;
    private final List<String> downloads = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        loadedOld = loadedModule("Demo", "1.0", "demo");
    }

    /** A module JAR declaring {@code main} and nothing else of note. */
    private File copy(String fileName, String main) throws IOException {
        File jar = new File(modules, fileName);
        String yml = "name: DemoCopy\nversion: '0.9'\nmain: " + main + "\nidentify-string: demo\n";
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(yml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /** Stages with the loader-recorded main class supplied, as the command does. */
    private ModuleFileTransactions.StageResult stage(Function<UltiToolsPlugin, String> mainClassOf) {
        return new ModuleFileTransactions(dataFolder).stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                (link, name, folder) -> {
                    downloads.add(name);
                    moduleJar(new File(folder, name), "Demo", "1.1", "demo");
                }, mainClassOf);
    }

    private ModuleFileTransactions.StageResult stage() {
        return stage(module -> module == loadedOld ? MAIN : null);
    }

    /** Every file under the server root with its bytes, to prove "nothing changed". */
    private Map<String, String> snapshot() throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String path : treeOf(serverRoot)) {
            // The path comes from listing this test's own temporary folder; nothing is external input.
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
            files.put(path, new String(Files.readAllBytes(new File(serverRoot, path).toPath()),
                    StandardCharsets.ISO_8859_1));
        }
        return files;
    }

    @Test
    @DisplayName("a copy that sorts before the new JAR is refused: nothing downloaded, nothing written, the file named")
    void copyThatSortsFirst_isRefusedAndNamed() throws IOException {
        copy("a-demo-0.9.jar", MAIN);
        Map<String, String> before = snapshot();

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OTHER_COPY_LOADS_FIRST);
        assertThat(result.getReasonArgs()).containsExactly("demo-1.1.jar", "a-demo-0.9.jar");
        assertThat(downloads).as("refused before the download").isEmpty();
        assertThat(snapshot()).as("no record, no staged file, no file touched").isEqualTo(before);
    }

    @Test
    @DisplayName("every copy that sorts before the new JAR is named, in file-name order")
    void everyCopyThatSortsFirst_isNamed() throws IOException {
        copy("b-demo.jar", MAIN);
        copy("a-demo-0.9.jar", MAIN);
        copy("zz-demo.jar", MAIN);
        Map<String, String> before = snapshot();

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OTHER_COPY_LOADS_FIRST);
        assertThat(result.getReasonArgs()).as("the copy after the new JAR is not in the refusal")
                .containsExactly("demo-1.1.jar", "a-demo-0.9.jar, b-demo.jar");
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("the order is plain code-point order, as the loader's: an upper-case name sorts before a lower-case one")
    void upperCaseCopy_sortsFirst() throws IOException {
        copy("Demo-0.9.jar", MAIN);

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OTHER_COPY_LOADS_FIRST);
        assertThat(result.getReasonArgs()).containsExactly("demo-1.1.jar", "Demo-0.9.jar");
        assertThat(downloads).isEmpty();
    }

    @Test
    @DisplayName("a copy the start-up excludes from the class path (it fails the JAR check) cannot load first, so it does not stop the update (Codex round 17)")
    void copyExcludedFromTheClassPath_doesNotStopTheUpdate() throws IOException {
        // Readable, declaring the module's main class, sorting first -- but over the entry limit, so
        // the start-up leaves it off the module class path and never scans it.
        File invalid = new File(modules, "a-demo-0.9.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(invalid))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: DemoCopy\nversion: '0.9'\nmain: " + MAIN + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (int i = 0; i <= 10_000; i++) {
                out.putNextEntry(new JarEntry("filler/" + i));
                out.closeEntry();
            }
        }
        assertThat(SecurityPolicy.isValidModuleJar(invalid)).as("the start-up rejects it").isFalse();

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        assertThat(downloads).containsExactly("demo-1.1.jar");
    }

    @Test
    @DisplayName("a copy that sorts after the new JAR does not stop the update")
    void copyThatSortsAfter_proceeds() throws IOException {
        copy("zz-demo.jar", MAIN);

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        assertThat(downloads).containsExactly("demo-1.1.jar");
    }

    @Test
    @DisplayName("no other copy: the update proceeds, and a JAR declaring another main class that sorts first is not a copy")
    void noCopy_proceeds() throws IOException {
        copy("a-other.jar", OTHER_MAIN);

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        assertThat(downloads).containsExactly("demo-1.1.jar");
    }

    @Test
    @DisplayName("a copy is judged by what its plugin.yml declares now, so a file replaced since the start no longer counts")
    void copyJudgedByItsCurrentDeclaration() throws IOException {
        // Say the loader recorded a-demo-0.9.jar as declaring MAIN at the start and it has since
        // been replaced by a JAR declaring another main class: the next start will not load the
        // module from it, so it must not stop the update (an index read alone would go stale here).
        copy("a-demo-0.9.jar", OTHER_MAIN);

        ModuleFileTransactions.StageResult result = stage();

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
    }

    @Test
    @DisplayName("once the operator removes the named copy, the next /upm update proceeds without a restart")
    void removedCopy_noLongerRefuses() throws IOException {
        File stray = copy("a-demo-0.9.jar", MAIN);
        assertThat(stage().getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OTHER_COPY_LOADS_FIRST);

        Files.delete(stray.toPath());
        ModuleFileTransactions.StageResult retried = stage();

        assertThat(retried.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        assertThat(downloads).containsExactly("demo-1.1.jar");
    }

    @Test
    @DisplayName("without a recorded main class, the old JAR's own plugin.yml main: is used")
    void withoutRecordedMainClass_theOldJarsDeclarationIsUsed() throws IOException {
        copy("a-demo-0.9.jar", MAIN);
        Map<String, String> before = snapshot();

        ModuleFileTransactions.StageResult fromOverload = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                Collections.singletonList(loadedOld), new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar),
                catalogue("demo", "1.1"), (link, name, folder) -> downloads.add(name));
        ModuleFileTransactions.StageResult unrecorded = stage(module -> null);

        assertThat(fromOverload.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OTHER_COPY_LOADS_FIRST);
        assertThat(unrecorded.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OTHER_COPY_LOADS_FIRST);
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("the command's main class comes from the loader's index: the declared class, even for a subclass instance")
    void commandResolvesTheMainClassFromTheIndex() {
        ModuleJarIndex index = new ModuleJarIndex();
        index.record(IndexedModule.class.getName(), oldJar);
        UltiToolsPlugin proxyLike = mock(IndexedModule.class);

        Function<UltiToolsPlugin, String> resolver = PluginInstallUtils.recordedMainClass(index);

        assertThat(resolver.apply(proxyLike)).isEqualTo(IndexedModule.class.getName());
        assertThat(resolver.apply(loadedOld)).as("a module the index has no record of: the old JAR's plugin.yml decides")
                .isNull();
        assertThat(PluginInstallUtils.recordedMainClass(null).apply(proxyLike))
                .as("no index: nothing recorded, so the old JAR's plugin.yml decides").isNull();
    }

    /** A module class the index records; a mock of it is a subclass, as a proxy would be. */
    static class IndexedModule extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }
}
