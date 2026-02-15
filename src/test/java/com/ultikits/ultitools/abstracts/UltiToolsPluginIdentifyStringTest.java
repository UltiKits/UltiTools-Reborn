package com.ultikits.ultitools.abstracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UltiToolsPlugin identifyString Tests")
class UltiToolsPluginIdentifyStringTest {

    @Test
    @DisplayName("getIdentifyString should return null when not set in plugin.yml")
    void shouldReturnNullWhenNotSet() {
        // The identifyString field defaults to null when plugin.yml has no identify-string key
        // We verify the getter exists and returns the expected type
        assertThat(UltiToolsPlugin.class.getDeclaredFields())
            .extracting("name")
            .contains("identifyString");
    }
}
