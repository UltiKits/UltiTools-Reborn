package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractFixtures.Fixture;

/**
 * The refused-upgrade parser against the vendored contract 2.0.0 fixtures
 * {@code fixtures/agent-rest/agentUpgrade/error/} (plan 22-20, S-44 narrowed by S-63). As PROTOCOL.md
 * section 2 states, each valid file name starts with the HTTP status it answers; an invalid file
 * without a status prefix must be refused by every status's schema, so it is parsed under all four.
 */
@DisplayName("UpgradeRejection parsing and sanitising")
class UpgradeRejectionTest {

    private static final int[] STATUSES = {401, 402, 426, 429};

    static List<Fixture> validSamples() {
        return ContractFixtures.restBodies("agentUpgrade", "error", true);
    }

    static List<Fixture> invalidSamples() {
        return ContractFixtures.restBodies("agentUpgrade", "error", false);
    }

    /** @return the HTTP status a fixture's file name starts with, or -1 when it has none */
    static int statusOf(Fixture fixture) {
        String name = fixture.path().substring(fixture.path().lastIndexOf('/') + 1);
        return name.matches("\\d{3}-.*") ? Integer.parseInt(name.substring(0, 3)) : -1;
    }

    private static UpgradeRejection parse(int status, Fixture fixture) {
        return UpgradeRejection.parse(status, Collections.emptyMap(), fixture.body().toString());
    }

    private static void assertStatusOnly(UpgradeRejection r, int status) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.code()).isNull();
        assertThat(r.message()).isNull();
        assertThat(r.requestId()).isNull();
        assertThat(r.minFramework()).isNull();
        assertThat(r.supportedProtocols()).isEmpty();
        assertThat(r.retryAfterSeconds()).isEqualTo(-1);
        assertThat(r.planReason()).isNull();
        assertThat(r.graceEndedAt()).isNull();
        assertThat(r.manageUrl()).isNull();
    }

    @Test
    @DisplayName("the vendored directories hold the four valid and six invalid bodies (control)")
    void fixtureDirectoriesAreComplete() {
        assertThat(validSamples()).extracting(UpgradeRejectionTest::statusOf).containsExactlyInAnyOrder(401, 402, 426, 429);
        assertThat(invalidSamples()).hasSize(6);
        assertThat(java.nio.file.Paths.get("src/test/resources/ulticloud-contract/v2/2.0.0")).as("control").isDirectory();
        assertThat(java.nio.file.Paths.get("src/test/resources/ulticloud-contract/provisional"))
                .as("the Wave A stand-in samples are gone").doesNotExist();
    }

    @ParameterizedTest(name = "valid {0}")
    @MethodSource("validSamples")
    void validSampleParses(Fixture sample) {
        int status = statusOf(sample);
        JsonObject error = sample.body().getAsJsonObject("error");
        UpgradeRejection r = parse(status, sample);
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.code()).isEqualTo(error.get("code").getAsString());
        assertThat(r.message()).isNotNull();
        assertThat(r.requestId()).isEqualTo("req_01");
        if (status == 426) {
            assertThat(r.minFramework()).isEqualTo("6.3.1");
            assertThat(r.supportedProtocols()).containsExactly(2);
        }
        if (status == 402) {
            assertThat(r.planReason()).isEqualTo("pro_expired_over_limit");
            assertThat(r.graceEndedAt()).isEqualTo("2026-11-01T00:00:00.000Z");
            assertThat(r.manageUrl()).isEqualTo("https://panel.ultikits.com/owner/billing");
        } else {
            assertThat(r.planReason()).isNull();
            assertThat(r.manageUrl()).isNull();
        }
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("invalidSamples")
    @DisplayName("an invalid body never throws and never yields a field the contract forbids")
    void invalidSampleIsHarmless(Fixture sample) {
        int prefix = statusOf(sample);
        for (int status : prefix > 0 ? new int[]{prefix} : STATUSES) {
            assertThatCode(() -> parse(status, sample)).doesNotThrowAnyException();
            UpgradeRejection r = parse(status, sample);
            assertThat(r.status()).isEqualTo(status);
            assertThat(r.retryAfterSeconds()).as("Retry-After only comes from the header").isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("402 with top-level fields (not wrapped in error) is a status-only rejection")
    void topLevelPlanFieldsAreStatusOnly() {
        Fixture sample = invalid("402-top-level-fields.json");
        assertStatusOnly(parse(402, sample), 402);
    }

    @Test
    @DisplayName("402 with an http manageUrl keeps every field but the link")
    void httpManageUrlIsDropped() {
        UpgradeRejection r = parse(402, invalid("402-http-manage-url.json"));
        assertThat(r.manageUrl()).isNull();
        assertThat(r.planReason()).isEqualTo("pro_expired_over_limit");
        assertThat(r.code()).isEqualTo("plan_limit");
    }

    @Test
    @DisplayName("426 without details carries no minimum version")
    void upgradeWithoutDetails() {
        UpgradeRejection r = parse(426, invalid("426-missing-details.json"));
        assertThat(r.code()).isEqualTo("upgrade_required");
        assertThat(r.minFramework()).isNull();
        assertThat(r.supportedProtocols()).isEmpty();
    }

    private static Fixture invalid(String name) {
        return invalidSamples().stream().filter(f -> f.path().endsWith("/" + name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing fixture " + name));
    }

    @ParameterizedTest(name = "generated body #{index}")
    @ValueSource(strings = {
            "not json",
            "null",
            "[1,2,3]",
            "{\"error\":\"upgrade_required\"}",
            "{\"error\":{\"code\":true,\"message\":5,\"requestId\":{},\"details\":[]}}",
            "{\"error\":{",
            ""
    })
    void malformedBodiesAreStatusOnly(String body) {
        assertThatCode(() -> UpgradeRejection.parse(426, Collections.emptyMap(), body)).doesNotThrowAnyException();
        assertStatusOnly(UpgradeRejection.parse(426, Collections.emptyMap(), body), 426);
    }

    @Test
    @DisplayName("a null body and a 9 KiB body are status-only")
    void absentAndOversizedBodies() {
        assertStatusOnly(UpgradeRejection.parse(401, null, null), 401);
        StringBuilder big = new StringBuilder("{\"error\":{\"code\":\"invalid_credential\",\"message\":\"");
        while (big.length() < 9 * 1024) {
            big.append('x');
        }
        big.append("\",\"requestId\":\"req_01\"}}");
        assertStatusOnly(UpgradeRejection.parse(401, Collections.emptyMap(), big.toString()), 401);
    }

    private static String planBody(String manageUrl) {
        return "{\"error\":{\"code\":\"plan_limit\",\"message\":\"m\",\"requestId\":\"req_01\",\"details\":{"
                + "\"reason\":\"pro_expired_over_limit\",\"graceEndedAt\":\"2026-11-01T00:00:00.000Z\","
                + "\"manageUrl\":\"" + manageUrl + "\"}}}";
    }

    @ParameterizedTest(name = "manageUrl {0} is dropped")
    @ValueSource(strings = {
            "http://panel.ultikits.com/x",
            "https://evil.example/x",
            "https://panel.ultikits.com.evil.example/x",
            "https://panel.ultikits.com/a b",
            "https://user@panel.ultikits.com/x",
            "https://notultikits.com/x"
    })
    void hostileManageUrlIsDropped(String url) {
        UpgradeRejection r = UpgradeRejection.parse(402, null, planBody(url));
        assertThat(r.manageUrl()).isNull();
        assertThat(r.planReason()).as("other fields survive").isEqualTo("pro_expired_over_limit");
    }

    @Test
    @DisplayName("manageUrl on ultikits.com or a subdomain is kept")
    void ownManageUrlIsKept() {
        assertThat(UpgradeRejection.parse(402, null, planBody("https://ultikits.com/b")).manageUrl())
                .isEqualTo("https://ultikits.com/b");
        assertThat(UpgradeRejection.parse(402, null, planBody("https://panel.ultikits.com/owner/billing")).manageUrl())
                .isEqualTo("https://panel.ultikits.com/owner/billing");
    }

    @Test
    @DisplayName("message loses ANSI escapes, control characters and colour codes and is cut to 200 characters")
    void messageIsSanitised() {
        String body = "{\"error\":{\"code\":\"upgrade_required\",\"message\":\"\\u001b[31mred\\u001b[0m \\u00a7cbold\\u0007\\n"
                + "x\",\"requestId\":\"req_01\"}}";
        assertThat(UpgradeRejection.parse(426, null, body).message()).isEqualTo("red cboldx");

        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longMessage.append('a');
        }
        String longBody = "{\"error\":{\"code\":\"rate_limited\",\"message\":\"" + longMessage + "\"}}";
        assertThat(UpgradeRejection.parse(429, null, longBody).message()).hasSize(200);
    }

    @Test
    @DisplayName("a requestId with a newline, and a code outside the character class, are dropped")
    void tokenFieldsAreSanitised() {
        String body = "{\"error\":{\"code\":\"upgrade required\",\"message\":\"m\",\"requestId\":\"req\\n01\"}}";
        UpgradeRejection r = UpgradeRejection.parse(426, null, body);
        assertThat(r.requestId()).isNull();
        assertThat(r.code()).isNull();
        assertThat(r.message()).isEqualTo("m");
    }

    @Test
    @DisplayName("the 402 fields are read only from error.details (S-63): wrapped in error or at the top level they are ignored")
    void planFieldsOnlyFromDetails() {
        String inError = "{\"error\":{\"code\":\"plan_limit\",\"reason\":\"pro_expired_over_limit\","
                + "\"graceEndedAt\":\"2026-11-01T00:00:00.000Z\",\"manageUrl\":\"https://panel.ultikits.com/b\"}}";
        UpgradeRejection wrapped = UpgradeRejection.parse(402, null, inError);
        assertThat(wrapped.code()).isEqualTo("plan_limit");
        assertThat(wrapped.planReason()).isNull();
        assertThat(wrapped.graceEndedAt()).isNull();
        assertThat(wrapped.manageUrl()).isNull();
        String topLevel = "{\"code\":\"plan_limit\",\"reason\":\"pro_expired_over_limit\","
                + "\"graceEndedAt\":\"2026-11-01T00:00:00.000Z\",\"manageUrl\":\"https://panel.ultikits.com/b\"}";
        assertStatusOnly(UpgradeRejection.parse(402, null, topLevel), 402);
        String badTime = planBody("https://panel.ultikits.com/b").replace("2026-11-01T00:00:00.000Z", "tomorrow");
        assertThat(UpgradeRejection.parse(402, null, badTime).graceEndedAt()).isNull();
    }

    @ParameterizedTest(name = "Retry-After {0} -> {1}")
    @CsvSource({
            "30, 30",
            "0, 0",
            "99999, 3600",
            "99999999999999999999, 3600",
            "'Wed, 21 Oct 2026 07:28:00 GMT', -1",
            "-1, -1",
            "' 45 ', 45"
    })
    void retryAfter(String header, long expected) {
        Map<String, String> headers = new HashMap<>();
        headers.put("retry-after", header);
        assertThat(UpgradeRejection.parse(429, headers, null).retryAfterSeconds()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an absent Retry-After is -1")
    void retryAfterAbsent() {
        assertThat(UpgradeRejection.parse(429, Collections.emptyMap(), null).retryAfterSeconds()).isEqualTo(-1);
    }

    @ParameterizedTest(name = "JSON protocol {0} -> {1}")
    @CsvSource({"1, 1", "99, 99", "2.0, 2", "2e0, 2"})
    void jsonProtocolsAcceptEveryIntegerSpellingInRange(String json, int expected) {
        assertThat(UpgradeRejection.parse(426, null, protocolsBody(json)).supportedProtocols())
                .containsExactly(expected);
    }

    @ParameterizedTest(name = "out-of-contract JSON protocol #{index} is dropped")
    @ValueSource(strings = {"0", "-1", "100", "1000", "2.5", "2.0000000000000001", "\"2\"", "true",
            "null", "{}", "[]", "4294967298", "1e100", "1e-100", "1e9999999999"})
    void jsonProtocolsDoNotCoerceTruncateOrOverflow(String json) {
        assertThat(UpgradeRejection.parse(426, null, protocolsBody(json)).supportedProtocols()).isEmpty();
    }

    @Test
    void protocolHeaderKeepsItsSeparateDigitOnlyDiagnosticPolicy() {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-ulticloud-protocol-supported", "2, 1000, 2.0, 2e0, 1001, -1, invalid");

        assertThat(UpgradeRejection.parse(426, headers, null).supportedProtocols()).containsExactly(2, 1000);
        assertThat(UpgradeRejection.parse(426, headers, protocolsBody("2.0")).supportedProtocols())
                .as("a valid JSON body still wins over the header").containsExactly(2);
    }

    private static String protocolsBody(String items) {
        return "{\"error\":{\"code\":\"upgrade_required\",\"details\":{\"supportedProtocols\":[" + items + "]}}}";
    }

    @Test
    @DisplayName("minFramework falls back to the sanitised header; the body wins when present")
    void minFrameworkSources() {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-ulticloud-min-framework", "6.3.2");
        assertThat(UpgradeRejection.parse(426, headers, null).minFramework()).isEqualTo("6.3.2");
        String body = "{\"error\":{\"code\":\"upgrade_required\",\"message\":\"m\",\"requestId\":\"r\","
                + "\"details\":{\"supportedProtocols\":[2],\"minFramework\":\"6.3.1\"}}}";
        assertThat(UpgradeRejection.parse(426, headers, body).minFramework()).isEqualTo("6.3.1");
        headers.put("x-ulticloud-min-framework", "6.3.1;rm -rf");
        assertThat(UpgradeRejection.parse(426, headers, null).minFramework()).isNull();
        headers.put("x-ulticloud-min-framework", "1234567890123456789012345678901234567890");
        assertThat(UpgradeRejection.parse(426, headers, null).minFramework()).isNull();
    }
}
