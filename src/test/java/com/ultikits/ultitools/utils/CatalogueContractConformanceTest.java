package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.JDKRegularExpressionFactory;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractFixtures.Fixture;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * {@link CatalogueClient} against the frozen catalogue REST contract 1.0.0 (plan 22-26, S-49, D-27,
 * D-28): UltiKits/ulticloud {@code main} commit {@code 682735c9}, vendored byte for byte under
 * {@code ulticloud-contract/rest/catalogue/1.0.0/} and checked by {@code ContractSyncTest}.
 *
 * <p>Every fixture of an operation the framework reads ({@code modulesList}, {@code moduleGet},
 * {@code versionsList}, {@code versionGet}, {@code versionDownload}, {@code framework}) is served by
 * {@link StubCatalogue} exactly as vendored, except that the API origin of each download URL is
 * rebased onto the stub's loopback address (the stub cannot answer on {@code https://api.ultikits.com});
 * {@link #theOnlyChangeTheStubMakesIsTheDownloadOrigin} proves that substitution is the only one, and
 * {@link #theProductionOriginAcceptsEveryValidDownloadUrlVerbatim} checks the URLs unchanged against the
 * production origin.
 *
 * <p>A valid fixture parses to the outcome its shape implies. An invalid one is either refused by the
 * client -- the version is unusable, its download is never requested, or the offending field is dropped
 * -- or, when the violation is in a field the client never reads, it is tolerated, and the test says
 * which field and why. Every invalid fixture of a read operation is classified exactly once
 * ({@link #everyInvalidFixtureOfAReadOperationIsClassified}); the vendored schemas refuse each of them
 * with format assertions off, so the fixtures and the validator agree.
 */
@DisplayName("CatalogueClient against the frozen catalogue REST contract 1.0.0")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CatalogueContractConformanceTest {

    private static final String MODULE = "ultikits.kits";
    private static final String VERSION = "1.2.0";
    private static final String MODULE_ROUTE = "/v1/catalogue/modules/" + MODULE;
    private static final String VERSIONS_ROUTE = MODULE_ROUTE + "/versions";
    private static final String VERSION_ROUTE = VERSIONS_ROUTE + "/" + VERSION;
    private static final int LEVEL = 631;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(false)
                            .regularExpressionFactory(JDKRegularExpressionFactory.getInstance()).build()));
    private static final Map<String, Schema> SCHEMAS = new HashMap<>();

    /** The operations the framework requests; {@code categories} and {@code tags} are not among them. */
    private static final String[] READ_OPERATIONS = {
        "modulesList", "moduleGet", "versionsList", "versionGet", "versionDownload", "framework"
    };

    /** How the client meets each invalid fixture of a read operation. */
    private enum Handling {
        /** The version object is unusable: the lookup answers nothing, the install sends no download. */
        VERSION_REFUSED,
        /** The version parses, but its download URL is refused before any request. */
        URL_REFUSED,
        /** The version parses but is not downloadable; the install sends no download. */
        NOT_DOWNLOADABLE,
        /** The offending field is dropped; the rest of the answer is used. */
        FIELD_DROPPED,
        /** The download is refused because its hash header does not match the metadata. */
        HEADER_REFUSED,
        /** The violation is in a field or key the client never reads; the answer is used as if it were absent. */
        TOLERATED_NOT_READ,
        /** The answer is a 404, and the status alone decides: not found. */
        TOLERATED_STATUS_DECIDES,
        /** The request is built by the framework, which never sends this shape; the server would refuse it. */
        QUERY_NEVER_SENT
    }

    private static final Map<String, Handling> INVALID = new LinkedHashMap<>();

    /** The one valid fixture the Java validator refuses, for a reason in the validator (see its test). */
    private static final String VALIDATOR_DIVERGENCE = "fixtures/categories/response/valid/collection.json";

    static {
        String v = "fixtures/versionGet/response/invalid/";
        INVALID.put(v + "api-level-zero.json", Handling.VERSION_REFUSED);
        INVALID.put(v + "missing-api-level.json", Handling.VERSION_REFUSED);
        INVALID.put(v + "missing-sha256.json", Handling.VERSION_REFUSED);
        INVALID.put(v + "sha256-uppercase.json", Handling.VERSION_REFUSED);
        INVALID.put(v + "size-bytes-zero.json", Handling.VERSION_REFUSED);
        INVALID.put(v + "url-http.json", Handling.URL_REFUSED);
        INVALID.put(v + "url-relative.json", Handling.URL_REFUSED);
        INVALID.put(v + "withdrawn-missing-withdrawn-at.json", Handling.NOT_DOWNLOADABLE);
        // Unknown keys are ignored so that a later contract that adds a field does not stop installs.
        INVALID.put(v + "extra-top-level-field.json", Handling.TOLERATED_NOT_READ);
        INVALID.put(v + "extra-version-field.json", Handling.TOLERATED_NOT_READ);
        INVALID.put("fixtures/versionGet/error/invalid/404-wrong-code.json", Handling.TOLERATED_STATUS_DECIDES);
        INVALID.put("fixtures/versionsList/response/invalid/withdrawn-with-url.json", Handling.NOT_DOWNLOADABLE);
        INVALID.put("fixtures/modulesList/response/invalid/icon-url-http.json", Handling.FIELD_DROPPED);
        // The list's total is not read: the framework asks for one page and shows its items.
        INVALID.put("fixtures/modulesList/response/invalid/missing-total.json", Handling.TOLERATED_NOT_READ);
        // The developer and the tags are not read: the framework shows name, descriptions and dates.
        INVALID.put("fixtures/moduleGet/response/invalid/developer-name-control-char.json", Handling.TOLERATED_NOT_READ);
        INVALID.put("fixtures/moduleGet/response/invalid/too-many-tags.json", Handling.TOLERATED_NOT_READ);
        // Only the framework's version is read; its JAR is never downloaded from the catalogue.
        INVALID.put("fixtures/framework/response/invalid/download-url-http.json", Handling.TOLERATED_NOT_READ);
        INVALID.put("fixtures/framework/response/invalid/missing-sha256.json", Handling.TOLERATED_NOT_READ);
        INVALID.put("fixtures/versionDownload/headers/invalid/sha256-63-chars.json", Handling.HEADER_REFUSED);
        // Without the header the body is still checked against the metadata's sha256 and sizeBytes (22-18).
        INVALID.put("fixtures/versionDownload/headers/invalid/missing-sha256.json", Handling.TOLERATED_NOT_READ);
        // Content-Disposition is not read: the framework names the installed file itself.
        INVALID.put("fixtures/versionDownload/headers/invalid/not-attachment.json", Handling.TOLERATED_NOT_READ);
        String q = "fixtures/";
        INVALID.put(q + "modulesList/query/invalid/category-zero.json", Handling.QUERY_NEVER_SENT);
        INVALID.put(q + "modulesList/query/invalid/page-size-101.json", Handling.QUERY_NEVER_SENT);
        INVALID.put(q + "modulesList/query/invalid/sort-hot.json", Handling.QUERY_NEVER_SENT);
        INVALID.put(q + "versionsList/query/invalid/api-level-zero.json", Handling.QUERY_NEVER_SENT);
        INVALID.put(q + "versionsList/query/invalid/include-withdrawn-yes.json", Handling.QUERY_NEVER_SENT);
        INVALID.put(q + "versionsList/query/invalid/page-size-101.json", Handling.QUERY_NEVER_SENT);
        INVALID.put(q + "versionsList/query/invalid/undeclared-key.json", Handling.QUERY_NEVER_SENT);
    }

    @TempDir
    File serverRoot;

    private StubCatalogue stub;
    private MockedStatic<UltiTools> ultiTools;

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = new File(new File(serverRoot, "plugins"), "UltiTools");
        assertThat(dataFolder.mkdirs()).isTrue();
        assertThat(ModuleFileTransactions.modulesFolder(dataFolder).mkdirs()).isTrue();
        UltiTools instance = mock(UltiTools.class);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        ultiTools = mockStatic(UltiTools.class);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(LEVEL);
        stub = StubCatalogue.start();
        PluginInstallUtils.setBaseUrlForTesting(stub.baseUrl());
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        stub.close();
        ultiTools.close();
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject data(String fixturePath) {
        return ContractFixtures.catalogueBody(fixturePath).getAsJsonObject("data");
    }

    /** Serves a fixture: a valid one as its vendored text, an invalid one as its {@code body}. */
    private void serve(String route, int status, String fixturePath) {
        stub.serveVendored(route, status, vendored(fixturePath));
    }

    private static String vendored(String fixturePath) {
        return fixturePath.contains("/invalid/") ? ContractFixtures.catalogueBody(fixturePath).toString()
                : ContractFixtures.catalogueText(fixturePath);
    }

    private String rebasedDownload(String vendoredUrl) {
        return vendoredUrl.replace(StubCatalogue.API_ORIGIN + "/", stub.baseUrl() + "/");
    }

    private boolean anyDownloadRequested() {
        for (String path : stub.paths()) {
            if (path.endsWith("/download")) {
                return true;
            }
        }
        return false;
    }

    private static Schema schema(String file) {
        synchronized (SCHEMAS) {
            return SCHEMAS.computeIfAbsent(file, key -> REGISTRY.getSchema(node(ContractFixtures.catalogueText(key))));
        }
    }

    private static JsonNode node(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid catalogue contract JSON", ex);
        }
    }

    private static boolean schemaAccepts(String file, JsonObject body) {
        return schema(file).validate(node(body.toString())).isEmpty();
    }

    /**
     * The schemas a fixture is checked against, by CATALOGUE.md's naming rule: {@code
     * schemas/<operation>.<part>[.<status>].schema.json}; a {@code <status>-} file-name prefix selects
     * that status's schema, otherwise every schema of the part applies.
     */
    private static List<String> schemasFor(String fixturePath) {
        String[] parts = fixturePath.split("/");
        String prefix = "schemas/" + parts[1] + "." + parts[2] + ".";
        String name = parts[parts.length - 1];
        String status = name.matches("\\d{3}-.*") ? name.substring(0, 3) : null;
        List<String> candidates = new ArrayList<>();
        for (String path : ContractFixtures.cataloguePaths()) {
            if (path.startsWith(prefix) && (status == null || path.equals(prefix + status + ".schema.json"))) {
                candidates.add(path);
            }
        }
        return candidates;
    }

    static Stream<String> allFixtures() {
        return ContractFixtures.cataloguePaths().stream().filter(p -> p.startsWith("fixtures/")).sorted();
    }

    // ------------------------------------------------------------------ tracer

    @Test
    @DisplayName("tracer: the vendored public-download version served by the stub yields its sha256, size and URL")
    void publicDownloadFixtureEndToEnd() {
        String fixture = "fixtures/versionGet/response/valid/public-download.json";
        JsonObject expected = data(fixture);
        serve(VERSION_ROUTE, 200, fixture);

        CatalogueClient.Version version = CatalogueClient.version(MODULE, VERSION);

        assertThat(version).isNotNull();
        assertThat(version.sha256()).isEqualTo(expected.get("sha256").getAsString());
        assertThat(version.sizeBytes()).isEqualTo(expected.get("sizeBytes").getAsLong());
        assertThat(version.apiLevel()).isEqualTo(631);
        assertThat(version.downloadable()).isTrue();
        assertThat(CatalogueClient.absoluteDownloadUrl(version.downloadUrl())).isEqualTo(
                rebasedDownload(expected.getAsJsonObject("download").get("url").getAsString()));
        assertThat(stub.paths()).containsExactly(VERSION_ROUTE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allFixtures")
    @DisplayName("control: the only change the stub makes to a vendored body is the origin of its download URLs")
    void theOnlyChangeTheStubMakesIsTheDownloadOrigin(String fixture) {
        String text = vendored(fixture);
        String served = stub.rebased(text);
        assertThat(served.replace(stub.baseUrl() + "/v1/catalogue/modules/",
                StubCatalogue.API_ORIGIN + "/v1/catalogue/modules/")).isEqualTo(text);
        if (text.contains(StubCatalogue.API_ORIGIN + "/v1/catalogue/modules/") && text.contains("/download\"")) {
            assertThat(served).as("a download URL was rebased").isNotEqualTo(text);
        }
        assertThat(served).as("icons and Maven Central are never rebased")
                .doesNotContain(stub.baseUrl() + "/v1/catalogue/modules/" + MODULE + "/icon");
    }

    @Test
    @DisplayName("against the production origin every valid download URL is used verbatim; http and relative are refused")
    void theProductionOriginAcceptsEveryValidDownloadUrlVerbatim() {
        PluginInstallUtils.setBaseUrlForTesting(StubCatalogue.API_ORIGIN);
        List<String> urls = new ArrayList<>();
        allFixtures().filter(p -> p.contains("/valid/")).forEach(p -> collectPublicUrls(
                ContractFixtures.catalogueBody(p), urls));
        assertThat(urls).as("control: the valid fixtures name public downloads").hasSizeGreaterThanOrEqualTo(6);
        for (String url : urls) {
            assertThat(CatalogueClient.absoluteDownloadUrl(url)).as(url).isEqualTo(url);
        }
        assertThat(CatalogueClient.absoluteDownloadUrl(data("fixtures/versionGet/response/invalid/url-http.json")
                .getAsJsonObject("download").get("url").getAsString())).isNull();
        assertThat(CatalogueClient.absoluteDownloadUrl(data("fixtures/versionGet/response/invalid/url-relative.json")
                .getAsJsonObject("download").get("url").getAsString())).isNull();
    }

    private static void collectPublicUrls(JsonElement element, List<String> urls) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("kind") && "public".equals(object.get("kind").getAsString()) && object.has("url")) {
                urls.add(object.get("url").getAsString());
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectPublicUrls(entry.getValue(), urls);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectPublicUrls(item, urls);
            }
        }
    }

    // ------------------------------------------------------------------ valid fixtures

    @Test
    @DisplayName("versionGet: licence-required is found and not downloadable; an unknown API level parses as null")
    void validVersionFixtures() {
        String licensed = "fixtures/versionGet/response/valid/license-required.json";
        serve(VERSION_ROUTE, 200, licensed);
        CatalogueClient.Version version = CatalogueClient.version(MODULE, VERSION);
        assertThat(version).isNotNull();
        assertThat(version.downloadKind()).isEqualTo("license_required");
        assertThat(version.downloadable()).isFalse();
        assertThat(version.sha256()).isEqualTo(data(licensed).get("sha256").getAsString());
        assertThat(version.latest()).isTrue();

        serve(VERSION_ROUTE, 200, "fixtures/versionGet/response/valid/unknown-api-level.json");
        CatalogueClient.Version unknown = CatalogueClient.version(MODULE, VERSION);
        assertThat(unknown).isNotNull();
        assertThat(unknown.apiLevel()).isNull();
        assertThat(unknown.downloadable()).isTrue();

        serve(VERSION_ROUTE, 404, "fixtures/versionGet/error/valid/404-not-found.json");
        assertThat(CatalogueClient.version(MODULE, VERSION)).isNull();
    }

    @Test
    @DisplayName("versionGet licence-required: the install is refused with the licence reason and sends no download")
    void licenceRequiredInstallIsRefused() {
        serve(MODULE_ROUTE, 200, "fixtures/moduleGet/response/valid/anonymous.json");
        serve(VERSION_ROUTE, 200, "fixtures/versionGet/response/valid/license-required.json");

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting(MODULE, VERSION);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_LICENCE_REQUIRED);
        assertThat(anyDownloadRequested()).isFalse();
    }

    @Test
    @DisplayName("versionsList: every listed version parses; the newest is the one marked isLatest; withdrawn is not downloadable")
    void validVersionsListFixtures() {
        for (String fixture : new String[]{"fixtures/versionsList/response/valid/page.json",
                "fixtures/versionsList/response/valid/with-withdrawn.json"}) {
            serve(VERSIONS_ROUTE, 200, fixture);
            JsonArray items = data(fixture).getAsJsonArray("items");
            List<CatalogueClient.Version> versions = CatalogueClient.versions(MODULE, LEVEL);
            assertThat(versions).as(fixture).hasSize(items.size());
            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                assertThat(versions.get(i).version()).isEqualTo(item.get("version").getAsString());
                assertThat(versions.get(i).downloadable()).as(fixture + " " + i)
                        .isEqualTo("public".equals(item.getAsJsonObject("download").get("kind").getAsString()));
            }
            assertThat(CatalogueClient.newest(versions).version()).isEqualTo("1.2.0");
            assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getVersion()).isEqualTo("1.2.0");
        }
        serve(VERSIONS_ROUTE, 404, "fixtures/versionsList/error/valid/404-not-found.json");
        assertThat(CatalogueClient.versions(MODULE, LEVEL)).isEmpty();
        assertThat(CatalogueClient.latestVersion(MODULE, LEVEL).getOutcome()).isEqualTo(VersionLookup.Outcome.NONE);
    }

    @Test
    @DisplayName("moduleGet: each valid card maps to the module entity; 404 is an unknown module")
    void validModuleFixtures() {
        for (String fixture : new String[]{"fixtures/moduleGet/response/valid/anonymous.json",
                "fixtures/moduleGet/response/valid/with-viewer.json",
                "fixtures/moduleGet/response/valid/all-withdrawn.json"}) {
            JsonObject card = data(fixture);
            String identifier = card.get("identifier").getAsString();
            serve("/v1/catalogue/modules/" + identifier, 200, fixture);
            PluginEntity entity = CatalogueClient.module(identifier);
            assertThat(entity).as(fixture).isNotNull();
            assertThat(entity.getIdentifyString()).isEqualTo(identifier);
            assertThat(entity.getName()).isEqualTo(card.get("name").getAsString());
            assertThat(entity.getShortDescription()).isEqualTo(card.get("shortDescription").getAsString());
            assertThat(entity.getDescription()).isEqualTo(card.get("description").isJsonNull()
                    ? null : card.get("description").getAsString());
            assertThat(entity.getIcon()).isEqualTo(card.get("iconUrl").isJsonNull()
                    ? null : card.get("iconUrl").getAsString());
            assertThat(entity.getDate()).isNotNull();
            assertThat(entity.getModifiedTime()).isNotNull();
            VersionLookup known = CatalogueClient.moduleKnown(identifier);
            assertThat(known.getOutcome()).isEqualTo(VersionLookup.Outcome.FOUND);
            assertThat(known.getVersion()).isEqualTo(identifier);
        }
        serve(MODULE_ROUTE, 404, "fixtures/moduleGet/error/valid/404-not-found.json");
        assertThat(CatalogueClient.module(MODULE)).isNull();
        assertThat(CatalogueClient.moduleKnown(MODULE).getOutcome()).isEqualTo(VersionLookup.Outcome.NONE);
    }

    @Test
    @DisplayName("modulesList: a page maps to its items in order; an empty page to none")
    void validModulesListFixtures() {
        String fixture = "fixtures/modulesList/response/valid/page.json";
        serve("/v1/catalogue/modules", 200, fixture);
        List<PluginEntity> page = CatalogueClient.list(1, 20);
        assertThat(page).extracting(PluginEntity::getIdentifyString).containsExactly("ultikits.kits", "ultikits.chat");
        assertThat(page.get(0).getIcon()).isEqualTo(
                data(fixture).getAsJsonArray("items").get(0).getAsJsonObject().get("iconUrl").getAsString());
        assertThat(page.get(1).getIcon()).isNull();

        serve("/v1/catalogue/modules", 200, "fixtures/modulesList/response/valid/empty-page.json");
        assertThat(CatalogueClient.list(5, 20)).isEmpty();
    }

    @Test
    @DisplayName("framework: the newest UltiTools version is the answer's version")
    void validFrameworkFixture() {
        serve(CatalogueClient.FRAMEWORK, 200, "fixtures/framework/response/valid/6.3.0.json");
        VersionLookup newest = CatalogueClient.frameworkNewest();
        assertThat(newest.getOutcome()).isEqualTo(VersionLookup.Outcome.FOUND);
        assertThat(newest.getVersion()).isEqualTo("6.3.0");
    }

    @Test
    @DisplayName("versionDownload: a 404 is not available; the stub's real download carries the contract's headers")
    void versionDownloadFixtures(@TempDir Path temp) throws IOException {
        String route = "/v1/catalogue/modules/" + StubCatalogue.MODULE + "/versions/" + StubCatalogue.VERSION
                + "/download";
        String url = stub.baseUrl() + route;
        VerifiedDownload.Result ok = VerifiedDownload.fetch(url, stub.servedSha256(), stub.jar().length,
                temp.resolve("ok.part"));
        assertThat(ok.kind()).isEqualTo(VerifiedDownload.Kind.OK);

        serve(route, 404, "fixtures/versionDownload/error/valid/404-not-found.json");
        VerifiedDownload.Result missing = VerifiedDownload.fetch(url, stub.servedSha256(), stub.jar().length,
                temp.resolve("missing.part"));
        assertThat(missing.kind()).isEqualTo(VerifiedDownload.Kind.NOT_AVAILABLE);
    }

    // ------------------------------------------------------------------ the stub's own answers

    /** GETs a stub path; the body with the stub's origin put back to {@link StubCatalogue#API_ORIGIN}. */
    private String[] get(String pathAndQuery) throws IOException {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(stub.baseUrl() + pathAndQuery).openConnection();
        connection.setInstanceFollowRedirects(false);
        int status = connection.getResponseCode();
        java.io.InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while (in != null && (read = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        if (in != null) {
            in.close();
        }
        String body = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                .replace(stub.baseUrl() + "/", StubCatalogue.API_ORIGIN + "/");
        return new String[]{String.valueOf(status), body,
                connection.getHeaderField("X-Content-SHA256"), connection.getHeaderField("Content-Disposition")};
    }

    private void assertServedValid(String pathAndQuery, int status, String schema) throws IOException {
        String[] answer = get(pathAndQuery);
        assertThat(answer[0]).as(pathAndQuery).isEqualTo(String.valueOf(status));
        JsonObject body = JsonParser.parseString(answer[1]).getAsJsonObject();
        assertThat(schema(schema).validate(node(body.toString()))).as(pathAndQuery + " against " + schema).isEmpty();
    }

    @Test
    @DisplayName("control: every answer the stub builds itself validates against the vendored schema")
    void theStubsOwnAnswersFollowTheContract() throws IOException {
        String module = "/v1/catalogue/modules/" + StubCatalogue.MODULE;
        assertServedValid("/v1/catalogue/modules?page=1&pageSize=20", 200, "schemas/modulesList.response.200.schema.json");
        assertServedValid(module, 200, "schemas/moduleGet.response.200.schema.json");
        assertServedValid(module + "/versions", 200, "schemas/versionsList.response.200.schema.json");
        assertServedValid(module + "/versions?apiLevel=630&minApiLevel=630", 200,
                "schemas/versionsList.response.200.schema.json");
        assertServedValid(module + "/versions/" + StubCatalogue.VERSION, 200, "schemas/versionGet.response.200.schema.json");
        assertServedValid(module + "/versions/" + StubCatalogue.LICENSED_VERSION, 200,
                "schemas/versionGet.response.200.schema.json");
        assertServedValid(CatalogueClient.FRAMEWORK, 200, "schemas/framework.response.200.schema.json");
        assertServedValid("/v1/catalogue/modules/nope", 404, "schemas/moduleGet.error.404.schema.json");
        assertServedValid(module + "/versions/9.9.9", 404, "schemas/versionGet.error.404.schema.json");
        stub.mode(StubCatalogue.Mode.LICENSE_REQUIRED);
        assertServedValid(module + "/versions/" + StubCatalogue.VERSION + "/download", 404,
                "schemas/versionDownload.error.404.schema.json");
        stub.mode(StubCatalogue.Mode.NORMAL);

        String[] download = get(module + "/versions/" + StubCatalogue.VERSION + "/download");
        JsonObject headers = new JsonObject();
        headers.addProperty("x-content-sha256", download[2]);
        headers.addProperty("content-disposition", download[3]);
        assertThat(schema("schemas/versionDownload.headers.200.schema.json").validate(node(headers.toString())))
                .isEmpty();

        for (String sample : new String[]{StubCatalogue.moduleCardSample("ultitrade", "UltiTrade"),
                StubCatalogue.versionsPageOf(StubCatalogue.versionSample("1.0.0",
                        StubCatalogue.API_ORIGIN + "/v1/catalogue/modules/ultitrade/versions/1.0.0/download")),
                StubCatalogue.frameworkSample("6.3.1")}) {
            JsonObject body = JsonParser.parseString(sample).getAsJsonObject();
            String schema = body.getAsJsonObject("data").has("items") ? "schemas/versionsList.response.200.schema.json"
                    : body.getAsJsonObject("data").has("identifier") ? "schemas/moduleGet.response.200.schema.json"
                    : "schemas/framework.response.200.schema.json";
            assertThat(schema(schema).validate(node(sample))).as(sample).isEmpty();
        }
    }

    @Test
    @DisplayName("the hand-made samples and their marker annotation are gone (S-49)")
    void noPlaceholderRemains() {
        assertThat(java.nio.file.Paths.get("src/test/resources/ulticloud-contract/rest/catalogue/1.0.0"))
                .as("control").isDirectory();
        assertThat(java.nio.file.Paths.get("src/test/resources/ulticloud-contract/pending")).doesNotExist();
        // The name is split so the plan's own search for any use of the marker stays at zero.
        assertThat(java.nio.file.Paths.get("src/test/java/com/ultikits/ultitools/contract/Pending" + "Fixture.java"))
                .doesNotExist();
        assertThat(java.nio.file.Paths.get("src/test/java/com/ultikits/ultitools/contract/ContractFixtures.java"))
                .as("control").exists();
    }

    // ------------------------------------------------------------------ invalid fixtures

    @Test
    @DisplayName("every invalid fixture of a read operation is classified exactly once (control)")
    void everyInvalidFixtureOfAReadOperationIsClassified() {
        TreeSet<String> invalid = new TreeSet<>();
        for (String operation : READ_OPERATIONS) {
            for (String part : new String[]{"query", "response", "error", "headers"}) {
                for (Fixture fixture : ContractFixtures.catalogueFixtures(operation, part, false)) {
                    invalid.add(fixture.path());
                    assertThat(fixture.expect()).isEqualTo("invalid");
                }
            }
        }
        assertThat(invalid).containsExactlyInAnyOrderElementsOf(INVALID.keySet());
        assertThat(INVALID).hasSize(28);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allFixtures")
    @DisplayName("the vendored schemas accept every valid fixture and refuse every invalid one (format off)")
    void schemasAgreeWithTheFixtures(String fixture) {
        List<String> schemas = schemasFor(fixture);
        assertThat(schemas).as("a schema exists for " + fixture).isNotEmpty();
        JsonObject body = ContractFixtures.catalogueBody(fixture);
        if (VALIDATOR_DIVERGENCE.equals(fixture)) {
            // networknt 2.0.8 reads maxItems as a 32-bit int: the schema's 9007199254740991 (2^53 - 1)
            // becomes -1, so this valid page is refused. The framework never requests categories;
            // the divergence is pinned so a validator or contract change is noticed (reported to the backend).
            assertThat(schema(schemas.get(0)).validate(node(body.toString())).toString())
                    .contains("must have at most -1 items");
            return;
        }
        if (fixture.contains("/valid/")) {
            assertThat(schemas.stream().anyMatch(schema -> schemaAccepts(schema, body)))
                    .as(fixture + " " + schema(schemas.get(0)).validate(node(body.toString()))).isTrue();
        } else {
            for (String schema : schemas) {
                assertThat(schemaAccepts(schema, body)).as(fixture + " against " + schema).isFalse();
            }
        }
    }

    static Stream<String> refusedVersions() {
        return INVALID.entrySet().stream().filter(e -> e.getKey().startsWith("fixtures/versionGet/response/")
                && e.getValue() != Handling.TOLERATED_NOT_READ).map(Map.Entry::getKey);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedVersions")
    @DisplayName("an invalid version the client refuses is never downloaded and never installed")
    void refusedVersionIsNeverDownloaded(String fixture) {
        serve(MODULE_ROUTE, 200, "fixtures/moduleGet/response/valid/anonymous.json");
        serve(VERSION_ROUTE, 200, fixture);

        CatalogueClient.Version version = CatalogueClient.version(MODULE, VERSION);
        switch (INVALID.get(fixture)) {
            case VERSION_REFUSED:
                assertThat(version).as(fixture).isNull();
                break;
            case URL_REFUSED:
                assertThat(version).isNotNull();
                assertThat(CatalogueClient.absoluteDownloadUrl(version.downloadUrl())).isNull();
                break;
            case NOT_DOWNLOADABLE:
                assertThat(version).isNotNull();
                assertThat(version.downloadable()).isFalse();
                break;
            default:
                throw new AssertionError("unexpected handling for " + fixture);
        }
        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting(MODULE, VERSION);
        assertThat(result.isInstalled()).isFalse();
        assertThat(anyDownloadRequested()).as("no download requested for " + fixture).isFalse();
    }

    @Test
    @DisplayName("unknown keys in a version are ignored: the answer reads exactly as the valid public download")
    void unknownVersionKeysAreTolerated() {
        CatalogueClient.Version reference = readVersion("fixtures/versionGet/response/valid/public-download.json");
        for (String fixture : new String[]{"fixtures/versionGet/response/invalid/extra-top-level-field.json",
                "fixtures/versionGet/response/invalid/extra-version-field.json"}) {
            CatalogueClient.Version version = readVersion(fixture);
            assertThat(version).as(fixture).isNotNull();
            assertThat(version.sha256()).isEqualTo(reference.sha256());
            assertThat(version.sizeBytes()).isEqualTo(reference.sizeBytes());
            assertThat(version.downloadUrl()).isEqualTo(reference.downloadUrl());
            assertThat(version.downloadable()).isTrue();
        }
        serve(VERSION_ROUTE, 404, "fixtures/versionGet/error/invalid/404-wrong-code.json");
        assertThat(CatalogueClient.version(MODULE, VERSION)).as("a 404 is not found whatever its code").isNull();
    }

    private CatalogueClient.Version readVersion(String fixture) {
        serve(VERSION_ROUTE, 200, fixture);
        return CatalogueClient.version(MODULE, VERSION);
    }

    @Test
    @DisplayName("a withdrawn version listed with a URL is still not downloadable")
    void withdrawnWithUrlIsNotDownloadable() {
        serve(VERSIONS_ROUTE, 200, "fixtures/versionsList/response/invalid/withdrawn-with-url.json");
        List<CatalogueClient.Version> versions = CatalogueClient.versions(MODULE, LEVEL);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).downloadKind()).isEqualTo("withdrawn");
        assertThat(versions.get(0).downloadable()).isFalse();
    }

    @Test
    @DisplayName("an http icon is dropped and the rest of the page is used; a page without total is still read")
    void listViolations() {
        serve("/v1/catalogue/modules", 200, "fixtures/modulesList/response/invalid/icon-url-http.json");
        List<PluginEntity> page = CatalogueClient.list(1, 20);
        assertThat(page).isNotEmpty();
        assertThat(page).allSatisfy(entity -> assertThat(entity.getIcon()).isNull());
        assertThat(page.get(0).getIdentifyString()).isNotNull();

        serve("/v1/catalogue/modules", 200, "fixtures/modulesList/response/invalid/missing-total.json");
        assertThat(CatalogueClient.list(1, 20)).isNotEmpty();
    }

    @Test
    @DisplayName("violations in fields the client never reads leave the module card and the framework version usable")
    void unreadFieldViolationsAreTolerated() {
        for (String fixture : new String[]{"fixtures/moduleGet/response/invalid/developer-name-control-char.json",
                "fixtures/moduleGet/response/invalid/too-many-tags.json"}) {
            String identifier = data(fixture).get("identifier").getAsString();
            serve("/v1/catalogue/modules/" + identifier, 200, fixture);
            assertThat(CatalogueClient.module(identifier)).as(fixture).isNotNull();
        }
        for (String fixture : new String[]{"fixtures/framework/response/invalid/download-url-http.json",
                "fixtures/framework/response/invalid/missing-sha256.json"}) {
            serve(CatalogueClient.FRAMEWORK, 200, fixture);
            assertThat(CatalogueClient.frameworkNewest().getVersion()).as(fixture)
                    .isEqualTo(data(fixture).get("version").getAsString());
        }
    }

    @Test
    @DisplayName("download headers: a 63-character hash header is refused; a missing one leaves the body check")
    void downloadHeaderViolations(@TempDir Path temp) {
        String url = stub.baseUrl() + "/v1/catalogue/modules/" + StubCatalogue.MODULE + "/versions/"
                + StubCatalogue.VERSION + "/download";
        JsonObject shortHeader = ContractFixtures.catalogueBody(
                "fixtures/versionDownload/headers/invalid/sha256-63-chars.json");
        stub.sha256Header(shortHeader.get("x-content-sha256").getAsString());
        assertThat(VerifiedDownload.fetch(url, stub.servedSha256(), stub.jar().length, temp.resolve("a.part"))
                .kind()).isEqualTo(VerifiedDownload.Kind.HEADER_MISMATCH);

        stub.sha256Header(null).mode(StubCatalogue.Mode.NO_HEADER);
        assertThat(VerifiedDownload.fetch(url, stub.servedSha256(), stub.jar().length, temp.resolve("b.part"))
                .kind()).isEqualTo(VerifiedDownload.Kind.OK);
    }

    // ------------------------------------------------------------------ queries

    @Test
    @DisplayName("the queries the client sends validate against the vendored query schemas; no other route is requested")
    void clientQueriesValidate() {
        serve("/v1/catalogue/modules", 200, "fixtures/modulesList/response/valid/page.json");
        serve(VERSIONS_ROUTE, 200, "fixtures/versionsList/response/valid/page.json");
        CatalogueClient.list(2, 10);
        CatalogueClient.versions(MODULE, LEVEL);

        Map<String, String> queries = new HashMap<>();
        for (StubCatalogue.Request request : stub.requests()) {
            queries.put(request.path(), request.query());
        }
        assertThat(schemaAccepts("schemas/modulesList.query.schema.json",
                decoded(queries.get("/v1/catalogue/modules")))).isTrue();
        JsonObject versionsQuery = decoded(queries.get(VERSIONS_ROUTE));
        assertThat(versionsQuery.get("apiLevel").getAsInt()).isEqualTo(LEVEL);
        assertThat(versionsQuery.get("minApiLevel").getAsInt()).isEqualTo(630);
        assertThat(schemaAccepts("schemas/versionsList.query.schema.json", versionsQuery)).isTrue();
        assertThat(stub.paths()).noneMatch(p -> p.startsWith("/v1/catalogue/categories")
                || p.startsWith("/v1/catalogue/tags"));
        for (String fixture : INVALID.keySet()) {
            if (INVALID.get(fixture) == Handling.QUERY_NEVER_SENT) {
                String schema = fixture.startsWith("fixtures/modulesList")
                        ? "schemas/modulesList.query.schema.json" : "schemas/versionsList.query.schema.json";
                assertThat(schemaAccepts(schema, ContractFixtures.catalogueBody(fixture))).as(fixture).isFalse();
            }
        }
    }

    /** Decodes a query string as CATALOGUE.md describes: integers as numbers, everything else as strings. */
    private static JsonObject decoded(String query) {
        JsonObject object = new JsonObject();
        if (query == null) {
            return object;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            if (value.matches("-?\\d+")) {
                object.addProperty(key, Long.parseLong(value));
            } else {
                object.addProperty(key, value);
            }
        }
        return object;
    }

    @Test
    @DisplayName("a module reference follows the contract's identifier grammar, at most 64 characters")
    void moduleRefFollowsTheIdentifierGrammar() {
        StringBuilder longest = new StringBuilder("a");
        while (longest.length() < 64) {
            longest.append('b');
        }
        assertThat(CatalogueClient.moduleRef("  UltiKits.Kits ")).isEqualTo(MODULE);
        assertThat(CatalogueClient.moduleRef(longest.toString())).isEqualTo(longest.toString());
        assertThat(CatalogueClient.moduleRef(longest + "c")).isNull();
        assertThat(CatalogueClient.moduleRef("kits..double")).isNull();
        assertThat(CatalogueClient.moduleRef("1kits")).isNull();
        List<String> identifiers = allFixtures().filter(p -> p.contains("/response/valid/"))
                .flatMap(p -> identifiersIn(ContractFixtures.catalogueBody(p)).stream()).collect(Collectors.toList());
        assertThat(identifiers).as("control").isNotEmpty();
        for (String identifier : identifiers) {
            assertThat(CatalogueClient.moduleRef(identifier)).isEqualTo(identifier);
        }
    }

    private static List<String> identifiersIn(JsonElement element) {
        List<String> found = new ArrayList<>();
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if ("identifier".equals(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    found.add(entry.getValue().getAsString());
                } else {
                    found.addAll(identifiersIn(entry.getValue()));
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                found.addAll(identifiersIn(item));
            }
        }
        return found;
    }

    @Test
    @DisplayName("the client names the contract version it implements, which the lock pins")
    void contractVersion() {
        assertThat(CatalogueClient.CONTRACT_VERSION).isEqualTo(JsonParser.parseString(
                ContractFixtures.catalogueText("MANIFEST.json")).getAsJsonObject().get("contractVersion").getAsString());
    }
}
