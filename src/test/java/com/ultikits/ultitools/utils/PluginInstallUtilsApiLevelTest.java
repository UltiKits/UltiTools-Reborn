package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ModuleApiLevels;

/**
 * {@code /upm install} against a catalogue stub (plan 18-40): every catalogue request carries the
 * framework's API level and the lowest level it installs, the download is written to a name the
 * module loader never reads, its {@code plugin.yml} is read again, and a module outside
 * {@code 630..<framework level>} is refused with a reason naming the levels and nothing left in the
 * modules folder.
 */
@DisplayName("/upm install sends the API level and refuses a downloaded module outside the accepted range (18-40)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsApiLevelTest {

    @TempDir
    File serverRoot;

    private File modules;
    private HttpServer server;
    private String base;
    private MockedStatic<UltiTools> ultiTools;

    /** Every request the stub received, as raw path plus "?" plus raw query. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    /** The JAR served for each version. */
    private final Map<String, byte[]> jars = new HashMap<>();
    /** Status answered by {@code /plugin/7/latest}; 200 serves {@link #latest}. */
    private int latestStatus = 200;
    private String latest = "1.1";
    /** Versions whose {@code /download} answers 409. */
    private final List<String> incompatibleVersions = new ArrayList<>();
    /** What {@code /plugin/7/versions} answers. */
    private List<String> versions = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = new File(new File(serverRoot, "plugins"), "UltiTools");
        assertThat(dataFolder.mkdirs()).isTrue();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        assertThat(modules.mkdirs()).isTrue();

        UltiTools instance = mock(UltiTools.class);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        ultiTools = mockStatic(UltiTools.class);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        frameworkAt(630);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        PluginInstallUtils.setBaseUrlForTesting(base);
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        server.stop(0);
        ultiTools.close();
    }

    private void frameworkAt(int level) {
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(level);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(path + (query == null ? "" : "?" + query));
        if ("/plugin/get".equals(path)) {
            send(exchange, 200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":{\"id\":7,\"identifyString\":\"demo\"}}");
        } else if ("/plugin/7/latest".equals(path)) {
            send(exchange, latestStatus, latestStatus == 200
                    ? "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"" + latest + "\"}"
                    : "{\"code\":\"" + latestStatus + "\",\"msg\":\"No version of this module is compatible\"}");
        } else if ("/plugin/7/versions".equals(path)) {
            StringBuilder data = new StringBuilder("[");
            for (String version : versions) {
                data.append(data.length() > 1 ? "," : "").append('"').append(version).append('"');
            }
            send(exchange, 200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":" + data + "]}");
        } else if (path.startsWith("/plugin/7/") && path.endsWith("/download")) {
            String version = path.substring("/plugin/7/".length(), path.length() - "/download".length());
            if (incompatibleVersions.contains(version)) {
                send(exchange, 409, "{\"code\":\"409\",\"msg\":\"Version " + version + " is not compatible\"}");
            } else {
                send(exchange, 200, "{\"code\":\"200\",\"msg\":\"Success\",\"data\":\"" + base + "/files/" + version
                        + ".jar\"}");
            }
        } else if (path.startsWith("/files/")) {
            byte[] jar = jars.get(path.substring("/files/".length(), path.length() - ".jar".length()));
            exchange.sendResponseHeaders(jar == null ? 404 : 200, jar == null ? -1 : jar.length);
            if (jar != null) {
                exchange.getResponseBody().write(jar);
            }
            exchange.close();
        } else {
            send(exchange, 404, "{\"code\":\"404\",\"msg\":\"Not found\"}");
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** A module JAR whose plugin.yml declares {@code apiVersion}, or no api-version when it is {@code null}. */
    private static byte[] moduleJar(String version, String apiVersion) throws IOException {
        StringBuilder yml = new StringBuilder()
                .append("name: Demo\n")
                .append("version: '").append(version).append("'\n")
                .append("main: com.example.demo.DemoModule\n")
                .append("identify-string: demo\n");
        if (apiVersion != null) {
            yml.append("api-version: ").append(apiVersion).append('\n');
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/example/demo/DemoModule.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private List<String> modulesFolderNames() {
        String[] names = modules.list();
        List<String> list = new ArrayList<>();
        if (names != null) {
            Collections.addAll(list, names);
        }
        Collections.sort(list);
        return list;
    }

    private List<String> catalogueRequests() {
        List<String> catalogue = new ArrayList<>();
        synchronized (requests) {
            for (String request : requests) {
                if (request.startsWith("/plugin/")) {
                    catalogue.add(request);
                }
            }
        }
        return catalogue;
    }

    private boolean downloadedAnything() {
        synchronized (requests) {
            return requests.stream().anyMatch(request -> request.startsWith("/files/"));
        }
    }

    private static Map<String, String> queryOf(String request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        int question = request.indexOf('?');
        if (question < 0) {
            return parameters;
        }
        for (String pair : request.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            parameters.put(equals < 0 ? pair : pair.substring(0, equals), equals < 0 ? "" : pair.substring(equals + 1));
        }
        return parameters;
    }

    @Test
    @DisplayName("a JAR declaring api-version 630 installs under <identify-string>-<version>.jar, and every catalogue request carried apiLevel=630 and minApiLevel=630")
    void compatibleModule_installsAndEveryRequestCarriesTheLevel() throws IOException {
        jars.put("1.1", moduleJar("1.1", "630"));

        assertThat(PluginInstallUtils.installLatestPlugin("demo")).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        List<String> catalogue = catalogueRequests();
        assertThat(catalogue).isNotEmpty();
        for (String request : catalogue) {
            assertThat(queryOf(request)).as(request)
                    .containsEntry("apiLevel", "630")
                    .containsEntry("minApiLevel", "630");
        }
    }

    @Test
    @DisplayName("a JAR declaring api-version 620 is refused after download: nothing in the modules folder, no .part file, and the reason names 620, 630 and the range 630..630")
    void olderModule_isRefusedAndNothingIsLeft() throws IOException {
        jars.put("1.1", moduleJar("1.1", "620"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "620", 630, 630, 630);
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
        assertThat(PluginInstallUtils.installLatestPlugin("demo")).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a JAR declaring an api-version above the framework's level is refused the same way")
    void newerModule_isRefused() throws IOException {
        jars.put("1.1", moduleJar("1.1", "640"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "640", 630, 630, 630);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("the upper bound is the framework's own level: on a framework at 640 a 640 module installs and the requests carry apiLevel=640")
    void frameworkLevelIsTheUpperBound() throws IOException {
        frameworkAt(640);
        jars.put("1.1", moduleJar("1.1", "640"));

        assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        for (String request : catalogueRequests()) {
            assertThat(queryOf(request)).as(request)
                    .containsEntry("apiLevel", "640")
                    .containsEntry("minApiLevel", String.valueOf(ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL));
        }
    }

    @Test
    @DisplayName("a JAR declaring no api-version is refused, the reason saying it declares none")
    void moduleWithoutApiVersion_isRefused() throws IOException {
        jars.put("1.1", moduleJar("1.1", null));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_JAR_NO_LEVEL);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", 630, 630, 630);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a JAR declaring a non-integer api-version (Bukkit style '1.19') is refused, naming the value as written")
    void moduleWithBukkitStyleApiVersion_isRefused() throws IOException {
        jars.put("1.1", moduleJar("1.1", "'1.19'"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "1.19", 630, 630, 630);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("the catalogue answering latest with 409: refused as 'no compatible version', nothing downloaded")
    void latestConflict_noCompatibleVersion() {
        latestStatus = 409;

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_NO_COMPATIBLE_VERSION);
        assertThat(result.getReasonArgs()).containsExactly("demo", 630, 630, 630);
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a stale .part file of the same name is replaced, and an accepted install leaves no .part file")
    void stalePartFile_isReplaced() throws IOException {
        Files.write(new File(modules, "demo-1.1.jar.part").toPath(), "stale".getBytes(StandardCharsets.UTF_8));
        jars.put("1.1", moduleJar("1.1", "630"));

        assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        assertThat(Files.readAllBytes(new File(modules, "demo-1.1.jar").toPath())).isEqualTo(jars.get("1.1"));
    }

    @Test
    @DisplayName("an accepted install over an existing file of the final name replaces it, as the download always did")
    void existingFinalName_isOverwritten() throws IOException {
        try (OutputStream out = Files.newOutputStream(new File(modules, "demo-1.1.jar").toPath())) {
            out.write("old".getBytes(StandardCharsets.UTF_8));
        }
        jars.put("1.1", moduleJar("1.1", "630"));

        assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        assertThat(Files.readAllBytes(new File(modules, "demo-1.1.jar").toPath())).isEqualTo(jars.get("1.1"));
    }

    @Test
    @DisplayName("/upm install demo 2.0.0, the catalogue answering 409 for that version: refused as 'this version cannot be installed', nothing downloaded")
    void namedVersionConflict_isRefusedWithoutDownload() {
        incompatibleVersions.add("2.0.0");

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "2.0.0");

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_VERSION_INCOMPATIBLE);
        assertThat(result.getReasonArgs()).containsExactly("demo", "2.0.0", 630, 630, 630);
        assertThat(PluginInstallUtils.installPlugin("demo", "2.0.0")).isFalse();
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("/upm install demo 1.0.0 from a catalogue that does not filter, the JAR declaring 620: refused after download, nothing left")
    void namedVersionFromUnfilteredCatalogue_isRefusedAfterDownload() throws IOException {
        jars.put("1.0.0", moduleJar("1.0.0", "620"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.0.0");

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.0.0", "620", 630, 630, 630);
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("/upm versions reads the level-filtered list: the request carries both parameters and an empty answer is an empty list, not a failure")
    void versionsRequestCarriesTheLevel() {
        versions = new ArrayList<>();

        assertThat(PluginInstallUtils.getPluginVersions("demo")).isEmpty();

        assertThat(catalogueRequests()).anySatisfy(request -> {
            assertThat(request).startsWith("/plugin/7/versions?");
            assertThat(queryOf(request)).containsEntry("apiLevel", "630").containsEntry("minApiLevel", "630");
        });
    }
}
