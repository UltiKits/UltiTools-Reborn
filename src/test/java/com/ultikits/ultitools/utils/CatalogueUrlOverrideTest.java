package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;

class CatalogueUrlOverrideTest {
    private static final String PROPERTY = "ultitools.catalogue.url";
    private static final Logger LOGGER = Logger.getLogger(PluginInstallUtils.class.getName());
    private final List<String> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) {
            if (record.getLevel().equals(Level.WARNING)) {
                warnings.add(record.getMessage());
            }
        }
        @Override public void flush() { /* No buffer. */ }
        @Override public void close() { /* No resource. */ }
    };
    private static final String BUNDLED = "https://catalogue.example.test";
    private String original;
    private MockedStatic<UltiTools> tools;

    @BeforeEach
    void reset() {
        original = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
        PluginInstallUtils.resetBaseUrl();
        LOGGER.addHandler(capture);
        var env = new YamlConfiguration();
        env.set("api-url", BUNDLED);
        tools = mockStatic(UltiTools.class);
        tools.when(UltiTools::getEnv).thenReturn(env);
    }

    @AfterEach
    void restore() {
        tools.close();
        LOGGER.removeHandler(capture);
        if (original == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, original);
        }
        PluginInstallUtils.resetBaseUrl();
    }

    @Test
    void unsetLeavesTheBundledUrlUnchanged() {
        assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo(UltiTools.getEnv().getString("api-url"));
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]", "localhost"})
    void acceptsOnlyTheRequestedLoopbackUrlAndWarnsOnce(String host) {
        String url = "https://" + host + ":18443";
        System.setProperty(PROPERTY, url);
        assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo(url);
        assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo(url);
        assertThat(warnings).singleElement().asString().contains(url, "for testing only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.test:18443", "https://localhost.example.test", "https://localhost@example.test"})
    void ignoresNonLoopbackAndWarnsOnce(String url) {
        System.setProperty(PROPERTY, url);
        assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo(UltiTools.getEnv().getString("api-url"));
        assertThat(PluginInstallUtils.getBaseUrl()).isEqualTo(UltiTools.getEnv().getString("api-url"));
        assertThat(warnings).singleElement().asString().contains("Ignoring", "loopback");
    }
}
