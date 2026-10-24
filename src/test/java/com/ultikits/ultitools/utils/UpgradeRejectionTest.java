package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonParser;

/**
 * The refused-upgrade parser against sample bodies.
 * <p>
 * The files under {@link #SAMPLE_ROOT} are <b>provisional stand-ins</b> for the contract fixtures
 * {@code fixtures/protocol/v2/agent-rest/agentUpgrade/error/} (laid out the same way, {@code valid/}
 * and {@code invalid/}), written from the documented bodies (contract answer 23 and the plan-limit
 * shape of contract 2.0.0). Plan 22-08 repoints {@code SAMPLE_ROOT} at the vendored fixtures and
 * deletes {@code provisional/}. The expected status comes from each body's error code, not from
 * the file name.
 */
@DisplayName("UpgradeRejection parsing and sanitising")
class UpgradeRejectionTest {

    static final String SAMPLE_ROOT = "ulticloud-contract/provisional/agentUpgrade/error/";

    private static final Map<String, Integer> STATUS_BY_CODE;

    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("upgrade_required", 426);
        m.put("invalid_credential", 401);
        m.put("rate_limited", 429);
        m.put("plan_limit", 402);
        STATUS_BY_CODE = Collections.unmodifiableMap(m);
    }

    static List<Path> samples(String kind) throws IOException, URISyntaxException {
        URL root = UpgradeRejectionTest.class.getClassLoader().getResource(SAMPLE_ROOT + kind);
        assertThat(root).as("sample directory " + SAMPLE_ROOT + kind).isNotNull();
        try (Stream<Path> files = Files.walk(Paths.get(root.toURI()))) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
    }

    static List<Path> validSamples() throws IOException, URISyntaxException {
        return samples("valid");
    }

    static List<Path> invalidSamples() throws IOException, URISyntaxException {
        return samples("invalid");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
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
    @DisplayName("both sample directories hold samples (control for the parameterised tests)")
    void sampleDirectoriesAreNotEmpty() throws Exception {
        assertThat(validSamples()).hasSize(4);
        assertThat(invalidSamples()).isNotEmpty();
    }

    @ParameterizedTest(name = "valid sample {0}")
    @MethodSource("validSamples")
    void validSampleParses(Path sample) throws IOException {
        String body = read(sample);
        String code = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
        int status = STATUS_BY_CODE.get(code);
        UpgradeRejection r = UpgradeRejection.parse(status, Collections.emptyMap(), body);
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.code()).isEqualTo(code);
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
        }
    }

    @ParameterizedTest(name = "invalid sample {0}")
    @MethodSource("invalidSamples")
    void invalidSampleIsStatusOnly(Path sample) throws IOException {
        String body = read(sample);
        for (int status : new int[]{401, 402, 426, 429}) {
            assertStatusOnly(UpgradeRejection.parse(status, Collections.emptyMap(), body), status);
        }
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
    @DisplayName("the 402 fields are also accepted wrapped in error and at the top level until 22-08 narrows it")
    void planShapesTolerated() {
        String inError = "{\"error\":{\"code\":\"plan_limit\",\"reason\":\"pro_expired_over_limit\","
                + "\"graceEndedAt\":\"2026-11-01T00:00:00.000Z\",\"manageUrl\":\"https://panel.ultikits.com/b\"}}";
        String topLevel = "{\"code\":\"plan_limit\",\"reason\":\"pro_expired_over_limit\","
                + "\"graceEndedAt\":\"2026-11-01T00:00:00.000Z\",\"manageUrl\":\"https://panel.ultikits.com/b\"}";
        for (String body : new String[]{inError, topLevel}) {
            UpgradeRejection r = UpgradeRejection.parse(402, null, body);
            assertThat(r.code()).isEqualTo("plan_limit");
            assertThat(r.planReason()).isEqualTo("pro_expired_over_limit");
            assertThat(r.graceEndedAt()).isEqualTo("2026-11-01T00:00:00.000Z");
            assertThat(r.manageUrl()).isEqualTo("https://panel.ultikits.com/b");
        }
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
