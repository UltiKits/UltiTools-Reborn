package com.ultikits.ultitools.stub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local catalogue server the catalogue tests drive (plan 22-18), reusable by the UAT stub of
 * plan 22-19.
 *
 * <p>It answers the {@code /v1/catalogue/*} routes with bodies built from the frozen catalogue REST
 * contract 1.0.0 vendored in {@code src/test/resources/ulticloud-contract/rest/catalogue/1.0.0/}
 * (plan 22-26): every body is a vendored valid fixture with only the scenario's values put in --
 * the module {@link #MODULE}, its versions {@link #VERSION} and {@link #LICENSED_VERSION}, their
 * API levels, the real {@code sizeBytes} and {@code sha256} of the JAR it serves, and download
 * URLs on this stub instead of {@link #API_ORIGIN}. It serves a module JAR it generates, so the
 * metadata it reports ({@code sizeBytes}, {@code sha256}, {@code X-Content-SHA256}) is always the
 * truth about the bytes it actually serves. {@link Mode} perturbs exactly one of those three, so a
 * test can prove which check caught the difference. {@link #serveVendored} answers a path with a
 * vendored fixture exactly as exported.
 *
 * <p>Every request is recorded in {@link #requests()} with its path, query and {@code
 * Authorization} header; the v1 {@code /plugin/*} routes answer 410 and are recorded the same way,
 * so a test can prove that no v1 catalogue request is sent any more.
 *
 * @since 6.3.1
 */
public final class StubCatalogue implements AutoCloseable {

    /** How the served JAR, its metadata and its response header disagree. */
    public enum Mode {
        /** Header, metadata and body agree. */
        NORMAL,
        /** One byte of the body is changed; metadata and header keep the real hash. */
        TAMPER_HASH,
        /** The metadata claims one byte more than the body carries. */
        TAMPER_SIZE,
        /** The body and the metadata agree; the response header names another hash. */
        WRONG_HEADER,
        /** Every version answers {@code download.kind = license_required}. */
        LICENSE_REQUIRED,
        /** The download route answers 302 and the redirect target serves the JAR. */
        REDIRECT,
        /** The download route answers 403. */
        FORBIDDEN,
        /** The version names a download on another host; the stub's own download route is never reached. */
        FOREIGN_URL,
        /** The download route answers 500. */
        SERVER_ERROR,
        /** The body is {@value #HUGE_BODY_BYTES} bytes while the metadata names {@value #HUGE_DECLARED_BYTES}. */
        HUGE_BODY,
        /** The download route announces the body but closes the connection after a few bytes. */
        BROKEN_STREAM,
        /** The download answers no {@code X-Content-SHA256} header at all. */
        NO_HEADER,
        /** The download answers the hash in upper-case hex. */
        UPPER_HEADER,
        /** The level-filtered version list is empty: versions exist, none this framework installs. */
        NO_COMPATIBLE_VERSION
    }

    /** The module identifier every generated route uses. */
    public static final String MODULE = "demo";
    /** The module's name on its card. */
    public static final String MODULE_NAME = "Demo";
    /** The newest compatible version, {@code apiLevel} 630, publicly downloadable. */
    public static final String VERSION = "1.1.0";
    /** The version marked {@code license_required}, {@code apiLevel} 640. */
    public static final String LICENSED_VERSION = "2.0.0";

    /**
     * The API origin the vendored catalogue fixtures name in their URLs. The stub answers on a
     * loopback address instead, so {@link #rebased(String)} moves exactly the download URLs at this
     * origin onto {@link #baseUrl()}.
     */
    public static final String API_ORIGIN = "https://api.ultikits.com";
    /** A download URL at {@link #API_ORIGIN} inside a JSON string; icons and other hosts never match. */
    private static final Pattern VENDORED_DOWNLOAD = Pattern.compile(
            Pattern.quote(API_ORIGIN) + "(/v1/catalogue/modules/[^\"?#]*/download)\"");
    private static final String CONTRACT = "ulticloud-contract/rest/catalogue/1.0.0/";
    private static final String PUBLIC_VERSION_FIXTURE = "fixtures/versionGet/response/valid/public-download.json";
    private static final String LICENSED_VERSION_FIXTURE = "fixtures/versionGet/response/valid/license-required.json";
    private static final String MODULE_FIXTURE = "fixtures/moduleGet/response/valid/anonymous.json";
    private static final String MODULES_PAGE_FIXTURE = "fixtures/modulesList/response/valid/page.json";
    private static final String VERSIONS_PAGE_FIXTURE = "fixtures/versionsList/response/valid/page.json";
    private static final String FRAMEWORK_FIXTURE = "fixtures/framework/response/valid/6.3.0.json";
    private static final String MODULE_NOT_FOUND = "fixtures/moduleGet/error/valid/404-not-found.json";
    private static final String VERSION_NOT_FOUND = "fixtures/versionGet/error/valid/404-not-found.json";
    private static final String DOWNLOAD_NOT_FOUND = "fixtures/versionDownload/error/valid/404-not-found.json";
    /** The framework version the vendored framework answer names. */
    private static final String VENDORED_FRAMEWORK_VERSION = "6.3.0";
    private static final String VERSION_PATH = "/v1/catalogue/modules/" + MODULE + "/versions/" + VERSION;
    private static final String HASH_OF_ONE_MIB = repeat('d', 64);
    /** A download URL on a host that is not the catalogue's, as a compromised answer would name it. */
    private static final String FOREIGN_DOWNLOAD = "https://evil.example/module.jar";
    /**
     * The byte count the {@link Mode#HUGE_BODY} answer announces.
     *
     * <p>It must exceed what the loopback socket buffers can hold, or the stub can finish writing
     * a body the client never read: with Linux's default maxima (4 MiB send, 6 MiB receive) a
     * 10 MiB body was written in full in 6 of 500 runs under CPU load, so neither
     * {@link #clientAborted()} nor {@link #servedBytes()} could tell a client that stopped early
     * from one that read everything. 64 MiB leaves a wide margin above those buffers.
     */
    public static final int HUGE_BODY_BYTES = 64 * 1024 * 1024;
    /** The byte count the {@link Mode#HUGE_BODY} metadata claims. */
    public static final int HUGE_DECLARED_BYTES = 1024 * 1024;
    private static final int BROKEN_STREAM_CHUNK = 1024;
    private static final int CHUNK = 8192;
    /** One supplementary character: four bytes in UTF-8, the widest one character can be. */
    private static final String WIDE_CHARACTER = "😀";

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.NORMAL;
    private volatile byte[] jar = moduleJar(MODULE_NAME, VERSION, MODULE, "630");
    private volatile int servedBytes;
    private volatile boolean clientAborted;
    private final CountDownLatch clientAbortSeen = new CountDownLatch(1);
    private volatile int longDescriptionChars;
    private volatile int versionCount;
    private volatile int changelogChars;
    private volatile String sha256HeaderOverride;
    private volatile String downloadPathOverride;
    private volatile String downloadOrigin;
    private final Map<String, Vendored> vendored = new ConcurrentHashMap<>();

    private StubCatalogue(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a stub on a free loopback port, serving the vendored catalogue shapes and a module JAR declaring
     * {@code api-version 630}.
     *
     * @return the running stub
     * @throws IOException when the loopback server cannot be started
     */
    public static StubCatalogue start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        StubCatalogue stub = new StubCatalogue(server);
        server.createContext("/", stub::answer);
        server.start();
        return stub;
    }

    /** @return the base URL a test points {@code PluginInstallUtils} at */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Names the origin the download URLs this stub hands out point at, for a catalogue reached
     * through a relay on another port (the UAT stub's front door, plan 22-28). A framework accepts a
     * download only on the origin it is configured with, so without this every download URL would
     * name this server's own port and be refused as foreign. Until it is called, download URLs name
     * {@link #baseUrl()}.
     *
     * @param origin the public origin, such as {@code http://127.0.0.1:18080}, without a trailing slash
     * @return this stub
     */
    public StubCatalogue advertiseDownloadsAt(String origin) {
        this.downloadOrigin = origin;
        return this;
    }

    /** @return the origin download URLs name: the advertised one, or {@link #baseUrl()} */
    private String downloadOrigin() {
        String origin = downloadOrigin;
        return origin == null ? baseUrl() : origin;
    }

    /**
     * @param mode how the next download disagrees with its metadata
     * @return this stub
     */
    public StubCatalogue mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    /** @return the mode in effect */
    public Mode mode() {
        return mode;
    }

    /**
     * Makes the module card carry a description of the given length, in four-byte characters.
     *
     * <p>The catalogue contract lets a module's {@code description} be 50,000 characters long, so a
     * card can be far larger than a 64 KiB answer. {@code 0} restores the vendored card's description.
     *
     * @param characters the number of characters, each four bytes in UTF-8
     * @return this stub
     */
    public StubCatalogue longDescription(int characters) {
        this.longDescriptionChars = characters;
        return this;
    }

    /**
     * Makes the versions page carry the given number of versions, each with a changelog of the
     * given length in four-byte characters.
     *
     * <p>The contract caps a page at 100 versions and a changelog at 5,000 characters. The first
     * version is {@link #VERSION} and the one marked {@code isLatest}; the others are {@code
     * 1.0.<i>}. {@code 0} restores the stub's own page.
     *
     * @param versions       how many versions the page carries
     * @param changelogChars the changelog length of each, in characters (four bytes each)
     * @return this stub
     */
    public StubCatalogue versionChangelogs(int versions, int changelogChars) {
        this.versionCount = versions;
        this.changelogChars = changelogChars;
        return this;
    }

    /**
     * Replaces the JAR served for {@link #VERSION}; its real hash and byte count are what the
     * metadata and the response header report.
     *
     * @param jar the bytes to serve
     * @return this stub
     */
    public StubCatalogue jar(byte[] jar) {
        this.jar = jar.clone();
        return this;
    }

    /**
     * Makes {@link #VERSION}'s public {@code download.url} name another path (and optionally a query)
     * on this stub, and serves the download there; the usual download route keeps working.
     *
     * <p>The catalogue contract says download URLs are opaque and their shape is descriptive only, so
     * a backend may hand out, say, {@code /v1/catalogue/downloads/<token>} or add a {@code ?sig=}
     * query inside 1.0.0. {@code null} restores the usual shape.
     *
     * @param pathAndQuery the raw path, starting with {@code /}, optionally followed by {@code ?query}
     * @return this stub
     */
    public StubCatalogue downloadPath(String pathAndQuery) {
        this.downloadPathOverride = pathAndQuery;
        return this;
    }

    /**
     * Answers {@code path} (any query) with a vendored catalogue body instead of the stub's own,
     * byte for byte except that each download URL at {@link #API_ORIGIN} is rebased onto this stub
     * (see {@link #rebased(String)}), so a client pointed at the stub reaches the stub's download.
     *
     * @param path   the raw request path to answer
     * @param status the HTTP status
     * @param body   the vendored body, as the contract export holds it
     * @return this stub
     */
    public StubCatalogue serveVendored(String path, int status, String body) {
        vendored.put(path, new Vendored(status, rebased(body)));
        return this;
    }

    /**
     * Moves every download URL at {@link #API_ORIGIN} onto the download origin ({@link #baseUrl()}
     * unless {@link #advertiseDownloadsAt(String)} named another), and changes nothing
     * else: icon URLs, the Maven Central framework URL and URLs on any other host stay as written.
     *
     * @param vendoredBody a body as the contract export holds it
     * @return the body this stub serves for it
     */
    public String rebased(String vendoredBody) {
        Matcher matcher = VENDORED_DOWNLOAD.matcher(vendoredBody);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(downloadOrigin() + matcher.group(1) + "\""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Makes the download send this {@code X-Content-SHA256} value instead of the hash the mode
     * implies; {@code null} restores the mode's header.
     *
     * @param value the header value to send, verbatim
     * @return this stub
     */
    public StubCatalogue sha256Header(String value) {
        this.sha256HeaderOverride = value;
        return this;
    }

    /** @return the bytes served for {@link #VERSION} */
    public byte[] jar() {
        return jar.clone();
    }

    /** @return every request the stub received, oldest first */
    public List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    /** @return the paths of every request the stub received, oldest first */
    public List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (Request request : requests()) {
            paths.add(request.path());
        }
        return paths;
    }

    /** @return the response-body bytes the download route managed to write, for the abort checks */
    public int servedBytes() {
        return servedBytes;
    }

    /** @return whether a client closed a download before the stub finished writing it */
    public boolean clientAborted() {
        return clientAborted;
    }

    /**
     * Waits until the stub has seen a client close a download early.
     *
     * <p>The stub learns of the close on its own writer thread, when its next write fails, which can
     * be after the client's call has already returned; asserting {@link #clientAborted()} at once
     * races that write. After this returns {@code true}, {@link #servedBytes()} is final.
     *
     * @param timeout the longest wait
     * @param unit    the unit of {@code timeout}
     * @return whether the abort was seen within the wait
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public boolean awaitClientAborted(long timeout, TimeUnit unit) throws InterruptedException {
        return clientAbortSeen.await(timeout, unit);
    }

    private void markClientAborted() {
        clientAborted = true;
        clientAbortSeen.countDown();
    }

    /** @return the {@code sha256} the stub reports for the bytes it serves */
    public String servedSha256() {
        return sha256(jar);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ routes

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(new Request(exchange.getRequestMethod(), path, query,
                exchange.getRequestHeaders().getFirst("Authorization")));
        try {
            Vendored answer = vendored.get(path);
            String override = downloadPathOverride;
            if (answer != null) {
                send(exchange, answer.status, answer.body);
            } else if (override != null && path.equals(override.split("\\?", 2)[0])) {
                download(exchange);
            } else {
                route(exchange, path, query);
            }
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path, String query) throws IOException {
        String modules = "/v1/catalogue/modules";
        if (path.startsWith("/plugin/")) {
            send(exchange, 410, "{\"error\":{\"code\":\"gone\",\"message\":\"v1 catalogue is gone\"}}");
        } else if (modules.equals(path)) {
            send(exchange, 200, modulesPage());
        } else if ((modules + "/" + MODULE).equals(path)) {
            send(exchange, 200, moduleBody());
        } else if ((modules + "/" + MODULE + "/versions").equals(path)) {
            send(exchange, 200, versionsPage(query));
        } else if (path.startsWith(modules + "/" + MODULE + "/versions/")) {
            versionRoute(exchange, path.substring((modules + "/" + MODULE + "/versions/").length()));
        } else if ("/v1/catalogue/framework".equals(path)) {
            send(exchange, 200, fixture(FRAMEWORK_FIXTURE));
        } else {
            send(exchange, 404, fixture(MODULE_NOT_FOUND));
        }
    }

    private void versionRoute(HttpExchange exchange, String tail) throws IOException {
        if (VERSION.equals(tail)) {
            send(exchange, 200, versionBody());
        } else if (LICENSED_VERSION.equals(tail)) {
            send(exchange, 200, envelope(licensedVersion()));
        } else if ((VERSION + "/download").equals(tail)) {
            download(exchange);
        } else if (("redirect-" + VERSION + "/download").equals(tail)) {
            // Only reachable when a client follows the redirect the REDIRECT mode answers.
            stream(exchange, jar, headerFor(jar));
        } else {
            send(exchange, 404, fixture(VERSION_NOT_FOUND));
        }
    }

    private void download(HttpExchange exchange) throws IOException {
        switch (mode) {
            case REDIRECT:
                exchange.getResponseHeaders().add("Location",
                        "/v1/catalogue/modules/" + MODULE + "/versions/redirect-" + VERSION + "/download");
                send(exchange, 302, "");
                return;
            case FORBIDDEN:
                send(exchange, 403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"not available\"}}");
                return;
            case SERVER_ERROR:
                send(exchange, 500, "{\"error\":{\"code\":\"internal_error\",\"message\":\"down\"}}");
                return;
            case LICENSE_REQUIRED:
                send(exchange, 404, fixture(DOWNLOAD_NOT_FOUND));
                return;
            case HUGE_BODY:
                hugeBody(exchange);
                return;
            case BROKEN_STREAM:
                brokenStream(exchange);
                return;
            default:
                stream(exchange, servedBody(), mode == Mode.NO_HEADER ? null : headerFor(jar));
        }
    }

    private byte[] servedBody() {
        byte[] body = jar.clone();
        if (mode == Mode.TAMPER_HASH && body.length > 0) {
            body[body.length - 1] = (byte) (body[body.length - 1] ^ 0xFF);
        }
        return body;
    }

    private String headerFor(byte[] body) {
        String override = sha256HeaderOverride;
        if (override != null) {
            return override;
        }
        if (mode == Mode.WRONG_HEADER) {
            return repeat('e', 64);
        }
        String hash = sha256(body);
        return mode == Mode.UPPER_HEADER ? hash.toUpperCase(Locale.ROOT) : hash;
    }

    private void stream(HttpExchange exchange, byte[] body, String sha256Header) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
        exchange.getResponseHeaders().add("Content-Disposition", contentDisposition());
        if (sha256Header != null) {
            exchange.getResponseHeaders().add("X-Content-SHA256", sha256Header);
        }
        exchange.sendResponseHeaders(200, body.length);
        OutputStream out = exchange.getResponseBody();
        int written = 0;
        try {
            while (written < body.length) {
                int length = Math.min(CHUNK, body.length - written);
                out.write(body, written, length);
                written += length;
                servedBytes = written;
            }
            out.flush();
        } catch (IOException closedByClient) {
            // The client aborted the download on purpose (an oversized or tampered body).
            markClientAborted();
        }
    }

    private void hugeBody(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
        exchange.getResponseHeaders().add("X-Content-SHA256", HASH_OF_ONE_MIB);
        exchange.sendResponseHeaders(200, HUGE_BODY_BYTES);
        byte[] chunk = new byte[CHUNK];
        OutputStream out = exchange.getResponseBody();
        int written = 0;
        try {
            while (written < HUGE_BODY_BYTES) {
                int length = Math.min(CHUNK, HUGE_BODY_BYTES - written);
                out.write(chunk, 0, length);
                written += length;
                servedBytes = written;
            }
            out.flush();
        } catch (IOException closedByClient) {
            // The client stopped after the declared size, which is what the test proves.
            markClientAborted();
        }
    }

    private void brokenStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
        exchange.sendResponseHeaders(200, jar.length);
        OutputStream out = exchange.getResponseBody();
        int length = Math.max(1, Math.min(BROKEN_STREAM_CHUNK, jar.length / 4));
        out.write(jar, 0, length);
        out.flush();
        servedBytes = length;
        // Closing without sending the announced length makes the client's read fail.
        exchange.close();
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.flush();
    }

    // ------------------------------------------------------------------ bodies

    /** @return the {@code Content-Disposition} the contract's download headers require */
    private static String contentDisposition() {
        return "attachment; filename=\"" + MODULE_NAME + "-" + VERSION + ".jar\"";
    }

    private String versionBody() {
        return envelope(publicVersion());
    }

    /** {@link #VERSION} as the version route and the versions page carry it, with the mode applied. */
    private JsonObject publicVersion() {
        String override = downloadPathOverride;
        JsonObject data = versionObject(PUBLIC_VERSION_FIXTURE, VERSION, true, 630,
                override == null ? downloadUrl(downloadOrigin(), MODULE, VERSION) : downloadOrigin() + override);
        if (mode == Mode.LICENSE_REQUIRED) {
            JsonObject download = new JsonObject();
            download.addProperty("kind", "license_required");
            data.add("download", download);
        }
        if (mode == Mode.FOREIGN_URL) {
            data.getAsJsonObject("download").addProperty("url", FOREIGN_DOWNLOAD);
        }
        if (mode == Mode.HUGE_BODY) {
            data.addProperty("sizeBytes", HUGE_DECLARED_BYTES);
            data.addProperty("sha256", HASH_OF_ONE_MIB);
        } else {
            data.addProperty("sizeBytes", (long) jar.length + (mode == Mode.TAMPER_SIZE ? 1 : 0));
            data.addProperty("sha256", sha256(jar));
        }
        return data;
    }

    /** {@link #LICENSED_VERSION}: the vendored licence-required version, re-addressed. */
    private static JsonObject licensedVersion() {
        return versionObject(LICENSED_VERSION_FIXTURE, LICENSED_VERSION, false, 640, null);
    }

    private String moduleBody() {
        JsonObject root = JsonParser.parseString(moduleCard(MODULE, MODULE_NAME)).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        data.add("latest", publicVersion());
        if (longDescriptionChars > 0) {
            data.addProperty("description", repeat(WIDE_CHARACTER, longDescriptionChars));
        }
        return root.toString();
    }

    /** The vendored list page whose first module is {@link #MODULE}. */
    private static String modulesPage() {
        JsonObject root = JsonParser.parseString(fixture(MODULES_PAGE_FIXTURE)).getAsJsonObject();
        JsonObject first = root.getAsJsonObject("data").getAsJsonArray("items").get(0).getAsJsonObject();
        first.addProperty("identifier", MODULE);
        first.addProperty("name", MODULE_NAME);
        first.addProperty("latestVersion", VERSION);
        first.addProperty("iconUrl", API_ORIGIN + "/v1/catalogue/modules/" + MODULE + "/icon");
        return root.toString();
    }

    private String versionsPage(String query) {
        JsonObject root = JsonParser.parseString(fixture(VERSIONS_PAGE_FIXTURE)).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        JsonArray items = new JsonArray();
        if (mode == Mode.NO_COMPATIBLE_VERSION) {
            data.add("items", items);
            data.addProperty("total", 0);
            return root.toString();
        }
        if (versionCount > 0) {
            JsonArray longPage = longVersionsPageItems();
            data.add("items", longPage);
            data.addProperty("total", longPage.size());
            return root.toString();
        }
        Integer apiLevel = integerParameter(query, "apiLevel");
        Integer minApiLevel = integerParameter(query, "minApiLevel");
        for (JsonObject version : new JsonObject[]{publicVersion(), licensedVersion()}) {
            if (within(version, apiLevel, minApiLevel)) {
                items.add(version);
            }
        }
        data.add("items", items);
        data.addProperty("total", items.size());
        return root.toString();
    }

    private JsonArray longVersionsPageItems() {
        JsonArray items = new JsonArray();
        for (int i = 0; i < versionCount; i++) {
            String version = i == 0 ? VERSION : "1.0." + i;
            JsonObject item = versionObject(PUBLIC_VERSION_FIXTURE, version, i == 0, 630,
                    downloadUrl(downloadOrigin(), MODULE, version));
            item.addProperty("changelog", repeat(WIDE_CHARACTER, changelogChars));
            item.addProperty("sizeBytes", (long) jar.length);
            item.addProperty("sha256", sha256(jar));
            items.add(item);
        }
        return items;
    }

    /**
     * A vendored version object with the scenario's values put in.
     *
     * @param fixture     the vendored fixture whose {@code data} is the template
     * @param version     the {@code version}
     * @param latest      the {@code isLatest}
     * @param apiLevel    the {@code apiLevel}
     * @param downloadUrl the public {@code download.url}, or {@code null} to keep the template's download
     */
    private static JsonObject versionObject(String fixture, String version, boolean latest, int apiLevel,
                                            String downloadUrl) {
        JsonObject data = JsonParser.parseString(fixture(fixture)).getAsJsonObject().getAsJsonObject("data");
        data.addProperty("version", version);
        data.addProperty("isLatest", latest);
        data.addProperty("apiLevel", apiLevel);
        if (downloadUrl != null) {
            data.getAsJsonObject("download").addProperty("url", downloadUrl);
        }
        return data;
    }

    private static String downloadUrl(String origin, String module, String version) {
        return origin + "/v1/catalogue/modules/" + module + "/versions/" + version + "/download";
    }

    private static String envelope(JsonObject data) {
        JsonObject root = new JsonObject();
        root.add("data", data);
        return root.toString();
    }

    private static boolean within(JsonObject version, Integer apiLevel, Integer minApiLevel) {
        if (apiLevel == null && minApiLevel == null) {
            return true;
        }
        JsonElement level = version.get("apiLevel");
        if (level == null || level.isJsonNull()) {
            return false;
        }
        int value = level.getAsInt();
        return (apiLevel == null || value <= apiLevel) && (minApiLevel == null || value >= minApiLevel);
    }

    private static Integer integerParameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equals(key)) {
                try {
                    return Integer.valueOf(equals < 0 ? "" : pair.substring(equals + 1));
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * A vendored module card addressed to another module: its {@code identifier}, {@code name}, and the
     * identifier in its {@code iconUrl} and latest version's download URL.
     *
     * <p>Tests that stand in for the catalogue build their answers from the vendored fixtures rather
     * than from hand-written JSON, so the shapes have one source: the frozen contract.
     *
     * @param identifier the {@code identifier}
     * @param name       the {@code name}
     * @return the whole answer body
     */
    public static String moduleCardSample(String identifier, String name) {
        return moduleCard(identifier, name);
    }

    private static String moduleCard(String identifier, String name) {
        JsonObject root = JsonParser.parseString(fixture(MODULE_FIXTURE)).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        data.addProperty("identifier", identifier);
        data.addProperty("name", name);
        data.addProperty("iconUrl", API_ORIGIN + "/v1/catalogue/modules/" + identifier + "/icon");
        JsonObject latest = data.getAsJsonObject("latest");
        latest.getAsJsonObject("download").addProperty("url",
                downloadUrl(API_ORIGIN, identifier, latest.get("version").getAsString()));
        return root.toString();
    }

    /**
     * The vendored public-download version, with another version name and download URL.
     *
     * @param version     the {@code version}
     * @param downloadUrl the {@code download.url}; the contract allows only absolute URLs
     * @return the {@code data} object as JSON, without the envelope
     */
    public static String versionSample(String version, String downloadUrl) {
        JsonObject data = JsonParser.parseString(fixture(PUBLIC_VERSION_FIXTURE)).getAsJsonObject()
                .getAsJsonObject("data");
        data.addProperty("version", version);
        data.getAsJsonObject("download").addProperty("url", downloadUrl);
        return data.toString();
    }

    /**
     * The vendored versions page carrying the given version objects.
     *
     * @param versions the versions, as {@link #versionSample} returns them; none for an empty page
     * @return the whole answer body
     */
    public static String versionsPageOf(String... versions) {
        JsonObject root = JsonParser.parseString(fixture(VERSIONS_PAGE_FIXTURE)).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        JsonArray items = new JsonArray();
        for (String version : versions) {
            items.add(JsonParser.parseString(version).getAsJsonObject());
        }
        data.add("items", items);
        data.addProperty("total", items.size());
        return root.toString();
    }

    /**
     * The vendored framework answer naming another version, with its file name and Maven Central
     * URL following it.
     *
     * @param version the {@code data.version}
     * @return the whole answer body
     */
    public static String frameworkSample(String version) {
        return fixture(FRAMEWORK_FIXTURE).replace(VENDORED_FRAMEWORK_VERSION, version);
    }

    /**
     * A vendored file, exactly as exported.
     *
     * @param path the path inside the contract, as its MANIFEST names it
     * @return the file's text
     */
    static String fixture(String path) {
        try (InputStream input = StubCatalogue.class.getClassLoader().getResourceAsStream(CONTRACT + path)) {
            if (input == null) {
                throw new IllegalStateException("Missing vendored catalogue file: " + CONTRACT + path);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read vendored catalogue file: " + CONTRACT + path, ex);
        }
    }

    /**
     * A minimal module JAR whose {@code plugin.yml} declares the given values.
     *
     * @param name            the plugin name
     * @param version         the plugin version
     * @param identifyString  the module's identify-string
     * @param apiVersion      the integer {@code api-version}, or {@code null} to declare none
     * @return the JAR bytes
     */
    public static byte[] moduleJar(String name, String version, String identifyString, String apiVersion) {
        StringBuilder yml = new StringBuilder()
                .append("name: ").append(name).append('\n')
                .append("version: '").append(version).append("'\n")
                .append("main: com.example.demo.DemoModule\n")
                .append("identify-string: ").append(identifyString).append('\n');
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
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot build the test module JAR", impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * @param bytes any bytes
     * @return the lower-case hex SHA-256 of them
     */
    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString().toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }

    /** A vendored answer {@link #serveVendored} installed for one path. */
    private static final class Vendored {
        private final int status;
        private final String body;

        private Vendored(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** One request the stub received. */
    public static final class Request {
        private final String method;
        private final String path;
        private final String query;
        private final String authorization;

        private Request(String method, String path, String query, String authorization) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.authorization = authorization;
        }

        /** @return the HTTP method */
        public String method() {
            return method;
        }

        /** @return the raw path, without the query */
        public String path() {
            return path;
        }

        /** @return the raw query, or {@code null} when the request carried none */
        public String query() {
            return query;
        }

        /** @return the raw {@code Authorization} header, or {@code null} when absent */
        public String authorization() {
            return authorization;
        }

        /** @return the path followed by {@code ?} and the query, for a readable access log */
        @Override
        public String toString() {
            return method + " " + path + (query == null ? "" : "?" + query);
        }
    }
}
