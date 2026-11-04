package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.stub.StubCatalogue;

/** Real loopback HTTP pagination, including the unchanged public and command consumers. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CatalogueClientPagingTest {
    private static final String MODULE = "demo";
    private static final String MODULE_ROUTE = "/v1/catalogue/modules/" + MODULE;
    private static final String VERSIONS_ROUTE = MODULE_ROUTE + "/versions";
    private static final int LEVEL = 631;
    private static final int JSON_LIMIT = 4_194_304;

    @TempDir
    File serverRoot;

    private HttpServer server;
    private UltiTools instance;
    private MockedStatic<UltiTools> ultiTools;
    private File modules;
    private byte[] jar;
    private String baseUrl;
    private final List<JsonObject> entries = new ArrayList<>();
    private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, String> bodies = new HashMap<>();
    private final Map<Integer, Integer> statuses = new HashMap<>();
    private final AtomicInteger downloads = new AtomicInteger();
    private int disconnectedPage;
    private int advertisedTotal = -1;
    private String singleBody;

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = new File(serverRoot, "plugins/UltiTools");
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        assertThat(modules.mkdirs()).isTrue();
        instance = mock(UltiTools.class);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        when(instance.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        ultiTools = mockStatic(UltiTools.class);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(LEVEL);
        jar = StubCatalogue.moduleJar("Demo", "1.0.11", MODULE, "630");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        PluginInstallUtils.setBaseUrlForTesting(baseUrl);
        populate(12);
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        if (server != null) {
            server.stop(0);
        }
        if (ultiTools != null) {
            ultiTools.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 10, 12, 27})
    void everyVersionIsReturnedInCatalogueOrderWithoutAnExtraRequest(int count) {
        populate(count);
        assertThat(PluginInstallUtils.getPluginVersions("  DeMo ")).containsExactlyElementsOf(names());
        assertQueries(Math.max(1, (count + 4) / 5));
        queries.clear();
        List<String> names = new ArrayList<>();
        VersionLookup lookup = PluginInstallUtils.lookUpPluginVersions(MODULE, names);
        assertThat(lookup.getOutcome()).isEqualTo(VersionLookup.Outcome.FOUND);
        assertThat(names).containsExactlyElementsOf(names());
        assertQueries(Math.max(1, (count + 4) / 5));
    }

    @Test
    void upmVersionsPrintsEveryOneOfTwelveVersions() {
        CommandSender sender = mock(CommandSender.class);
        PluginInstallCommands command = mock(PluginInstallCommands.class, CALLS_REAL_METHODS);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            command.listVersions(sender, MODULE);
        }
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(message.capture());
        for (int i = 0; i < entries.size(); i++) {
            assertThat(message.getValue()).contains((i + 1) + ". 版本：" + names().get(i) + "\n");
        }
        assertQueries(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"versions", "install-latest", "install-version", "update", "update-all"})
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void catalogueCommandsReadOnARealWorkerAndDeliverOutputOnTheCallingThread(String action) throws Exception {
        Thread callingThread = Thread.currentThread();
        List<Thread> networkThreads = Collections.synchronizedList(new ArrayList<>());
        List<Thread> messageThreads = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        List<Runnable> asyncTasks = new ArrayList<>();
        List<Runnable> mainTasks = Collections.synchronizedList(new ArrayList<>());
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            mainTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));
        CommandSender sender = mock(CommandSender.class);
        doAnswer(invocation -> {
            messageThreads.add(Thread.currentThread());
            messages.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(anyString());
        entries.get(10).addProperty("isLatest", true);
        UpdateManager updates = mock(UpdateManager.class);
        UpdateInfo info = new UpdateInfo();
        info.setPluginName("Demo");
        info.setIdentifyString(MODULE);
        when(updates.getModuleUpdates()).thenReturn(Collections.singletonMap("Demo", info));
        when(updates.getUnconfirmed()).thenReturn(Collections.emptyList());
        when(instance.getUpdateManager()).thenReturn(updates);
        PluginInstallCommands command = mock(PluginInstallCommands.class, CALLS_REAL_METHODS);
        boolean versioned = "install-version".equals(action);
        String methodName = "versions".equals(action) ? "listVersions"
                : action.startsWith("install") ? "installPlugin" : "updatePlugin";
        Method method = versioned ? PluginInstallCommands.class.getMethod(methodName,
                CommandSender.class, String.class, String.class)
                : PluginInstallCommands.class.getMethod(methodName, CommandSender.class, String.class);
        String argument = "update-all".equals(action) ? "all" : action.startsWith("update") ? "Demo" : MODULE;
        Object[] params = versioned ? new Object[]{sender, argument, "1.0.11"} : new Object[]{sender, argument};
        Method dispatch = BaseCommandExecutor.class.getDeclaredMethod("executeCommand", CommandContext.class,
                Method.class, Object[].class, ValidatorChain.ChainValidationResult.class);
        dispatch.setAccessible(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<CatalogueHttp> http = recordingHttp(networkThreads);
             MockedStatic<PluginInstallUtils> utils = stagingThroughRealCatalogue()) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            dispatch.invoke(command, CommandContext.builder().sender(sender).build(), method, params, null);
            assertThat(networkThreads).as("dispatch must not perform catalogue I/O").isEmpty();
            assertThat(asyncTasks).hasSize(1);
            FutureTask<Void> work = new FutureTask<>(() -> {
                try (MockedStatic<UltiTools> workerTools = mockStatic(UltiTools.class);
                     MockedStatic<Bukkit> workerBukkit = mockStatic(Bukkit.class);
                     MockedStatic<CatalogueHttp> workerHttp = recordingHttp(networkThreads);
                     MockedStatic<PluginInstallUtils> workerUtils = stagingThroughRealCatalogue()) {
                    workerTools.when(UltiTools::getInstance).thenReturn(instance);
                    workerTools.when(UltiTools::getPluginVersion).thenReturn(LEVEL);
                    workerBukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
                    workerBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                    asyncTasks.get(0).run();
                }
                return null;
            });
            Thread worker = new Thread(work, "catalogue-command-test");
            worker.start();
            try {
                work.get(20, TimeUnit.SECONDS);
            } finally {
                worker.join(20_000);
            }
            assertThat(worker.isAlive()).isFalse();
            assertThat(networkThreads).isNotEmpty().allSatisfy(thread -> {
                assertThat(thread).isSameAs(worker).isNotSameAs(callingThread);
            });
            assertThat(messages).as("no sender API calls on the worker").isEmpty();
            assertThat(mainTasks).isNotEmpty();
            mainTasks.forEach(Runnable::run);
            assertThat(messageThreads).isNotEmpty().allSatisfy(thread -> assertThat(thread).isSameAs(callingThread));
            assertThat(messages).anySatisfy(message -> assertThat(message).contains(
                    "versions".equals(action) ? "版本列表" : action.startsWith("install") ? "安装成功" : "未能暂存"));
            if ("versions".equals(action)) {
                for (String version : names()) {
                    assertThat(String.join("\n", messages)).contains(version);
                }
            }
            assertQueries(versioned ? 0 : 3);
        }
    }

    private static MockedStatic<CatalogueHttp> recordingHttp(List<Thread> networkThreads) {
        MockedStatic<CatalogueHttp> http = mockStatic(CatalogueHttp.class, CALLS_REAL_METHODS);
        http.when(() -> CatalogueHttp.getJson(anyString())).thenAnswer(invocation -> {
            networkThreads.add(Thread.currentThread());
            return invocation.callRealMethod();
        });
        return http;
    }

    private static MockedStatic<PluginInstallUtils> stagingThroughRealCatalogue() {
        MockedStatic<PluginInstallUtils> utils = mockStatic(PluginInstallUtils.class, CALLS_REAL_METHODS);
        utils.when(() -> PluginInstallUtils.stageUpdate(MODULE)).thenAnswer(invocation -> {
            assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getVersion()).isEqualTo("1.0.11");
            ModuleFileTransactions.StageResult result = mock(ModuleFileTransactions.StageResult.class);
            when(result.getOutcome()).thenReturn(ModuleFileTransactions.StageResult.Outcome.FAILED);
            when(result.getReasonKey()).thenReturn("test failure");
            when(result.getReasonArgs()).thenReturn(new Object[0]);
            return result;
        });
        return utils;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void allFourLatestConsumersSeeTheThirdPageMarkerOrSecondPageMaximum(boolean markedLatest) throws IOException {
        entries.get(10).addProperty("isLatest", markedLatest);
        entries.get(6).addProperty("version", "9.0.0");
        entries.get(6).getAsJsonObject("download").addProperty("url", downloadUrl("9.0.0"));
        String selected = markedLatest ? "1.0.11" : "9.0.0";
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getVersion()).isEqualTo(selected);
        assertQueries(3);
        queries.clear();
        assertThat(PluginInstallUtils.CATALOGUE.latestVersion(MODULE)).isEqualTo(selected);
        assertQueries(3);
        queries.clear();
        assertThat(PluginInstallUtils.getPluginLatestDownloadLink(MODULE)).isEqualTo(downloadUrl(selected));
        assertQueries(3);
        queries.clear();
        PluginInstallUtils.InstallResult installed = PluginInstallUtils.installReporting(MODULE, null);
        assertThat(installed.isInstalled()).isTrue();
        assertThat(installed.getFileName()).isEqualTo("demo-" + selected + ".jar");
        assertThat(Files.readAllBytes(new File(modules, installed.getFileName()).toPath())).isEqualTo(jar);
        assertThat(downloads.get()).isEqualTo(1);
        assertQueries(3);
    }

    @Test
    void latestStopsAfterTheFirstMarkerButListsStillReadEveryPage() {
        entries.get(0).addProperty("isLatest", true);
        statuses.put(2, 500);
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getVersion()).isEqualTo("1.0.1");
        assertQueries(1);
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"500", "404", "redirect", "unreadable", "invalid-version", "invalid-items",
            "oversize", "disconnected"})
    void anyRequestedLaterPageFailureDiscardsAllCollectedVersions(String failure) {
        switch (failure) {
            case "500": statuses.put(2, 500); break;
            case "404": statuses.put(2, 404); break;
            case "redirect": statuses.put(2, 302); break;
            case "unreadable": bodies.put(2, "{broken"); break;
            case "invalid-version": entries.get(5).remove("sha256"); break;
            case "invalid-items": bodies.put(2, "{\"data\":{\"total\":12,\"items\":[null]}}"); break;
            case "oversize": bodies.put(2, repeat(' ', JSON_LIMIT + 1)); break;
            case "disconnected": disconnectedPage = 2; break;
            default: throw new AssertionError(failure);
        }
        List<CatalogueClient.Version> collected = new ArrayList<>();
        assertThat(CatalogueClient.lookUpVersions(MODULE, LEVEL, collected).isFailed()).isTrue();
        assertThat(collected).isEmpty();
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).isNull();
        List<String> commandNames = new ArrayList<>();
        assertThat(PluginInstallUtils.lookUpPluginVersions(MODULE, commandNames).isFailed()).isTrue();
        assertThat(commandNames).isEmpty();
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).isFailed()).isTrue();
        assertThat(queries).allSatisfy(query -> assertThat(Integer.parseInt(parameters(query).get("page")))
                .isBetween(1, 2));
        assertThat(downloads.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "-1", "1.5", "\"12\"", "true", "{}"})
    void missingNegativeOrNonIntegerTotalFailsOnAnyPage(String total) {
        for (int page : new int[]{1, 2}) {
            JsonObject body = page(page, 5);
            if ("missing".equals(total)) {
                body.getAsJsonObject("data").remove("total");
            } else {
                body.getAsJsonObject("data").add("total", JsonParser.parseString(total));
            }
            bodies.put(page, body.toString());
            List<CatalogueClient.Version> collected = new ArrayList<>();
            assertThat(CatalogueClient.lookUpVersions(MODULE, LEVEL, collected).isFailed()).as(total).isTrue();
            assertThat(collected).isEmpty();
            assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).isFailed()).as(total).isTrue();
            bodies.clear();
        }
    }

    @Test
    void firstPage404IsNoneAndASuccessfulEmptyListIsFound() {
        statuses.put(1, 404);
        List<CatalogueClient.Version> collected = new ArrayList<>();
        assertThat(CatalogueClient.lookUpVersions(MODULE, LEVEL, collected).getOutcome())
                .isEqualTo(VersionLookup.Outcome.NONE);
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getOutcome()).isEqualTo(VersionLookup.Outcome.NONE);
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).isEmpty();
        statuses.clear();
        populate(0);
        assertThat(CatalogueClient.lookUpVersions(MODULE, LEVEL, collected).getOutcome())
                .isEqualTo(VersionLookup.Outcome.FOUND);
        assertThat(collected).isEmpty();
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getOutcome()).isEqualTo(VersionLookup.Outcome.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ignored-page", "wrong-size", "missing-page", "string-page", "fractional-page",
            "missing-size", "string-size", "fractional-size", "oversized-page", "short-page", "empty-page",
            "duplicate-version"})
    void inconsistentPagesFailWithoutExposingAPartialList(String failure) {
        JsonObject second = page(2, 5);
        JsonObject data = second.getAsJsonObject("data");
        switch (failure) {
            case "ignored-page": second = page(1, 5); break;
            case "wrong-size": data.addProperty("pageSize", 20); break;
            case "missing-page": data.remove("page"); break;
            case "string-page": data.addProperty("page", "2"); break;
            case "fractional-page": data.addProperty("page", 2.5); break;
            case "missing-size": data.remove("pageSize"); break;
            case "string-size": data.addProperty("pageSize", "5"); break;
            case "fractional-size": data.addProperty("pageSize", 5.5); break;
            case "oversized-page": data.getAsJsonArray("items").add(entries.get(10).deepCopy()); break;
            case "short-page": data.getAsJsonArray("items").remove(4); break;
            case "empty-page": data.add("items", new JsonArray()); break;
            case "duplicate-version": data.getAsJsonArray("items").set(0, entries.get(0).deepCopy()); break;
            default: throw new AssertionError(failure);
        }
        bodies.put(2, second.toString());
        if ("ignored-page".equals(failure)) {
            bodies.put(3, second.toString());
        }
        List<CatalogueClient.Version> collected = new ArrayList<>();
        VersionLookup lookup = CatalogueClient.lookUpVersions(MODULE, LEVEL, collected);
        assertThat(lookup.isFailed()).as(failure).isTrue();
        assertThat(lookup.getFailure()).isEqualTo(VersionLookup.UNREADABLE);
        assertThat(collected).isEmpty();
        assertQueries(2);
        queries.clear();
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).isNull();
        assertQueries(2);
        queries.clear();
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).isFailed()).isTrue();
        assertQueries(2);
    }

    @Test
    void aShortPageCannotHideVersionsPromisedByTheTotal() {
        populate(5);
        advertisedTotal = 100;
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).isNull();
        assertQueries(2);
    }

    @Test
    void stoppingAfterTheFirstVersionPageSendsNoSecondPageRequest() {
        VersionLookup lookup = PluginInstallUtils.lookUpPluginLatestVersion(MODULE, () -> !queries.isEmpty());
        assertThat(lookup.isFailed()).isTrue();
        assertThat(lookup.getFailure()).isEqualTo("stopped");
        assertQueries(1);
    }

    @Test
    void onlyTheFirstTotalControlsTermination() {
        JsonObject second = page(2, 5);
        second.getAsJsonObject("data").addProperty("total", 1);
        bodies.put(2, second.toString());
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).containsExactlyElementsOf(names());
        assertQueries(3);
    }

    @Test
    void reachingTheFirstTotalTerminatesEvenIfMoreEntriesAreServed() {
        advertisedTotal = 5;
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).containsExactlyElementsOf(names().subList(0, 5));
        assertQueries(1);
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void terminationIncludesTheContractPageCeilingWithoutMakingAMillionHttpRequests() throws Exception {
        Method lastPage = CatalogueClient.class.getDeclaredMethod("lastVersionPage", int.class, int.class,
                int.class, BigDecimal.class);
        lastPage.setAccessible(true);
        BigDecimal total = BigDecimal.valueOf(9_000_000);
        assertThat(lastPage.invoke(null, 999_999, 5, 4_999_995, total)).isEqualTo(false);
        assertThat(lastPage.invoke(null, 1_000_000, 5, 5_000_000, total)).isEqualTo(true);
        assertThat(lastPage.invoke(null, 1, 4, 4, total)).isEqualTo(true);
        assertThat(lastPage.invoke(null, 2, 5, 10, BigDecimal.TEN)).isEqualTo(true);
    }

    @Test
    void fiveWorstEscaped64000CharacterChangelogsAndASingleVersionAreReadable() {
        populate(5);
        String changelog = repeat('\u0001', 64_000);
        entries.forEach(entry -> entry.addProperty("changelog", changelog));
        String body = page(1, 5).toString();
        assertThat(body).contains("\\u0001");
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isBetween(1_920_000, JSON_LIMIT);
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).hasSize(5);
        assertQueries(1);
        assertThat(CatalogueClient.version(MODULE, "1.0.1")).isNotNull();
        assertThat(readChangelog(CatalogueHttp.getJson(VERSIONS_ROUTE + "/1.0.1"))).isEqualTo(changelog);
    }

    @Test
    void migrated53983CharacterChangelogSurvivesBothPageAndSingleVersionReads() {
        populate(1);
        String changelog = repeat('x', 53_981) + "\n中";
        entries.get(0).addProperty("changelog", changelog);
        CatalogueHttp.Json answer = CatalogueHttp.getJson(VERSIONS_ROUTE + "?page=1&pageSize=5");
        assertThat(JsonParser.parseString(answer.body()).getAsJsonObject().getAsJsonObject("data")
                .getAsJsonArray("items").get(0).getAsJsonObject().get("changelog").getAsString())
                .hasSize(53_983).isEqualTo(changelog);
        assertThat(PluginInstallUtils.getPluginVersions(MODULE)).containsExactly("1.0.1");
        assertThat(CatalogueClient.version(MODULE, "1.0.1")).isNotNull();
        assertThat(readChangelog(CatalogueHttp.getJson(VERSIONS_ROUTE + "/1.0.1"))).isEqualTo(changelog);
    }

    @Test
    void httpJsonLimitIsExactlyFourMiBAndNeverReturnsATruncatedBody() {
        populate(1);
        String body = envelope(entries.get(0)).toString();
        singleBody = body + repeat(' ', JSON_LIMIT - body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(CatalogueHttp.getJson(VERSIONS_ROUTE + "/1.0.1").kind()).isEqualTo(CatalogueHttp.Kind.OK);
        assertThat(CatalogueClient.version(MODULE, "1.0.1")).isNotNull();
        singleBody += " ";
        CatalogueHttp.Json refused = CatalogueHttp.getJson(VERSIONS_ROUTE + "/1.0.1");
        assertThat(refused.kind()).isEqualTo(CatalogueHttp.Kind.OVERSIZE);
        assertThat(refused.body()).isNull();
        assertThat(CatalogueClient.version(MODULE, "1.0.1")).isNull();
    }

    private void populate(int count) {
        entries.clear();
        for (int i = 1; i <= count; i++) {
            JsonObject entry = ContractFixtures.catalogueBody(
                    "fixtures/versionGet/response/valid/public-download.json").getAsJsonObject("data");
            entry.addProperty("version", "1.0." + i);
            entry.addProperty("isLatest", false);
            entry.addProperty("apiLevel", 630);
            entry.addProperty("sha256", CatalogueHttp.sha256Hex(jar));
            entry.addProperty("sizeBytes", jar.length);
            entry.getAsJsonObject("download").addProperty("url", downloadUrl("1.0." + i));
            entries.add(entry);
        }
    }

    private List<String> names() {
        return entries.stream().map(entry -> entry.get("version").getAsString()).collect(Collectors.toList());
    }

    private String downloadUrl(String version) {
        return baseUrl + VERSIONS_ROUTE + "/" + version + "/download";
    }

    private void assertQueries(int count) {
        List<String> expected = IntStream.rangeClosed(1, count).mapToObj(page ->
                "apiLevel=" + LEVEL + "&minApiLevel=630&page=" + page + "&pageSize=5").collect(Collectors.toList());
        assertThat(queries).containsExactlyElementsOf(expected);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (VERSIONS_ROUTE.equals(path)) {
            String query = exchange.getRequestURI().getRawQuery();
            queries.add(query);
            Map<String, String> parameters = parameters(query);
            int number = Integer.parseInt(parameters.getOrDefault("page", "1"));
            int size = Integer.parseInt(parameters.getOrDefault("pageSize", "20"));
            if (number == disconnectedPage) {
                exchange.close();
                return;
            }
            respond(exchange, statuses.getOrDefault(number, 200),
                    bodies.getOrDefault(number, page(number, size).toString()).getBytes(StandardCharsets.UTF_8));
        } else if (MODULE_ROUTE.equals(path)) {
            respond(exchange, 200, StubCatalogue.moduleCardSample(MODULE, "Demo").getBytes(StandardCharsets.UTF_8));
        } else if (path.endsWith("/download")) {
            downloads.incrementAndGet();
            exchange.getResponseHeaders().set(CatalogueHttp.SHA256_HEADER, CatalogueHttp.sha256Hex(jar));
            respond(exchange, 200, jar);
        } else if (path.startsWith(VERSIONS_ROUTE + "/") && !entries.isEmpty()) {
            String version = path.substring((VERSIONS_ROUTE + "/").length());
            JsonObject entry = entries.stream().filter(item -> version.equals(item.get("version").getAsString()))
                    .findFirst().orElse(entries.get(0));
            respond(exchange, 200, (singleBody == null ? envelope(entry).toString() : singleBody)
                    .getBytes(StandardCharsets.UTF_8));
        } else {
            respond(exchange, 404, "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private JsonObject page(int number, int size) {
        JsonObject data = new JsonObject();
        JsonArray items = new JsonArray();
        int start = Math.min(entries.size(), (number - 1) * size);
        int end = Math.min(entries.size(), start + size);
        for (JsonObject entry : entries.subList(start, end)) {
            items.add(entry.deepCopy());
        }
        data.add("items", items);
        data.addProperty("total", advertisedTotal < 0 ? entries.size() : advertisedTotal);
        data.addProperty("page", number);
        data.addProperty("pageSize", size);
        return envelope(data);
    }

    private static JsonObject envelope(JsonElement data) {
        JsonObject body = new JsonObject();
        body.add("data", data);
        return body;
    }

    private static String readChangelog(CatalogueHttp.Json answer) {
        assertThat(answer.kind()).isEqualTo(CatalogueHttp.Kind.OK);
        return JsonParser.parseString(answer.body()).getAsJsonObject().getAsJsonObject("data")
                .get("changelog").getAsString();
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                parameters.put(parts[0], parts.length == 1 ? "" : parts[1]);
            }
        }
        return parameters;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private static String repeat(char character, int count) {
        char[] text = new char[count];
        java.util.Arrays.fill(text, character);
        return new String(text);
    }
}
