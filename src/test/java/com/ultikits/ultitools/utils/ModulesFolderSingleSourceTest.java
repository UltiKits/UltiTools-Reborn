package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The modules folder is computed in exactly one method, and every place that needs it calls that
 * method (#517). Before, {@code PluginManager#init} scanned {@code user.dir} +
 * {@code /plugins/UltiTools/plugins} while the class loader, install, update and uninstall used the
 * data folder, so a server started with a working directory other than its root loaded modules from
 * one folder and managed them in another.
 *
 * <p>A source-level check, because the defect is two computations of one path: the test fails if a
 * second computation appears anywhere under {@code src/main/java}, or if a named caller stops using
 * the one method.
 */
@DisplayName("The modules folder is computed by one method, used by every caller (#517)")
class ModulesFolderSingleSourceTest {

    private static final Path SOURCES = Paths.get("src/main/java/com/ultikits/ultitools");

    /** Every way this codebase has spelled "the data folder plus plugins". */
    private static final Pattern SECOND_COMPUTATION = Pattern.compile(
            "getDataFolder\\(\\)\\s*\\+\\s*(File\\.separator\\s*\\+\\s*)?\"/?plugins\""
                    + "|getDataFolder\\(\\)\\s*,\\s*\"plugins\""
                    + "|getDataFolder\\(\\)\\.getAbsolutePath\\(\\)\\s*\\+\\s*\"/plugins\""
                    + "|\"plugins\"\\s*\\+\\s*File\\.separator\\s*\\+\\s*\"UltiTools\"");

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(SOURCES.resolve(relative)), StandardCharsets.UTF_8);
    }

    /** The body of the first method or constructor with this name, found by brace matching. */
    private static String body(String source, String methodName) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\([^;{]*\\)\\s*(throws[^{]*)?\\{")
                .matcher(source);
        assertThat(matcher.find()).as("method %s exists", methodName).isTrue();
        int depth = 0;
        for (int i = matcher.end() - 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(matcher.end(), i);
            }
        }
        throw new AssertionError("unbalanced body of " + methodName);
    }

    @Test
    @DisplayName("no file under src/main/java computes the modules folder except ModuleFileTransactions#modulesFolder")
    void noSecondComputation() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (SECOND_COMPUTATION.matcher(lines[i]).find()) {
                        hits.add(SOURCES.relativize(file) + ":" + (i + 1) + ": " + lines[i].trim());
                    }
                }
            }
        }
        assertThat(hits).isEmpty();
        assertThat(body(read("utils/ModuleFileTransactions.java"), "File modulesFolder"))
                .contains("new File(dataFolder, MODULES_FOLDER_NAME)");
    }

    @Test
    @DisplayName("the control: the pattern finds the computation it is looking for")
    void patternFindsAComputation() {
        assertThat(SECOND_COMPUTATION.matcher("new File(getDataFolder(), \"plugins\")").find()).isTrue();
        assertThat(SECOND_COMPUTATION.matcher("UltiTools.getInstance().getDataFolder() + \"/plugins\"").find()).isTrue();
        assertThat(SECOND_COMPUTATION.matcher("currentPath + File.separator + \"plugins\" + File.separator + \"UltiTools\"")
                .find()).isTrue();
    }

    @Test
    @DisplayName("PluginManager#init no longer reads user.dir for the modules folder")
    void pluginManagerDoesNotReadTheWorkingDirectory() throws IOException {
        String init = body(read("manager/PluginManager.java"), "void init");
        assertThat(init).doesNotContain("user.dir").contains("modulesFolder(");
    }

    @Test
    @DisplayName("the class loader, the scan, install, update, uninstall and the transaction folder all call modulesFolder")
    void everyCallerUsesTheOneMethod() throws IOException {
        String ultiTools = read("UltiTools.java");
        assertThat(body(ultiTools, "URL[] getModuleUrls")).contains("modulesFolder(");
        assertThat(body(ultiTools, "void initPluginModules")).contains("modulesFolder(");
        String install = read("utils/PluginInstallUtils.java");
        assertThat(body(install, "boolean installLatestPlugin")).contains("modulesFolder(");
        assertThat(body(install, "boolean installPlugin")).contains("modulesFolder(");
        assertThat(body(install, "File modulesFolder")).contains("ModuleFileTransactions.modulesFolder(");
        String transactions = read("utils/ModuleFileTransactions.java");
        assertThat(body(transactions, "public ModuleFileTransactions")).contains("modulesFolder(dataFolder)");
        assertThat(body(transactions, "File transactionsFolder")).contains("modulesFolder(dataFolder)");
        assertThat(body(read("commands/PluginInstallCommands.java"), "void uninstallPlugin"))
                .doesNotContain("\"/plugins\"");
    }
}
