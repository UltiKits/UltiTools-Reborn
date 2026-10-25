package com.ultikits.ultitools.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;

/**
 * Proves the UAT stub of plan 22-19 against the frozen contract: a v2 client logs in, connects,
 * handshakes and gets an event acknowledged, everything appears in the access log, and every body
 * and frame the stub answers validates against the vendored JSON Schema.
 *
 * <p>The checks here use the contract helpers of plan 22-08 ({@link ContractSchemas},
 * {@link ContractFixtures}), not the stub's own validator, so a stub that drifted from the
 * contract fails here rather than passing its own reading of it.
 */
class UltiCloudStubSelfTest {

    private static final String FRAMEWORK = "6.3.1";
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    @TempDir
    Path temp;

    private UltiCloudStub.Handle stub;
    private final List<AgentClient> clients = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log")));
    }

    @AfterEach
    void stopStub() {
        for (AgentClient client : clients) {
            client.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    // ------------------------------------------------------------------ Task 1: the tracer

    @Test
    void deviceLoginThenUpgradeHelloWelcomePingAndAnAckedEventAreAllLogged() throws Exception {
        Login login = login();

        AgentClient client = connect(login.credential);
        JsonObject hello = hello("events:players");
        client.send(hello);
        JsonObject welcome = client.nextFrame(5000);
        assertThat(welcome.get("type").getAsString()).isEqualTo("welcome");
        assertThat(welcome.get("replyTo").getAsString()).isEqualTo(hello.get("id").getAsString());
        assertThat(welcome.getAsJsonObject("payload").get("serverId").getAsString()).isEqualTo(login.serverId);
        ContractSchemas.assertFrameValid(welcome);

        client.sendText("ping");
        assertThat(client.nextText(5000)).isEqualTo("pong");

        JsonObject join = frame("player.join", "basic.json");
        long sentAt = System.nanoTime();
        client.send(join);
        JsonObject ack = client.nextFrame(1000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAt);
        assertThat(ack.get("type").getAsString()).isEqualTo("ack");
        assertThat(ack.getAsJsonObject("payload").getAsJsonArray("ids")).containsExactly(join.get("id"));
        assertThat(elapsedMs).isLessThan(1000);
        ContractSchemas.assertFrameValid(ack);

        List<String> lines = stub.accessLog().lines();
        assertInOrder(lines,
                "HTTP POST /v1/device/authorize",
                "HTTP POST /v1/device/token",
                "HTTP POST /v1/device/token",
                "UPGRADE GET /v1/agent",
                "WS-IN conn=1 hello",
                "WS-OUT conn=1 welcome",
                "WS-IN conn=1 ping",
                "WS-OUT conn=1 pong",
                "WS-IN conn=1 player.join",
                "WS-OUT conn=1 ack");
        String memory = String.join("\n", lines);
        String onDisk = new String(Files.readAllBytes(temp.resolve("access.log")), StandardCharsets.UTF_8);
        for (String text : new String[]{memory, onDisk}) {
            assertThat(text).doesNotContain(login.credential)
                    .contains("ucs_***#" + StubAccessLog.fingerprint(login.credential));
        }
        assertThat(onDisk).contains("WS-OUT conn=1 ack");
        assertThat(lines.get(0)).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z .*");
    }

    @Test
    void aRequestBodyThatFailsItsSchemaIsLoggedInvalidWithTheSchemasFirstIssue() throws Exception {
        JsonObject body = ContractFixtures.object("fixtures/agent-rest/deviceAuthorize/request/valid/server.json");
        String legacy = body.remove("legacyServerUuid").getAsString();
        body.addProperty("attachToServerId", legacy);

        HttpReply reply = RawHttp.post(stub.httpPort(), "/v1/device/authorize", body.toString(), null);

        assertThat(reply.status).isEqualTo(400);
        assertThat(reply.json().getAsJsonObject("error").get("code").getAsString()).isEqualTo("invalid_request");
        ContractSchemas.assertRestBodyValid("deviceAuthorize.error.400.schema.json", reply.json());
        assertThat(stub.accessLog().lines()).anySatisfy(line -> assertThat(line)
                .contains("REST-INVALID deviceAuthorize.request")
                .contains("attachToServerId"));
    }

    @Test
    void aValidRequestBodyIsLoggedValid() throws Exception {
        login();
        assertThat(stub.accessLog().lines())
                .anySatisfy(line -> assertThat(line).contains("REST-VALID deviceAuthorize.request"))
                .anySatisfy(line -> assertThat(line).contains("REST-VALID deviceToken.request"));
    }

    @Test
    void pooledHttpConnectionsNeverCarryARequestToTheWrongRoute() throws Exception {
        // HttpURLConnection keeps connections alive; the front door must close each one, so a second
        // request to another route is not sent down the first route's relay.
        for (int i = 0; i < 3; i++) {
            java.net.HttpURLConnection authorize = (java.net.HttpURLConnection) new java.net.URL(
                    stub.baseUrl() + "/v1/device/authorize").openConnection();
            authorize.setRequestMethod("POST");
            authorize.setDoOutput(true);
            authorize.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/request/valid/server.json")
                    .getBytes(StandardCharsets.UTF_8);
            authorize.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = authorize.getOutputStream()) {
                out.write(bytes);
            }
            assertThat(authorize.getResponseCode()).isEqualTo(200);
            JsonObject issued = JsonParser.parseString(readAll(authorize.getInputStream())).getAsJsonObject();
            ContractSchemas.assertRestBodyValid("deviceAuthorize.response.200.schema.json", issued);

            java.net.HttpURLConnection framework = (java.net.HttpURLConnection) new java.net.URL(
                    stub.baseUrl() + "/v1/catalogue/framework").openConnection();
            assertThat(framework.getResponseCode()).isEqualTo(200);
            assertThat(readAll(framework.getInputStream())).contains("\"version\"");
        }
        assertThat(stub.catalogue().paths()).containsExactly(
                "/v1/catalogue/framework", "/v1/catalogue/framework", "/v1/catalogue/framework");
    }

    // ------------------------------------------------------------------ Task 2: the admin API

    @Test
    void bothPortsBindLoopbackOnly() {
        assertThat(stub.httpAddress().getAddress().isLoopbackAddress()).isTrue();
        assertThat(stub.adminAddress().getAddress().isLoopbackAddress()).isTrue();
        assertThat(stub.adminUrl()).isEqualTo("http://127.0.0.1:" + stub.adminPort());
    }

    @Test
    void theContractTablesTheStubReadsAreTheOnesThePlanNames() {
        UltiCloudStub.Contract contract = stub.contract();
        assertThat(contract.closeCodes())
                .containsExactly(1000, 1001, 1009, 1011, 4000, 4400, 4401, 4402, 4408, 4426, 4429);
        assertThat(contract.constant("ack.flushMs")).isEqualTo(1000);
        assertThat(contract.constant("ack.batchMax")).isEqualTo(100);
        assertThat(contract.constant("timing.helloTimeoutMs")).isEqualTo(10000);
        assertThat(contract.message("player.join").reliable()).isTrue();
        assertThat(contract.message("player.join").capability()).isEqualTo("events:players");
        assertThat(contract.message("command.execute").ackFirst()).isTrue();
        assertThat(contract.message("player.link.create").replies())
                .containsExactly("player.link.created", "player.link.completed", "player.link.expired");
        assertThat(contract.messages()).hasSize(60);
    }

    @Test
    void eachRefusalModeAnswersItsStatusHeadersAndVendoredBody() throws Exception {
        Login login = login();
        String[][] modes = {
            {"426", "{\"refusal\":426,\"minFramework\":\"9.9.9\"}"},
            {"401", "{\"refusal\":\"401\"}"},
            {"402", "{\"refusal\":402}"},
            {"429", "{\"refusal\":429,\"retryAfterSeconds\":120}"},
        };
        for (String[] mode : modes) {
            assertThat(admin("/_stub/mode", mode[1]).status).isEqualTo(200);
            HttpReply refused = upgradeRaw(login.credential, "2", FRAMEWORK);
            int status = Integer.parseInt(mode[0]);
            assertThat(refused.status).as(mode[1]).isEqualTo(status);
            ContractSchemas.assertRestBodyValid("agentUpgrade.error." + status + ".schema.json", refused.json());
            if (status == 426) {
                assertThat(refused.headers).containsEntry("x-ulticloud-protocol-supported", "2")
                        .containsEntry("x-ulticloud-min-framework", "9.9.9");
                assertThat(refused.json().getAsJsonObject("error").getAsJsonObject("details")
                        .get("minFramework").getAsString()).isEqualTo("9.9.9");
                assertThat(refused.json().getAsJsonObject("error").get("message").getAsString()).contains("9.9.9");
            } else if (status == 429) {
                assertThat(refused.headers).containsEntry("retry-after", "120");
            } else if (status == 402) {
                assertThat(refused.json().getAsJsonObject("error").getAsJsonObject("details")
                        .get("manageUrl").getAsString()).startsWith("https://");
            }
            assertThat(stub.accessLog().lines()).anySatisfy(line -> assertThat(line)
                    .contains("UPGRADE GET /v1/agent").contains("-> " + status));
        }
        assertThat(admin("/_stub/mode", "{\"refusal\":\"none\"}").status).isEqualTo(200);
        connectAndHello(login, "events:players");
        assertThat(admin("/_stub/mode", "{\"refusal\":418}").status).isEqualTo(400);
    }

    @Test
    void naturalUpgradeChecksRunInTheContractOrder() throws Exception {
        Login login = login();
        assertThat(upgradeRaw(login.credential, null, FRAMEWORK).status).isEqualTo(426);
        assertThat(upgradeRaw(login.credential, "1", FRAMEWORK).status).isEqualTo(426);
        assertThat(upgradeRaw(login.credential, "2", null).status).isEqualTo(426);
        assertThat(upgradeRaw(login.credential, "2", "6.3").status).isEqualTo(426);
        assertThat(upgradeRaw("ucs_" + repeat('B', 43), "2", FRAMEWORK).status).isEqualTo(401);
        // Protocol is decided before the credential: a v1-style handshake with no protocol header
        // and no valid credential is refused as outdated, not as unauthorised.
        assertThat(upgradeRaw("not-a-credential", null, null).status).isEqualTo(426);
        assertThat(upgradeRaw(login.credential, "2", "6.3.1-SNAPSHOT").status)
                .as("a pre-release framework is a valid semantic version").isNotEqualTo(426);
    }

    @Test
    void unreachableRefusesConnectionsUntilItIsSwitchedOff() throws Exception {
        Login login = login();
        AgentClient live = connectAndHello(login, "events:players");
        assertThat(admin("/_stub/unreachable", "{\"on\":true}").status).isEqualTo(200);
        assertThat(live.awaitClose(5000)).isNotEqualTo(1000);
        assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), stub.httpPort()).close())
                .isInstanceOf(java.net.ConnectException.class);
        assertThat(admin("/_stub/unreachable", "{\"on\":false}").status).isEqualTo(200);
        connectAndHello(login, "events:players");
    }

    @Test
    void closeEndsTheLiveConnectionWithEveryContractCode() throws Exception {
        Login login = login();
        List<Integer> codes = new ArrayList<>(stub.contract().closeCodes());
        codes.remove(Integer.valueOf(4401));
        codes.remove(Integer.valueOf(4402));
        for (int code : codes) {
            AgentClient client = connectAndHello(login, "events:players");
            assertThat(admin("/_stub/close", "{\"code\":" + code + "}").status).isEqualTo(200);
            assertThat(client.awaitClose(5000)).as("close %d", code).isEqualTo(code);
        }
        // 4402 suspends the server: the probe after it is refused with 402 until the mode is cleared.
        AgentClient planLimited = connectAndHello(login, "events:players");
        assertThat(admin("/_stub/close", "{\"code\":4402}").status).isEqualTo(200);
        assertThat(planLimited.awaitClose(5000)).isEqualTo(4402);
        assertThat(upgradeRaw(login.credential, "2", FRAMEWORK).status).isEqualTo(402);
        assertThat(admin("/_stub/mode", "{\"refusal\":\"none\"}").status).isEqualTo(200);
        // 4401 revokes: the credential is refused with 401 afterwards.
        AgentClient revoked = connectAndHello(login, "events:players");
        assertThat(admin("/_stub/close", "{\"code\":4401}").status).isEqualTo(200);
        assertThat(revoked.awaitClose(5000)).isEqualTo(4401);
        assertThat(upgradeRaw(login.credential, "2", FRAMEWORK).status).isEqualTo(401);

        assertThat(admin("/_stub/close", "{\"code\":4999}").status).isEqualTo(400);
        assertThat(admin("/_stub/close", "{\"code\":1000}").status).as("no live connection").isEqualTo(409);
    }

    @Test
    void sendBuildsEveryDownTypeFromItsVendoredFixture() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "link", "events:players");
        JsonObject create = frame("player.link.create", "auth-plugin-not-registered.json");
        client.send(create);
        assertThat(stub.gateway().awaitReceived("player.link.create", 1, 5000)).hasSize(1);
        int sent = 0;
        for (UltiCloudStub.MessageType type : stub.contract().messages()) {
            if (!type.down() || "credential.rotate".equals(type.type())) {
                continue;
            }
            HttpReply reply = admin("/_stub/send", "{\"type\":\"" + type.type() + "\"}");
            assertThat(reply.status).as(type.type() + " " + reply.body).isEqualTo(200);
            JsonObject frame = client.nextFrame(5000);
            assertThat(frame.get("type").getAsString()).isEqualTo(type.type());
            ContractSchemas.assertFrameValid(frame);
            sent++;
        }
        assertThat(sent).isGreaterThan(20);
        assertThat(stub.gateway().sent("player.link.created").get(0).get("replyTo").getAsString())
                .isEqualTo(create.get("id").getAsString());
    }

    @Test
    void sendWithAFixedIdReplaysTheSameEnvelopeAndOverridesThePayload() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "commands");
        String id = UltiCloudStub.Contract.newUlid();
        String body = "{\"type\":\"command.execute\",\"id\":\"" + id + "\",\"payload\":{\"command\":\"list\"}}";
        assertThat(admin("/_stub/send", body).status).isEqualTo(200);
        assertThat(admin("/_stub/send", body).status).isEqualTo(200);
        JsonObject first = client.nextFrame(5000);
        JsonObject second = client.nextFrame(5000);
        assertThat(first.get("id").getAsString()).isEqualTo(id);
        assertThat(second.get("id").getAsString()).isEqualTo(id);
        assertThat(first.getAsJsonObject("payload").get("command").getAsString()).isEqualTo("list");

        String explicit = UltiCloudStub.Contract.newUlid();
        assertThat(admin("/_stub/send", "{\"type\":\"player.link.expired\",\"replyTo\":\"" + explicit + "\"}").status)
                .isEqualTo(200);
        assertThat(client.nextFrame(5000).get("replyTo").getAsString()).isEqualTo(explicit);
    }

    @Test
    void sendRefusesWhatTheContractWouldNotCarry() throws Exception {
        assertThat(admin("/_stub/send", "{\"type\":\"command.execute\"}").status).as("no connection").isEqualTo(409);
        Login login = login();
        AgentClient client = connectAndHello(login, "commands");
        HttpReply invalid = admin("/_stub/send", "{\"type\":\"command.execute\",\"payload\":{\"command\":123}}");
        assertThat(invalid.status).isEqualTo(400);
        assertThat(invalid.body).contains("command");
        assertThat(admin("/_stub/send", "{\"type\":\"auth_complete\"}").status).isEqualTo(400);
        assertThat(admin("/_stub/send", "{\"type\":\"player.join\"}").status).as("an up-only type").isEqualTo(400);
        assertThat(admin("/_stub/send", "{\"type\":\"player.link.created\"}").status)
                .as("no player.link.create to reply to").isEqualTo(400);
        assertThat(admin("/_stub/send", "{\"type\":\"command.execute\",\"fixture\":\"nope\"}").status).isEqualTo(400);
        assertThat(client.pollText(300)).as("nothing invalid reached the plugin").isNull();
    }

    @Test
    void welcomeOverrideChangesTheNextWelcomeOnly() throws Exception {
        Login login = login();
        String other = UltiCloudStub.Contract.newUlid();
        assertThat(admin("/_stub/welcome", "{\"serverId\":\"" + other + "\"}").status).isEqualTo(200);
        AgentClient first = connect(login.credential);
        first.send(hello());
        assertThat(first.nextFrame(5000).getAsJsonObject("payload").get("serverId").getAsString()).isEqualTo(other);
        AgentClient second = connect(login.credential);
        second.send(hello());
        assertThat(second.nextFrame(5000).getAsJsonObject("payload").get("serverId").getAsString())
                .isEqualTo(login.serverId);
        assertThat(admin("/_stub/welcome", "{\"serverId\":\"not-a-ulid\"}").status).isEqualTo(400);
    }

    @Test
    void ackSwitchStopsAndResumesAcknowledging() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "events:players");
        assertThat(admin("/_stub/ack", "{\"enabled\":false}").status).isEqualTo(200);
        JsonObject unacked = frame("player.join", "basic.json");
        client.send(unacked);
        assertThat(client.pollText(1200)).isNull();
        assertThat(stub.accessLog().count("WS-NOTE conn=1 " + unacked.get("id").getAsString())).isEqualTo(1);
        assertThat(admin("/_stub/ack", "{\"enabled\":true}").status).isEqualTo(200);
        JsonObject acked = frame("player.leave", "basic.json");
        client.send(acked);
        assertThat(client.nextFrame(1000).getAsJsonObject("payload").getAsJsonArray("ids"))
                .containsExactly(acked.get("id"));
        client.send(acked);
        assertThat(client.nextFrame(1000).getAsJsonObject("payload").getAsJsonArray("ids"))
                .as("a redelivered id is acknowledged again").containsExactly(acked.get("id"));
        assertThat(stub.accessLog().count("WS-IN conn=1 player.leave id=" + acked.get("id").getAsString() + " duplicate"))
                .isEqualTo(1);
    }

    @Test
    void deviceActionsDecideTheNextPoll() throws Exception {
        assertThat(admin("/_stub/device", "{\"action\":\"hold\"}").status).isEqualTo(200);
        String held = authorize();
        assertThat(tokenCode(held)).isEqualTo("authorization_pending");
        assertThat(tokenCode(held)).isEqualTo("authorization_pending");
        assertThat(admin("/_stub/device", "{\"action\":\"slow_down\"}").status).isEqualTo(200);
        assertThat(tokenCode(held)).isEqualTo("slow_down");
        assertThat(tokenCode(held)).isEqualTo("authorization_pending");
        assertThat(admin("/_stub/device", "{\"action\":\"approve\"}").status).isEqualTo(200);
        assertThat(token(held).status).isEqualTo(200);
        assertThat(tokenCode(held)).as("a device code is used once").isEqualTo("expired_token");

        String denied = authorize();
        assertThat(admin("/_stub/device", "{\"action\":\"deny\"}").status).isEqualTo(200);
        assertThat(tokenCode(denied)).isEqualTo("access_denied");

        String expired = authorize();
        assertThat(admin("/_stub/device", "{\"action\":\"expire\"}").status).isEqualTo(200);
        assertThat(tokenCode(expired)).isEqualTo("expired_token");

        assertThat(admin("/_stub/device", "{\"action\":\"auto\"}").status).isEqualTo(200);
        String auto = authorize();
        assertThat(tokenCode(auto)).isEqualTo("authorization_pending");
        assertThat(token(auto).status).isEqualTo(200);
        assertThat(admin("/_stub/device", "{\"action\":\"maybe\"}").status).isEqualTo(400);
    }

    @Test
    void revokeDownResetsTheConnectionAndRevokeUpRevokesOnce() throws Exception {
        Login login = login();
        Map<String, String> bearer = new LinkedHashMap<>();
        bearer.put("Authorization", "Bearer " + login.credential);
        assertThat(admin("/_stub/revoke", "{\"state\":\"down\"}").status).isEqualTo(200);
        assertThatThrownBy(() -> RawHttp.post(stub.httpPort(), "/v1/agent/credential/revoke", null, bearer))
                .isInstanceOf(IOException.class);
        assertThat(stub.accessLog().count("TCP-RESET POST /v1/agent/credential/revoke")).isEqualTo(1);

        assertThat(admin("/_stub/revoke", "{\"state\":\"up\"}").status).isEqualTo(200);
        HttpReply revoked = RawHttp.post(stub.httpPort(), "/v1/agent/credential/revoke", null, bearer);
        assertThat(revoked.status).isEqualTo(200);
        ContractSchemas.assertRestBodyValid("credentialRevoke.response.200.schema.json", revoked.json());
        HttpReply again = RawHttp.post(stub.httpPort(), "/v1/agent/credential/revoke", null, bearer);
        assertThat(again.status).isEqualTo(401);
        ContractSchemas.assertRestBodyValid("credentialRevoke.error.401.schema.json", again.json());
        assertThat(upgradeRaw(login.credential, "2", FRAMEWORK).status).isEqualTo(401);
    }

    @Test
    void catalogueModesAreForwardedAndTheCatalogueIsServedOnThePublicPort() throws Exception {
        assertThat(admin("/_stub/catalogue", "{\"mode\":\"tamper_hash\"}").status).isEqualTo(200);
        assertThat(stub.catalogue().mode()).isEqualTo(StubCatalogue.Mode.TAMPER_HASH);
        assertThat(admin("/_stub/catalogue", "{\"mode\":\"NORMAL\"}").status).isEqualTo(200);
        assertThat(stub.catalogue().mode()).isEqualTo(StubCatalogue.Mode.NORMAL);
        assertThat(admin("/_stub/catalogue", "{\"mode\":\"sideways\"}").status).isEqualTo(400);
        HttpReply versions = RawHttp.get(stub.httpPort(),
                "/v1/catalogue/modules/" + StubCatalogue.MODULE + "/versions/" + StubCatalogue.VERSION, null);
        assertThat(versions.status).isEqualTo(200);
        assertThat(versions.json().getAsJsonObject("data").get("sha256").getAsString())
                .isEqualTo(stub.catalogue().servedSha256());
    }

    @Test
    void v1PathsAnswer410AndAreLogged() throws Exception {
        assertThat(RawHttp.get(stub.httpPort(), "/plugin/list", null).status).isEqualTo(410);
        assertThat(RawHttp.post(stub.httpPort(), "/auth/magic-link", "{}", null).status).isEqualTo(410);
        Map<String, String> upgrade = new LinkedHashMap<>();
        upgrade.put("Upgrade", "websocket");
        upgrade.put("Connection", "Upgrade");
        assertThat(RawHttp.get(stub.httpPort(), "/ws", upgrade).status).isEqualTo(410);
        assertThat(stub.accessLog().count(" V1 ")).isEqualTo(3);
        assertThat(stub.catalogue().paths()).as("v1 never reaches the catalogue").isEmpty();
    }

    @Test
    void helloMustComeFirstAndBeValid() throws Exception {
        Login login = login();
        AgentClient early = connect(login.credential);
        early.send(frame("status", "basic.json"));
        assertThat(early.awaitClose(5000)).isEqualTo(4400);

        AgentClient invalid = connect(login.credential);
        JsonObject hello = hello();
        hello.getAsJsonObject("payload").addProperty("resume", true);
        invalid.send(hello);
        assertThat(invalid.awaitClose(5000)).isEqualTo(4400);
        assertThat(stub.accessLog().lines()).anySatisfy(line -> assertThat(line)
                .contains("WS-INVALID conn=2 hello").contains("resume"));
    }

    @Test
    void noHelloWithinTheTimeoutClosesWith4408() throws Exception {
        stub.close();
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().helloTimeoutMs(300));
        Login login = login();
        AgentClient silent = connect(login.credential);
        assertThat(silent.awaitClose(5000)).isEqualTo(4408);
    }

    @Test
    void invalidFramesAfterWelcomeAreAnsweredAndTooManyCloseWith4429() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "events:players");
        JsonObject broken = frame("player.join", "basic.json");
        broken.getAsJsonObject("payload").remove("playerName");
        client.send(broken);
        JsonObject error = client.nextFrame(5000);
        ContractSchemas.assertFrameValid(error);
        assertThat(error.get("replyTo").getAsString()).isEqualTo(broken.get("id").getAsString());
        assertThat(error.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        assertThat(error.getAsJsonObject("payload").get("message").getAsString()).contains("playerName");

        JsonObject unknown = frame("player.join", "basic.json");
        unknown.addProperty("type", "auth_complete");
        client.send(unknown);
        assertThat(client.nextFrame(5000).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("unsupported_type");

        JsonObject notAccepted = frame("status", "basic.json");
        client.send(notAccepted);
        JsonObject refused = client.nextFrame(5000);
        assertThat(refused.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("capability_not_accepted");
        assertThat(refused.getAsJsonObject("payload").getAsJsonObject("details").get("capability").getAsString())
                .isEqualTo("telemetry");

        for (int i = 0; i < 20; i++) {
            client.sendText("not json " + i);
        }
        assertThat(client.awaitClose(5000)).isEqualTo(4429);
    }

    @Test
    void aNewConnectionForTheSameServerSupersedesTheOldOne() throws Exception {
        Login login = login();
        AgentClient first = connectAndHello(login, "events:players");
        connectAndHello(login, "events:players");
        assertThat(first.awaitClose(5000)).isEqualTo(4000);
    }

    @Test
    void anAcknowledgedRotationRetiresTheOldCredential() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login);
        assertThat(admin("/_stub/send", "{\"type\":\"credential.rotate\"}").status).isEqualTo(200);
        JsonObject rotate = client.nextFrame(5000);
        String rotated = rotate.getAsJsonObject("payload").get("credential").getAsString();
        JsonObject ack = frame("credential.rotate.ack", "ack.json");
        ack.addProperty("replyTo", rotate.get("id").getAsString());
        client.send(ack);
        assertThat(stub.accessLog().await("retired by credential.rotate.ack", 5000)).isTrue();
        assertThat(upgradeRaw(login.credential, "2", FRAMEWORK).status).isEqualTo(401);
        AgentClient next = connect(rotated);
        next.send(hello());
        assertThat(next.nextFrame(5000).getAsJsonObject("payload").get("serverId").getAsString())
                .isEqualTo(login.serverId);
        assertThat(stub.accessLog().text()).doesNotContain(rotated);
    }

    @Test
    void anAcceptedCredentialConnectsWithItsServerId() throws Exception {
        String credential = "ucs_" + UltiCloudStub.Contract.randomToken(32);
        String serverId = UltiCloudStub.Contract.newUlid();
        assertThat(admin("/_stub/credential",
                "{\"credential\":\"" + credential + "\",\"serverId\":\"" + serverId + "\"}").status).isEqualTo(200);
        AgentClient client = connect(credential);
        client.send(hello());
        assertThat(client.nextFrame(5000).getAsJsonObject("payload").get("serverId").getAsString()).isEqualTo(serverId);
        assertThat(admin("/_stub/credential", "{\"credential\":\"x\",\"serverId\":\"" + serverId + "\"}").status)
                .isEqualTo(400);
    }

    @Test
    void logFramesAndStatusAreReadableAndEveryActionIsLoggedOnce() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "logs");
        JsonObject lines = frame("log.lines", ContractFixtures.paths().stream()
                .filter(path -> path.startsWith("fixtures/agent/log.lines/valid/"))
                .map(path -> path.substring(path.lastIndexOf('/') + 1)).findFirst().get());
        lines.getAsJsonObject("payload").getAsJsonArray("lines").get(0).getAsJsonObject()
                .addProperty("message", "leaked " + login.credential);
        client.send(lines);
        assertThat(stub.gateway().awaitReceived("log.lines", 1, 5000)).hasSize(1);

        HttpReply frames = RawHttp.get(stub.adminPort(), "/_stub/frames?type=log.lines", null);
        assertThat(frames.status).isEqualTo(200);
        JsonArray array = JsonParser.parseString(frames.body).getAsJsonArray();
        assertThat(array).hasSize(1);
        assertThat(array.get(0).getAsJsonObject().getAsJsonObject("frame").get("id").getAsString())
                .isEqualTo(lines.get("id").getAsString());
        assertThat(frames.body).doesNotContain(login.credential).contains("ucs_***#");

        admin("/_stub/ack", "{\"enabled\":true}");
        HttpReply log = RawHttp.get(stub.adminPort(), "/_stub/log", null);
        assertThat(log.status).isEqualTo(200);
        assertThat(log.body).contains("WS-IN conn=1 log.lines").doesNotContain(login.credential);
        assertThat(stub.accessLog().count("ADMIN POST /_stub/ack")).isEqualTo(1);

        HttpReply status = RawHttp.get(stub.adminPort(), "/_stub/status", null);
        assertThat(status.status).isEqualTo(200);
        assertThat(status.json().getAsJsonArray("connections")).hasSize(1);
        assertThat(status.body).doesNotContain(login.credential);
        assertThat(RawHttp.get(stub.adminPort(), "/_stub", null).body).contains("/_stub/send");
        assertThat(RawHttp.get(stub.adminPort(), "/_stub/nothing", null).status).isEqualTo(404);
        assertThat(admin("/_stub/ack", "not json").status).isEqualTo(400);
    }

    // ------------------------------------------------------------------ contract boundaries (22-19 fix round, W2)

    @Test
    void exactlyTwentyRefusedFramesKeepTheConnectionAndTheTwentyFirstClosesWith4429() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "events:players");
        for (int i = 0; i < 20; i++) {
            client.sendText("not json " + i);
        }
        for (int i = 0; i < 20; i++) {
            JsonObject refused = client.nextFrame(5000);
            ContractSchemas.assertFrameValid(refused);
            assertThat(refused.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        }
        client.sendText("ping");
        assertThat(client.nextText(5000)).as("twenty refusals are within the limit").isEqualTo("pong");
        client.sendText("not json 21");
        assertThat(client.awaitClose(5000)).as("PROTOCOL.md: more than 20 in the window").isEqualTo(4429);
    }

    @Test
    void aBurstOfEventsIsAckedInSchemaValidBatchesOfAtMostAckBatchMax() throws Exception {
        Login login = login();
        AgentClient client = connectAndHello(login, "events:players");
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            JsonObject join = frame(i % 2 == 0 ? "player.join" : "player.leave", "basic.json");
            sent.add(join.get("id").getAsString());
            client.send(join);
        }
        java.util.Set<String> acked = new java.util.HashSet<>();
        int acks = 0;
        int largest = 0;
        long deadline = System.currentTimeMillis() + 10000;
        while (acked.size() < sent.size() && System.currentTimeMillis() < deadline) {
            String text = client.pollText(2000);
            if (text == null) {
                break;
            }
            JsonObject ack = JsonParser.parseString(text).getAsJsonObject();
            assertThat(ack.get("type").getAsString()).isEqualTo("ack");
            ContractSchemas.assertFrameValid(ack);
            JsonArray ids = ack.getAsJsonObject("payload").getAsJsonArray("ids");
            largest = Math.max(largest, ids.size());
            acks++;
            for (int i = 0; i < ids.size(); i++) {
                acked.add(ids.get(i).getAsString());
            }
        }
        assertThat(acked).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(largest).as("ack.batchMax in PROTOCOL.md section 9").isLessThanOrEqualTo(100);
        assertThat(acks).isGreaterThanOrEqualTo(6);
        assertThat(stub.accessLog().count("WS-SELF-INVALID")).isZero();
    }

    @Test
    void aForced402IsAppliedOnlyAfterTheCredentialStep() throws Exception {
        Login login = login();
        assertThat(admin("/_stub/mode", "{\"refusal\":402}").status).isEqualTo(200);
        HttpReply unknown = upgradeRaw("ucs_" + repeat('C', 43), "2", FRAMEWORK);
        assertThat(unknown.status).as("an unknown credential is refused before the plan").isEqualTo(401);
        ContractSchemas.assertRestBodyValid("agentUpgrade.error.401.schema.json", unknown.json());
        assertThat(upgradeRaw(login.credential, "2", FRAMEWORK).status).isEqualTo(402);
    }

    @Test
    void aFrameOverMaxFrameBytesIsAnsweredPayloadTooLargeAndTheConnectionStays() throws Exception {
        int maxFrameBytes = ContractFixtures.object("fixtures/agent/welcome/valid/default-limits.json")
                .getAsJsonObject("payload").getAsJsonObject("limits").get("maxFrameBytes").getAsInt();
        Login login = login();
        AgentClient client = connectAndHello(login, "logs");
        client.sendText(repeat('x', maxFrameBytes + 1));
        JsonObject refused = client.nextFrame(5000);
        ContractSchemas.assertFrameValid(refused);
        assertThat(refused.get("type").getAsString()).isEqualTo("error");
        assertThat(refused.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("payload_too_large");
        client.sendText("ping");
        assertThat(client.nextText(5000)).isEqualTo("pong");
    }

    @Test
    void aJsonAnswerLeavesTheFrontDoorAsOneWriteOfHeadAndBody() throws Exception {
        // A UAT run must not depend on TCP segmentation: refusals and the v1 410 are one write.
        final List<byte[]> writes = new ArrayList<>();
        OutputStream recorder = new OutputStream() {
            @Override
            public void write(int b) {
                writes.add(new byte[]{(byte) b});
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writes.add(java.util.Arrays.copyOfRange(b, off, off + len));
            }
        };
        JsonObject body = ContractFixtures.object("fixtures/agent-rest/agentUpgrade/error/valid/402-plan-limit.json");
        StubFrontDoor.writeJson(recorder, 402, "Payment Required", body, null);
        assertThat(writes).hasSize(1);
        HttpReply parsed = RawHttp.parse(writes.get(0));
        assertThat(parsed.status).isEqualTo(402);
        assertThat(parsed.json()).isEqualTo(body);
    }

    @Test
    void everyV1PrefixAnswers410AndIsLoggedV1() throws Exception {
        String[] paths = {
            "/ws", "/ws/server/x/log/stream",
            "/auth/magic-link/poll", "/auth/server-login/status",
            "/editor/register", "/editor/updateServer",
            "/server/getByUUID",
            "/oauth/token",
            "/plugin/ultitools/newest",
        };
        for (String path : paths) {
            assertThat(RawHttp.get(stub.httpPort(), path, null).status).as(path).isEqualTo(410);
        }
        assertThat(stub.accessLog().count(" V1 ")).isEqualTo(paths.length);
        for (String path : paths) {
            assertThat(stub.accessLog().lines()).as(path)
                    .anySatisfy(line -> assertThat(line).contains("V1 GET " + path + " -> 410"));
        }
        // Control: an unknown v2 path is a plain 404, not a v1 answer.
        assertThat(RawHttp.get(stub.httpPort(), "/v1/nothing", null).status).isEqualTo(404);
        assertThat(stub.accessLog().count(" V1 ")).isEqualTo(paths.length);
    }

    // ------------------------------------------------------------------ Task 3: the standalone command

    @Test
    void helpPrintsTheUsageTheAdminEndpointsAndTheStartCommand() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(UltiCloudStub.run(new String[]{"--help"}, new java.io.PrintStream(out, true),
                new java.io.PrintStream(new ByteArrayOutputStream(), true))).isZero();
        assertThat(out.toString("UTF-8"))
                .contains("--http-port").contains("--admin-port").contains("--access-log").contains("--fixtures-root")
                .contains("dependency:build-classpath").contains("com.ultikits.ultitools.stub.UltiCloudStub")
                .contains("-Dultitools.api.url=")
                .contains("POST /_stub/send").contains("GET  /_stub/frames");
        assertThat(UltiCloudStub.run(new String[]{"--bogus"}, new java.io.PrintStream(new ByteArrayOutputStream(), true),
                new java.io.PrintStream(new ByteArrayOutputStream(), true))).isEqualTo(2);
    }

    @Test
    void theCommandPrintsOneLinePerPortAndRunsUntilInterrupted() throws Exception {
        stub.close();
        stub = null;
        java.io.PipedOutputStream pipe = new java.io.PipedOutputStream();
        java.io.BufferedReader lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.PipedInputStream(pipe, 65536), StandardCharsets.UTF_8));
        Path log = temp.resolve("standalone.log");
        int[] exit = {-1};
        Thread command = new Thread(() -> exit[0] = UltiCloudStub.run(new String[]{
            "--http-port", "0", "--admin-port", "0", "--access-log", log.toString(),
            "--fixtures-root", "src/test/resources/" + ContractFixtures.ROOT},
                new java.io.PrintStream(pipe, true), new java.io.PrintStream(new ByteArrayOutputStream(), true)));
        command.start();
        String http = lines.readLine();
        String admin = lines.readLine();
        assertThat(http).matches("UltiCloudStub http +listening on 127\\.0\\.0\\.1:\\d+ .*-Dultitools\\.api\\.url=http://127\\.0\\.0\\.1:\\d+.*");
        assertThat(admin).matches("UltiCloudStub admin +listening on 127\\.0\\.0\\.1:\\d+ .*");
        int httpPort = Integer.parseInt(http.replaceAll(".*127\\.0\\.0\\.1:(\\d+) .*", "$1"));
        int adminPort = Integer.parseInt(admin.replaceAll(".*127\\.0\\.0\\.1:(\\d+) .*", "$1"));
        assertThat(RawHttp.get(adminPort, "/_stub", null).status).isEqualTo(200);
        assertThat(RawHttp.get(httpPort, "/v1/catalogue/framework", null).status).isEqualTo(200);
        assertThat(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))
                .contains("contract=" + java.nio.file.Paths.get("src/test/resources/" + ContractFixtures.ROOT).toAbsolutePath());

        command.interrupt();
        command.join(10_000);
        assertThat(command.isAlive()).isFalse();
        assertThat(exit[0]).isZero();
        assertThat(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)).contains("STUB stopped");
    }

    // ------------------------------------------------------------------ helpers

    private Login login() throws IOException {
        HttpReply authorize = RawHttp.post(stub.httpPort(), "/v1/device/authorize",
                ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/request/valid/server.json"), null);
        assertThat(authorize.status).isEqualTo(200);
        ContractSchemas.assertRestBodyValid("deviceAuthorize.response.200.schema.json", authorize.json());
        String deviceCode = authorize.json().getAsJsonObject("data").get("deviceCode").getAsString();
        JsonObject poll = new JsonObject();
        poll.addProperty("deviceCode", deviceCode);

        HttpReply pending = RawHttp.post(stub.httpPort(), "/v1/device/token", poll.toString(), null);
        assertThat(pending.status).isEqualTo(400);
        ContractSchemas.assertRestBodyValid("deviceToken.error.400.schema.json", pending.json());
        assertThat(pending.json().getAsJsonObject("error").get("code").getAsString())
                .isEqualTo("authorization_pending");

        HttpReply issued = RawHttp.post(stub.httpPort(), "/v1/device/token", poll.toString(), null);
        assertThat(issued.status).isEqualTo(200);
        ContractSchemas.assertRestBodyValid("deviceToken.response.200.schema.json", issued.json());
        JsonObject data = issued.json().getAsJsonObject("data");
        assertThat(data.get("kind").getAsString()).isEqualTo("server");
        assertThat(data.get("credential").getAsString()).startsWith("ucs_");
        assertThat(data.get("serverId").getAsString()).matches(ULID);
        return new Login(data.get("credential").getAsString(), data.get("serverId").getAsString());
    }

    private AgentClient connect(String credential) throws InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + credential);
        headers.put("X-UltiCloud-Protocol", "2");
        headers.put("X-UltiCloud-Framework", FRAMEWORK);
        AgentClient client = new AgentClient(URI.create("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"), headers);
        clients.add(client);
        assertThat(client.connectBlocking(5, TimeUnit.SECONDS)).as("the upgrade is accepted").isTrue();
        return client;
    }

    /** The vendored basic hello with a fresh id, declaring exactly {@code capabilities}. */
    static JsonObject hello(String... capabilities) {
        JsonObject hello = frame("hello", "basic.json");
        JsonArray declared = new JsonArray();
        for (String capability : capabilities) {
            declared.add(capability);
        }
        hello.getAsJsonObject("payload").add("capabilities", declared);
        return hello;
    }

    /** Connects with {@code login}'s credential, says hello and consumes the welcome. */
    private AgentClient connectAndHello(Login login, String... capabilities) throws InterruptedException {
        AgentClient client = connect(login.credential);
        JsonObject hello = hello(capabilities);
        client.send(hello);
        JsonObject welcome = client.nextFrame(5000);
        assertThat(welcome.get("type").getAsString()).isEqualTo("welcome");
        assertThat(welcome.get("replyTo").getAsString()).isEqualTo(hello.get("id").getAsString());
        return client;
    }

    private HttpReply admin(String path, String body) throws IOException {
        return RawHttp.post(stub.adminPort(), path, body, null);
    }

    /** A raw upgrade request with the given headers ({@code null} leaves one out); for refusals only. */
    private HttpReply upgradeRaw(String credential, String protocol, String framework) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Upgrade", "websocket");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.put("Sec-WebSocket-Version", "13");
        if (credential != null) {
            headers.put("Authorization", "Bearer " + credential);
        }
        if (protocol != null) {
            headers.put("X-UltiCloud-Protocol", protocol);
        }
        if (framework != null) {
            headers.put("X-UltiCloud-Framework", framework);
        }
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), stub.httpPort())) {
            socket.setSoTimeout(5000);
            StringBuilder head = new StringBuilder("GET /v1/agent HTTP/1.1\r\nHost: 127.0.0.1\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            socket.getOutputStream().write(head.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[65536];
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int read;
            try {
                while ((read = socket.getInputStream().read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                    String sofar = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
                    if (sofar.startsWith("HTTP/1.1 101")) {
                        return new HttpReply(101, new HashMap<String, String>(), "");
                    }
                }
            } catch (java.net.SocketTimeoutException stillOpen) {
                // An accepted upgrade keeps the socket open; the status line was already checked.
            }
            return RawHttp.parse(bytes.toByteArray());
        }
    }

    private String authorize() throws IOException {
        HttpReply authorize = RawHttp.post(stub.httpPort(), "/v1/device/authorize",
                ContractFixtures.text("fixtures/agent-rest/deviceAuthorize/request/valid/server.json"), null);
        assertThat(authorize.status).isEqualTo(200);
        return authorize.json().getAsJsonObject("data").get("deviceCode").getAsString();
    }

    private HttpReply token(String deviceCode) throws IOException {
        JsonObject poll = new JsonObject();
        poll.addProperty("deviceCode", deviceCode);
        return RawHttp.post(stub.httpPort(), "/v1/device/token", poll.toString(), null);
    }

    private String tokenCode(String deviceCode) throws IOException {
        HttpReply reply = token(deviceCode);
        assertThat(reply.status).as(reply.body).isEqualTo(400);
        ContractSchemas.assertRestBodyValid("deviceToken.error.400.schema.json", reply.json());
        return reply.json().getAsJsonObject("error").get("code").getAsString();
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(value);
        }
        return text.toString();
    }

    /** A vendored valid frame with a fresh id, so each send is a new message. */
    static JsonObject frame(String type, String fixture) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/" + type + "/valid/" + fixture);
        frame.addProperty("id", UltiCloudStub.Contract.newUlid());
        frame.addProperty("ts", System.currentTimeMillis());
        return frame;
    }

    private static void assertInOrder(List<String> lines, String... fragments) {
        int from = 0;
        for (String fragment : fragments) {
            int found = -1;
            for (int i = from; i < lines.size(); i++) {
                if (lines.get(i).contains(fragment)) {
                    found = i;
                    break;
                }
            }
            assertThat(found).as("'%s' after line %d in%n%s", fragment, from, String.join("\n", lines))
                    .isGreaterThanOrEqualTo(0);
            from = found + 1;
        }
    }

    static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Login {
        final String credential;
        final String serverId;

        Login(String credential, String serverId) {
            this.credential = credential;
            this.serverId = serverId;
        }
    }

    /** One answer read by {@link RawHttp}: status line, headers (lower-case names) and body. */
    static final class HttpReply {
        final int status;
        final Map<String, String> headers;
        final String body;

        HttpReply(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /** A minimal HTTP/1.1 client over a raw socket, so the test sees exactly what the stub wrote. */
    static final class RawHttp {
        private RawHttp() {
        }

        static HttpReply post(int port, String path, String body, Map<String, String> headers) throws IOException {
            return request(port, "POST", path, body, headers);
        }

        static HttpReply get(int port, String path, Map<String, String> headers) throws IOException {
            return request(port, "GET", path, null, headers);
        }

        static HttpReply request(int port, String method, String path, String body, Map<String, String> headers)
                throws IOException {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                }
            }
            if (headers == null || !headers.containsKey("Connection")) {
                // The admin server keeps connections alive; the answer is read to the end of stream.
                head.append("Connection: close\r\n");
            }
            if (body != null) {
                head.append("Content-Type: application/json\r\nContent-Length: ").append(bytes.length).append("\r\n");
            }
            head.append("\r\n");
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(10_000);
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(bytes);
                out.flush();
                return parse(readBytes(socket.getInputStream()));
            }
        }

        private static byte[] readBytes(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }

        static HttpReply parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            assertThat(end).as("a complete response head in %s", text).isGreaterThan(0);
            String[] lines = text.substring(0, end).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
            byte[] body = new byte[raw.length - end - 4];
            System.arraycopy(raw, end + 4, body, 0, body.length);
            return new HttpReply(status, headers, new String(body, StandardCharsets.UTF_8));
        }
    }

    /** A Java-WebSocket client that queues every text frame and records how the connection closed. */
    static final class AgentClient extends WebSocketClient {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile int closeCode = -1;
        private volatile String closeReason;

        AgentClient(URI uri, Map<String, String> headers) {
            super(uri, new Draft_6455(), headers, 5000);
            setConnectionLostTimeout(0);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            // Nothing to do: frames are queued as they arrive.
        }

        @Override
        public void onMessage(String message) {
            texts.add(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode = code;
            closeReason = reason;
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
            // The close callback carries the outcome the tests assert.
        }

        void send(JsonObject frame) {
            send(frame.toString());
        }

        void sendText(String text) {
            send(text);
        }

        String nextText(long timeoutMs) throws InterruptedException {
            String text = texts.poll(timeoutMs, TimeUnit.MILLISECONDS);
            assertThat(text).as("a text frame within %d ms", timeoutMs).isNotNull();
            return text;
        }

        String pollText(long timeoutMs) throws InterruptedException {
            return texts.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        JsonObject nextFrame(long timeoutMs) throws InterruptedException {
            return JsonParser.parseString(nextText(timeoutMs)).getAsJsonObject();
        }

        int awaitClose(long timeoutMs) throws InterruptedException {
            assertThat(closed.await(timeoutMs, TimeUnit.MILLISECONDS)).as("closed within %d ms", timeoutMs).isTrue();
            return closeCode;
        }

        String closeReason() {
            return closeReason;
        }

        JsonArray drainFrames() {
            JsonArray frames = new JsonArray();
            String text;
            while ((text = texts.poll()) != null) {
                if (text.startsWith("{")) {
                    frames.add(JsonParser.parseString(text));
                }
            }
            return frames;
        }
    }
}
