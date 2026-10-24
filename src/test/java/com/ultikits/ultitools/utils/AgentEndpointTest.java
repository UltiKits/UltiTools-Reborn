package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AgentEndpoint derivation from the API URL")
class AgentEndpointTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "https://api.ultikits.com, wss://api.ultikits.com/v1/agent",
            "https://api.ultikits.com/, wss://api.ultikits.com/v1/agent",
            "http://127.0.0.1:8787, ws://127.0.0.1:8787/v1/agent",
            "api-staging.ultikits.com, wss://api-staging.ultikits.com/v1/agent",
            "https://host/base/, wss://host/base/v1/agent",
            "HTTPS://api.ultikits.com, wss://api.ultikits.com/v1/agent"
    })
    void maps(String apiUrl, String expected) {
        assertThat(AgentEndpoint.fromApiUrl(apiUrl)).hasToString(expected);
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {"", "   ", "ht!tp://x", "ftp://api.ultikits.com", "https://", "https://host?x=1"})
    void rejects(String apiUrl) {
        assertThatThrownBy(() -> AgentEndpoint.fromApiUrl(apiUrl)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        assertThatThrownBy(() -> AgentEndpoint.fromApiUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
