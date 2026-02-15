package com.ultikits.ultitools.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpdateInfo Tests")
class UpdateInfoTest {

    @Test
    @DisplayName("Should store and retrieve all fields")
    void shouldStoreAndRetrieveAllFields() {
        UpdateInfo info = new UpdateInfo();
        info.setPluginName("UltiChat");
        info.setIdentifyString("ultichat");
        info.setCurrentVersion("1.0.0");
        info.setLatestVersion("1.1.0");

        assertThat(info.getPluginName()).isEqualTo("UltiChat");
        assertThat(info.getIdentifyString()).isEqualTo("ultichat");
        assertThat(info.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(info.getLatestVersion()).isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("Should have working equals and hashCode via Lombok")
    void shouldHaveEqualsAndHashCode() {
        UpdateInfo a = new UpdateInfo();
        a.setPluginName("Test");
        a.setCurrentVersion("1.0");
        a.setLatestVersion("2.0");

        UpdateInfo b = new UpdateInfo();
        b.setPluginName("Test");
        b.setCurrentVersion("1.0");
        b.setLatestVersion("2.0");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
