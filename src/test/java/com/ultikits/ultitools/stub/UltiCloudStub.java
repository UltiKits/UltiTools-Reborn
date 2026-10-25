package com.ultikits.ultitools.stub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.JDKRegularExpressionFactory;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.contract.ContractFixtures;

/**
 * A local stand-in for UltiCloud that a 6.3.1 build pointed at it (build property
 * {@code ultitools.api.url}) can run a whole session against (plan 22-19): device authorization,
 * credential revoke, the {@code /v1/agent} gateway and {@code /v1/catalogue/*}, all on one loopback
 * port, with every v1 path answered 410 and logged.
 *
 * <p>Its behaviour comes from the frozen protocol contract 2.0.0 vendored by plan 22-08 (fixtures,
 * JSON Schemas and the message, close-code and timing tables of {@code PROTOCOL.md}), read through
 * {@link Contract}; it imports no framework protocol class, so it cannot inherit a framework defect.
 * Local Codex starts it for the stub-environment UAT rows, and the full-session tests start it
 * in-process through {@link #start(Options)}.
 *
 * <p>Parts: {@link StubFrontDoor} owns the public port and routes each connection by its request
 * head; {@link StubGateway} speaks the agent protocol; {@link StubDeviceAuthority} answers device
 * authorization and credential revoke; {@link StubCatalogue} (plan 22-18) answers the catalogue;
 * {@link StubAccessLog} records everything.
 *
 * <p>Test scope only: nothing here is part of {@code src/main} or the shaded JAR.
 *
 * @since 6.3.1
 */
public final class UltiCloudStub {

    private UltiCloudStub() {
    }

    /**
     * Starts every part of the stub on loopback.
     *
     * @param options ports, access log, fixture root and pre-accepted credentials
     * @return the running stub
     * @throws IOException when a port cannot be bound or the access log cannot be opened
     */
    public static Handle start(Options options) throws IOException {
        Handle handle = new Handle(options);
        try {
            handle.startParts();
        } catch (IOException | RuntimeException failure) {
            handle.close();
            throw failure;
        }
        return handle;
    }

    static int portOf(String baseUrl) {
        return Integer.parseInt(baseUrl.substring(baseUrl.lastIndexOf(':') + 1));
    }

    static ThreadFactory daemonThreads(String name) {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ================================================================== options

    /** How a stub is started; every setter returns this object. */
    public static final class Options {
        private int httpPort;
        private Path accessLog;
        private Path fixturesRoot;
        private long helloTimeoutMs = -1;
        private boolean deviceAutoApprove = true;
        private final Map<String, String> credentials = new LinkedHashMap<>();

        private Options() {
        }

        /** @return ephemeral ports, an in-memory log, the classpath contract, device auto-approve */
        public static Options defaults() {
            return new Options();
        }

        /**
         * @param port the public port; {@code 0} picks a free one
         * @return these options
         */
        public Options httpPort(int port) {
            this.httpPort = port;
            return this;
        }

        /**
         * @param file the access-log file to append to, or {@code null} for memory only
         * @return these options
         */
        public Options accessLog(Path file) {
            this.accessLog = file;
            return this;
        }

        /**
         * @param root a directory holding a copy of the contract version directory (with its
         *             {@code MANIFEST.json}), or {@code null} for the vendored classpath copy
         * @return these options
         */
        public Options fixturesRoot(Path root) {
            this.fixturesRoot = root;
            return this;
        }

        /**
         * @param millis how long the gateway waits for {@code hello} before closing with 4408;
         *               negative for the contract's {@code timing.helloTimeoutMs}
         * @return these options
         */
        public Options helloTimeoutMs(long millis) {
            this.helloTimeoutMs = millis;
            return this;
        }

        /**
         * @param auto whether a device authorization is approved after its first poll (the default);
         *             {@code false} keeps every authorization pending until an admin action
         * @return these options
         */
        public Options deviceAutoApprove(boolean auto) {
            this.deviceAutoApprove = auto;
            return this;
        }

        /**
         * Accepts a server credential the stub did not issue, as a stored credential from an earlier
         * login is.
         *
         * @param credential the {@code ucs_} credential
         * @param serverId   the server id its {@code welcome} carries
         * @return these options
         */
        public Options acceptCredential(String credential, String serverId) {
            credentials.put(credential, serverId);
            return this;
        }

        int httpPort() {
            return httpPort;
        }

        Path accessLog() {
            return accessLog;
        }
    }

    // ================================================================== handle

    /** A running stub; {@link #close()} stops every part. */
    public static final class Handle implements AutoCloseable {
        private final Options options;
        private Contract contract;
        private StubAccessLog log;
        private Credentials credentials;
        private StubCatalogue catalogue;
        private HttpServer http;
        private ExecutorService httpExecutor;
        private StubDeviceAuthority deviceAuthority;
        private StubGateway gateway;
        private StubFrontDoor frontDoor;

        private Handle(Options options) {
            this.options = options;
        }

        private void startParts() throws IOException {
            contract = Contract.load(options.fixturesRoot);
            log = new StubAccessLog(options.accessLog);
            credentials = new Credentials(log);
            for (Map.Entry<String, String> accepted : options.credentials.entrySet()) {
                credentials.accept(accepted.getKey(), accepted.getValue(), "accepted at start");
            }
            catalogue = StubCatalogue.start();
            gateway = new StubGateway(contract, log, credentials, options.helloTimeoutMs < 0
                    ? contract.constant("timing.helloTimeoutMs") : options.helloTimeoutMs);
            gateway.start();
            deviceAuthority = new StubDeviceAuthority(contract, log, credentials, options.deviceAutoApprove,
                    credential -> gateway.closeConnectionsUsing(credential, 4401, "credential revoked"));
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            httpExecutor = Executors.newCachedThreadPool(daemonThreads("ulticloud-stub-http"));
            http.setExecutor(httpExecutor);
            deviceAuthority.install(http);
            http.start();
            frontDoor = new StubFrontDoor(options.httpPort(), contract, log, credentials,
                    gateway.port(), http.getAddress().getPort(), portOf(catalogue.baseUrl()));
            frontDoor.start();
            log.log("STUB", "started http=" + baseUrl() + " gateway=127.0.0.1:" + gateway.port()
                    + " internal-http=127.0.0.1:" + http.getAddress().getPort()
                    + " catalogue=" + catalogue.baseUrl()
                    + " contract=" + contract.describe());
        }

        /** @return the public port: device, revoke, {@code /v1/agent} and {@code /v1/catalogue/*} */
        public int httpPort() {
            return frontDoor.port();
        }

        /** @return the value to build the framework with as {@code ultitools.api.url} */
        public String baseUrl() {
            return "http://127.0.0.1:" + httpPort();
        }

        /** @return the bound public address, always a loopback address */
        public InetSocketAddress httpAddress() {
            return frontDoor.address();
        }

        /** @return the access-log file, or {@code null} when the log is kept in memory only */
        public Path accessLogPath() {
            return log.file();
        }

        /** @return the access log */
        public StubAccessLog accessLog() {
            return log;
        }

        /** @return the gateway behind {@code /v1/agent} */
        public StubGateway gateway() {
            return gateway;
        }

        /** @return the catalogue behind {@code /v1/catalogue/*} */
        public StubCatalogue catalogue() {
            return catalogue;
        }

        /** @return the device authority behind {@code /v1/device/*} and the revoke endpoint */
        public StubDeviceAuthority deviceAuthority() {
            return deviceAuthority;
        }

        /** @return the public-port acceptor */
        public StubFrontDoor frontDoor() {
            return frontDoor;
        }

        /** @return the contract the stub reads */
        public Contract contract() {
            return contract;
        }

        /**
         * Accepts a stored server credential the stub did not issue.
         *
         * @param credential the {@code ucs_} credential
         * @param serverId   the server id its {@code welcome} carries
         */
        public void acceptCredential(String credential, String serverId) {
            credentials.accept(credential, serverId, "accepted");
        }

        @Override
        public void close() {
            if (frontDoor != null) {
                frontDoor.stop();
            }
            if (http != null) {
                http.stop(0);
            }
            if (httpExecutor != null) {
                httpExecutor.shutdownNow();
            }
            if (gateway != null) {
                gateway.stop();
            }
            if (catalogue != null) {
                catalogue.close();
            }
            if (log != null) {
                log.log("STUB", "stopped");
                log.close();
            }
        }
    }

    // ================================================================== credentials

    /**
     * The server credentials the stub knows: issued by its device authority, accepted at start or
     * through the admin API, or delivered by a {@code credential.rotate} it sent. A revoked or
     * retired credential is refused with 401 at the next upgrade, as the contract's credential step
     * requires.
     */
    public static final class Credentials {
        private final Map<String, String> serverIds = new LinkedHashMap<>();
        private final Set<String> revoked = new HashSet<>();
        private final StubAccessLog log;

        Credentials(StubAccessLog log) {
            this.log = log;
        }

        /**
         * @param credential the credential
         * @param serverId   the server it belongs to
         * @param why        the reason written to the access log
         */
        public synchronized void accept(String credential, String serverId, String why) {
            serverIds.put(credential, serverId);
            revoked.remove(credential);
            log.log("CREDENTIAL", why + " " + credential + " serverId=" + serverId);
        }

        /**
         * @param credential a credential, or {@code null}
         * @return its server id, or {@code null} when it is unknown or revoked
         */
        public synchronized String serverIdOf(String credential) {
            if (credential == null || revoked.contains(credential)) {
                return null;
            }
            return serverIds.get(credential);
        }

        /**
         * @param credential the credential to revoke
         * @param why        the reason written to the access log
         * @return whether it was valid until now
         */
        public synchronized boolean revoke(String credential, String why) {
            boolean wasValid = serverIdOf(credential) != null;
            if (wasValid) {
                revoked.add(credential);
                log.log("CREDENTIAL", why + " " + credential);
            }
            return wasValid;
        }

        /** @return the fingerprints of the credentials that would pass the credential step now */
        public synchronized List<String> validFingerprints() {
            List<String> valid = new ArrayList<>();
            for (String credential : serverIds.keySet()) {
                if (!revoked.contains(credential)) {
                    valid.add(StubAccessLog.fingerprint(credential));
                }
            }
            return valid;
        }
    }

    // ================================================================== contract

    /**
     * The frozen contract as the stub reads it: fixtures, JSON Schemas, and the message, close-code
     * and timing tables of {@code PROTOCOL.md}.
     *
     * <p>Schemas are checked the way {@code PROTOCOL.md} section 12 recommends for Java: networknt
     * json-schema-validator, Draft 2020-12, format assertions off, JDK regular expressions.
     */
    public static final class Contract {
        /** A ULID as the contract's envelope ids are written. */
        public static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

        /** The contract's reliable-flag and empty-cell markers in {@code PROTOCOL.md}. */
        private static final String YES = "是";
        private static final String NONE = "—";
        private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
        private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(
                        SchemaRegistryConfig.builder().formatAssertionsEnabled(false)
                                .regularExpressionFactory(JDKRegularExpressionFactory.getInstance()).build()));

        private final Path root;
        private final Map<String, String> texts = new ConcurrentHashMap<>();
        private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
        private final List<String> paths;
        private final Map<String, MessageType> messages;
        private final List<Integer> closeCodes;
        private final Map<String, String> constants;
        private final Set<String> capabilities;

        private Contract(Path root) {
            this.root = root;
            List<String> listed = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : object("MANIFEST.json").getAsJsonObject("files").entrySet()) {
                listed.add(entry.getKey());
            }
            Collections.sort(listed);
            this.paths = Collections.unmodifiableList(listed);
            String[] lines = text("PROTOCOL.md").split("\n");
            this.messages = parseMessages(lines);
            this.closeCodes = parseCloseCodes(lines);
            this.constants = parseConstants(lines);
            Set<String> names = new HashSet<>();
            for (MessageType type : messages.values()) {
                if (type.capability() != null && !type.capability().startsWith("modules:")) {
                    names.add(type.capability());
                }
            }
            this.capabilities = Collections.unmodifiableSet(names);
        }

        /**
         * @param root a directory holding the contract version directory's content, or {@code null}
         *             for the classpath copy under {@link ContractFixtures#ROOT}
         * @return the contract
         */
        public static Contract load(Path root) {
            return new Contract(root);
        }

        /** @return where the contract is read from, for the start line of the access log */
        public String describe() {
            return root == null ? "classpath:" + ContractFixtures.ROOT : root.toAbsolutePath().toString();
        }

        /**
         * @param relative a path inside the contract version directory
         * @return its text, UTF-8
         */
        public String text(String relative) {
            String cached = texts.get(relative);
            if (cached != null) {
                return cached;
            }
            String text = root == null ? classpathText(ContractFixtures.ROOT + "/" + relative) : fileText(relative);
            texts.put(relative, text);
            return text;
        }

        /**
         * @param relative a JSON file inside the contract version directory
         * @return a fresh copy of its object
         */
        public JsonObject object(String relative) {
            return JsonParser.parseString(text(relative)).getAsJsonObject();
        }

        /** @return every file the contract's {@code MANIFEST.json} lists, sorted */
        public List<String> paths() {
            return paths;
        }

        /**
         * @param directory a directory inside the contract, ending with {@code /}
         * @return the bare names of the JSON files directly in it, sorted
         */
        public List<String> fileNames(String directory) {
            List<String> names = new ArrayList<>();
            for (String path : paths) {
                if (path.startsWith(directory) && path.endsWith(".json")
                        && path.indexOf('/', directory.length()) < 0) {
                    names.add(path.substring(directory.length()));
                }
            }
            return names;
        }

        /**
         * @param type an agent message type
         * @return the schema file of its full frame
         */
        public static String frameSchema(String type) {
            return "schemas/agent/" + type + ".schema.json";
        }

        /**
         * @param operationSchema a file name under {@code schemas/agent-rest/}, for example
         *                        {@code deviceToken.request.schema.json}
         * @return its contract path
         */
        public static String restSchema(String operationSchema) {
            return "schemas/agent-rest/" + operationSchema;
        }

        /**
         * Checks a value against one of the contract's schemas.
         *
         * @param schemaPath the schema's contract path
         * @param value      the value
         * @return {@code null} when it is valid, otherwise the schema's first concrete issue
         *         ({@code oneOf}/{@code anyOf} summaries are skipped in favour of the issue inside
         *         them), as {@code <instance location>: <message>}
         */
        public String firstIssue(String schemaPath, JsonElement value) {
            List<com.networknt.schema.Error> errors = schema(schemaPath).validate(node(value.toString()));
            if (errors.isEmpty()) {
                return null;
            }
            com.networknt.schema.Error chosen = errors.get(0);
            for (com.networknt.schema.Error error : errors) {
                String keyword = error.getKeyword();
                if (!"oneOf".equals(keyword) && !"anyOf".equals(keyword)) {
                    chosen = error;
                    break;
                }
            }
            String location = String.valueOf(chosen.getInstanceLocation());
            return (location.isEmpty() ? "$" : location) + ": " + chosen.getMessage();
        }

        /**
         * @param type an agent message type
         * @return its row of the message table, or {@code null} when the contract does not register it
         */
        public MessageType message(String type) {
            return type == null ? null : messages.get(type);
        }

        /** @return every row of the agent message table, in table order */
        public Collection<MessageType> messages() {
            return Collections.unmodifiableCollection(messages.values());
        }

        /** @return the gateway close codes of section 7, in table order */
        public List<Integer> closeCodes() {
            return closeCodes;
        }

        /**
         * @param name a constant of section 9, for example {@code ack.flushMs}
         * @return its numeric value
         * @throws IllegalStateException when the contract does not define it as a number
         */
        public long constant(String name) {
            String value = constants.get(name);
            if (value == null || !value.matches("[0-9]+")) {
                throw new IllegalStateException("The contract defines no numeric constant " + name);
            }
            return Long.parseLong(value);
        }

        /**
         * @param capability a capability a plugin declared
         * @return whether the gateway knows it: a capability named in the message table, or a module
         *         capability {@code modules:<id>@<X.Y.Z>}
         */
        public boolean knowsCapability(String capability) {
            return capabilities.contains(capability)
                    || capability.matches("^modules:[a-z0-9][a-z0-9.-]*@(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
        }

        /** @return a new ULID: 48 bits of millisecond time, 80 random bits, Crockford base32 */
        public static String newUlid() {
            char[] chars = new char[26];
            long time = System.currentTimeMillis();
            for (int i = 9; i >= 0; i--) {
                chars[i] = CROCKFORD[(int) (time & 31)];
                time >>>= 5;
            }
            for (int i = 10; i < 26; i++) {
                chars[i] = CROCKFORD[RANDOM.nextInt(32)];
            }
            return new String(chars);
        }

        /**
         * @param value any text, or {@code null}
         * @return whether it is a ULID as the contract writes envelope ids
         */
        public static boolean isUlid(String value) {
            return value != null && ULID.matcher(value).matches();
        }

        /**
         * @param bytes how many random bytes
         * @return them as unpadded base64url, the alphabet of credentials and device codes
         */
        static String randomToken(int bytes) {
            byte[] random = new byte[bytes];
            RANDOM.nextBytes(random);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        }

        private Schema schema(String path) {
            return schemas.computeIfAbsent(path, key -> REGISTRY.getSchema(node(text(key))));
        }

        private static JsonNode node(String text) {
            try {
                return MAPPER.readTree(text);
            } catch (IOException ex) {
                throw new IllegalArgumentException("Invalid JSON", ex);
            }
        }

        private String fileText(String relative) {
            try {
                return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read contract file " + root.resolve(relative), ex);
            }
        }

        private static String classpathText(String path) {
            try (InputStream input = Contract.class.getClassLoader().getResourceAsStream(path)) {
                if (input == null) {
                    throw new IllegalStateException("Missing contract resource: " + path);
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read contract resource: " + path, ex);
            }
        }

        /** Rows of the first {@code ###} table after the section heading starting with {@code heading}. */
        private static List<String[]> firstSubsectionRows(String[] lines, String heading) {
            int start = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith(heading)) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                throw new IllegalStateException("PROTOCOL.md has no section " + heading);
            }
            List<String[]> rows = new ArrayList<>();
            boolean subsections = hasSubsection(lines, start);
            boolean inSubsection = false;
            for (int i = start + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("## ")) {
                    break;
                }
                if (line.startsWith("### ")) {
                    if (inSubsection) {
                        break;
                    }
                    inSubsection = true;
                    continue;
                }
                if ((inSubsection || !subsections) && line.startsWith("|") && !line.startsWith("|---")) {
                    rows.add(cells(line));
                }
            }
            return rows;
        }

        private static boolean hasSubsection(String[] lines, int start) {
            for (int i = start + 1; i < lines.length; i++) {
                if (lines[i].startsWith("## ")) {
                    return false;
                }
                if (lines[i].startsWith("### ")) {
                    return true;
                }
            }
            return false;
        }

        private static String[] cells(String row) {
            String[] parts = row.split("\\|", -1);
            String[] cells = new String[Math.max(0, parts.length - 2)];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = parts[i + 1].trim();
            }
            return cells;
        }

        private static String unquote(String cell) {
            Matcher matcher = BACKTICKED.matcher(cell);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static Map<String, MessageType> parseMessages(String[] lines) {
            Map<String, MessageType> table = new LinkedHashMap<>();
            for (String[] row : firstSubsectionRows(lines, "## 4.")) {
                String type = unquote(row[0]);
                if (type == null || row.length < 8) {
                    continue;
                }
                List<String> replies = new ArrayList<>();
                Matcher reply = BACKTICKED.matcher(row[4]);
                while (reply.find()) {
                    replies.add(reply.group(1));
                }
                String capability = NONE.equals(row[7]) ? null : unquote(row[7]);
                table.put(type, new MessageType(type, row[1], row[3], replies, YES.equals(row[5]),
                        YES.equals(row[6]), capability));
            }
            if (table.isEmpty()) {
                throw new IllegalStateException("PROTOCOL.md section 4 has no agent message table");
            }
            return table;
        }

        private static List<Integer> parseCloseCodes(String[] lines) {
            List<Integer> codes = new ArrayList<>();
            for (String[] row : firstSubsectionRows(lines, "## 7.")) {
                if (row.length > 0 && row[0].matches("[0-9]{4}")) {
                    codes.add(Integer.valueOf(row[0]));
                }
            }
            if (codes.isEmpty()) {
                throw new IllegalStateException("PROTOCOL.md section 7 has no plugin close-code table");
            }
            return Collections.unmodifiableList(codes);
        }

        private static Map<String, String> parseConstants(String[] lines) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String[] row : firstSubsectionRows(lines, "## 9.")) {
                String name = row.length > 1 ? unquote(row[0]) : null;
                if (name != null) {
                    values.put(name, row[1]);
                }
            }
            return values;
        }
    }

    /** One row of the contract's agent message table (section 4). */
    public static final class MessageType {
        private final String type;
        private final String direction;
        private final String replyTo;
        private final List<String> replies;
        private final boolean ackFirst;
        private final boolean reliable;
        private final String capability;

        MessageType(String type, String direction, String replyTo, List<String> replies, boolean ackFirst,
                    boolean reliable, String capability) {
            this.type = type;
            this.direction = direction;
            this.replyTo = replyTo;
            this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
            this.ackFirst = ackFirst;
            this.reliable = reliable;
            this.capability = capability;
        }

        /** @return the message type */
        public String type() {
            return type;
        }

        /** @return whether a plugin may send it ({@code up} or {@code both}) */
        public boolean up() {
            return "up".equals(direction) || "both".equals(direction);
        }

        /** @return whether the gateway may send it ({@code down} or {@code both}) */
        public boolean down() {
            return "down".equals(direction) || "both".equals(direction);
        }

        /** @return the envelope {@code replyTo} rule: {@code required}, {@code forbidden} or {@code optional} */
        public String replyTo() {
            return replyTo;
        }

        /** @return the types that may answer it */
        public List<String> replies() {
            return replies;
        }

        /** @return whether it is acknowledged with {@code command.ack} before it runs */
        public boolean ackFirst() {
            return ackFirst;
        }

        /** @return whether it is a reliable event the gateway acknowledges with {@code ack} */
        public boolean reliable() {
            return reliable;
        }

        /** @return the capability it needs, or {@code null} when it needs none */
        public String capability() {
            return capability;
        }
    }
}
