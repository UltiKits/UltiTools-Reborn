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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
 * <p>It answers the {@code /v1/catalogue/*} routes from the pending samples in
 * {@code src/test/resources/ulticloud-contract/pending/catalogue/} and serves a module JAR it
 * generates, so the metadata it reports ({@code sizeBytes}, {@code sha256}, {@code
 * X-Content-SHA256}) is always the truth about the bytes it actually serves. {@link Mode}
 * perturbs exactly one of those three, so a test can prove which check caught the difference.
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
    /** The newest compatible version the samples name. */
    public static final String VERSION = "1.1";
    /** The version the samples mark {@code license_required}. */
    public static final String LICENSED_VERSION = "2.0.0";

    private static final String FIXTURES = "ulticloud-contract/pending/catalogue/";
    private static final String VERSION_PATH = "/v1/catalogue/modules/" + MODULE + "/versions/" + VERSION;
    private static final String HASH_OF_ONE_MIB = repeat('d', 64);
    /** A download URL on a host that is not the catalogue's, as a compromised answer would name it. */
    private static final String FOREIGN_DOWNLOAD = "https://evil.example/module.jar";
    /** The byte count the {@link Mode#HUGE_BODY} answer announces. */
    public static final int HUGE_BODY_BYTES = 10 * 1024 * 1024;
    /** The byte count the {@link Mode#HUGE_BODY} metadata claims. */
    public static final int HUGE_DECLARED_BYTES = 1024 * 1024;
    private static final int BROKEN_STREAM_CHUNK = 1024;
    private static final int CHUNK = 8192;
    /** One supplementary character: four bytes in UTF-8, the widest one character can be. */
    private static final String WIDE_CHARACTER = "😀";

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.NORMAL;
    private volatile byte[] jar = moduleJar("Demo", VERSION, MODULE, "630");
    private volatile int servedBytes;
    private volatile boolean clientAborted;
    private volatile int longDescriptionChars;
    private volatile int versionCount;
    private volatile int changelogChars;

    private StubCatalogue(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a stub on a free loopback port, serving the pending samples and a module JAR declaring
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
     * card can be far larger than a 64 KiB answer. {@code 0} restores the pending sample.
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
     * version is the pending sample's {@link #VERSION} and the one marked {@code isLatest}; the
     * others are {@code 1.0.<i>}. {@code 0} restores the pending sample.
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
            route(exchange, path, query);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path, String query) throws IOException {
        String modules = "/v1/catalogue/modules";
        if (path.startsWith("/plugin/")) {
            send(exchange, 410, "{\"error\":{\"code\":\"gone\",\"message\":\"v1 catalogue is gone\"}}");
        } else if (modules.equals(path)) {
            send(exchange, 200, fixture("modules-list.json"));
        } else if ((modules + "/" + MODULE).equals(path)) {
            send(exchange, 200, moduleBody());
        } else if ((modules + "/" + MODULE + "/versions").equals(path)) {
            send(exchange, 200, versionsPage(query));
        } else if (path.startsWith(modules + "/" + MODULE + "/versions/")) {
            versionRoute(exchange, path.substring((modules + "/" + MODULE + "/versions/").length()));
        } else if ("/v1/catalogue/framework".equals(path)) {
            send(exchange, 200, fixture("framework.json"));
        } else {
            send(exchange, 404, fixture("error-not-found.json"));
        }
    }

    private void versionRoute(HttpExchange exchange, String tail) throws IOException {
        if (VERSION.equals(tail)) {
            send(exchange, 200, versionBody());
        } else if (LICENSED_VERSION.equals(tail)) {
            send(exchange, 200, fixture("version-license-required.json"));
        } else if ((VERSION + "/download").equals(tail)) {
            download(exchange);
        } else if (("redirect-" + VERSION + "/download").equals(tail)) {
            // Only reachable when a client follows the redirect the REDIRECT mode answers.
            stream(exchange, jar, headerFor(jar));
        } else {
            send(exchange, 404, fixture("error-not-found.json"));
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
                send(exchange, 404, fixture("error-not-found.json"));
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
        if (mode == Mode.WRONG_HEADER) {
            return repeat('e', 64);
        }
        String hash = sha256(body);
        return mode == Mode.UPPER_HEADER ? hash.toUpperCase(Locale.ROOT) : hash;
    }

    private void stream(HttpExchange exchange, byte[] body, String sha256Header) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
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
            clientAborted = true;
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
            clientAborted = true;
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

    private String versionBody() {
        JsonObject root = JsonParser.parseString(fixture("version-public.json")).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
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
        return root.toString();
    }

    private String moduleBody() {
        String sample = fixture("module.json");
        if (longDescriptionChars <= 0) {
            return sample;
        }
        JsonObject root = JsonParser.parseString(sample).getAsJsonObject();
        root.getAsJsonObject("data").addProperty("description", repeat(WIDE_CHARACTER, longDescriptionChars));
        return root.toString();
    }

    private String versionsPage(String query) {
        JsonObject root = JsonParser.parseString(fixture("versions-list.json")).getAsJsonObject();
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
        JsonArray all = data.getAsJsonArray("items");
        Integer apiLevel = integerParameter(query, "apiLevel");
        Integer minApiLevel = integerParameter(query, "minApiLevel");
        for (JsonElement element : all) {
            JsonObject version = element.getAsJsonObject().deepCopy();
            if (VERSION.equals(version.get("version").getAsString())) {
                version.addProperty("sizeBytes", mode == Mode.HUGE_BODY
                        ? HUGE_DECLARED_BYTES : (long) jar.length);
                version.addProperty("sha256", mode == Mode.HUGE_BODY ? HASH_OF_ONE_MIB : sha256(jar));
                if (mode == Mode.LICENSE_REQUIRED) {
                    JsonObject download = new JsonObject();
                    download.addProperty("kind", "license_required");
                    version.add("download", download);
                }
            }
            if (within(version, apiLevel, minApiLevel)) {
                items.add(version);
            }
        }
        data.add("items", items);
        data.addProperty("total", items.size());
        return root.toString();
    }

    private JsonArray longVersionsPageItems() {
        JsonObject template = JsonParser.parseString(fixture("version-public.json")).getAsJsonObject()
                .getAsJsonObject("data");
        JsonArray items = new JsonArray();
        for (int i = 0; i < versionCount; i++) {
            JsonObject item = template.deepCopy();
            String version = i == 0 ? VERSION : "1.0." + i;
            item.addProperty("version", version);
            item.addProperty("isLatest", i == 0);
            item.addProperty("changelog", repeat(WIDE_CHARACTER, changelogChars));
            item.addProperty("sizeBytes", (long) jar.length);
            item.addProperty("sha256", sha256(jar));
            JsonObject download = new JsonObject();
            download.addProperty("kind", "public");
            download.addProperty("url", "/v1/catalogue/modules/" + MODULE + "/versions/" + version + "/download");
            item.add("download", download);
            items.add(item);
        }
        return items;
    }

    private static boolean within(JsonObject version, Integer apiLevel, Integer minApiLevel) {        if (apiLevel == null && minApiLevel == null) {
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
     * One of the pending samples, exactly as it is served.
     *
     * <p>Tests that stand in for the catalogue build their answers from these rather than from
     * hand-written JSON, so the shapes have one source: the samples plan 22-26 swaps.
     *
     * @param name the sample's file name under {@code pending/catalogue/}
     * @return the whole answer body
     */
    public static String sample(String name) {
        return fixture(name);
    }

    /**
     * The pending module card's shape, addressed to another module.
     *
     * @param identifier the {@code identifier}
     * @param name       the {@code name}
     * @return the whole answer body
     */
    public static String moduleCardSample(String identifier, String name) {
        JsonObject root = JsonParser.parseString(fixture("module.json")).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        data.addProperty("identifier", identifier);
        data.addProperty("name", name);
        return root.toString();
    }

    /**
     * The pending version sample's shape, with another version name and download URL.
     *
     * @param version     the {@code version}
     * @param downloadUrl the {@code download.url}, relative or absolute
     * @return the {@code data} object as JSON, without the envelope
     */
    public static String versionSample(String version, String downloadUrl) {
        JsonObject data = JsonParser.parseString(fixture("version-public.json")).getAsJsonObject()
                .getAsJsonObject("data");
        data.addProperty("version", version);
        data.getAsJsonObject("download").addProperty("url", downloadUrl);
        return data.toString();
    }

    /**
     * The pending versions page's shape carrying the given version objects.
     *
     * @param versions the versions, as {@link #versionSample} returns them; none for an empty page
     * @return the whole answer body
     */
    public static String versionsPageOf(String... versions) {
        JsonObject root = JsonParser.parseString(fixture("versions-list.json")).getAsJsonObject();
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
     * The pending framework sample's shape naming another version.
     *
     * @param version the {@code data.version}
     * @return the whole answer body
     */
    public static String frameworkSample(String version) {
        JsonObject root = JsonParser.parseString(fixture("framework.json")).getAsJsonObject();
        root.getAsJsonObject("data").addProperty("version", version);
        return root.toString();
    }

    static String fixture(String name) {        try (InputStream input = StubCatalogue.class.getClassLoader().getResourceAsStream(FIXTURES + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing pending catalogue sample: " + FIXTURES + name);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read pending catalogue sample: " + FIXTURES + name, ex);
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
