package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;

/**
 * Local Codex review of #570, run 1: with {@code ultipanel.capabilities.logs: false} the panel never
 * receives the log stream, so the start-up capture must not collect anything either (D-12: a disabled
 * capability prevents collection, it does not collect and discard). Before this, {@code onLoad()}
 * attached the capture unconditionally and it held up to five minutes of records in memory when the
 * panel connection never opened.
 */
@DisplayName("The start-up log capture honours the logs capability (#487, Codex run 1)")
class EarlyLogCaptureLogsCapabilityTest {

    private MockedStatic<UltiTools> ultiToolsMock;

    private void withConfig(YamlConfiguration config) {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.getConfig()).thenReturn(config);
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);
    }

    @AfterEach
    void tearDown() {
        EarlyLogCapture.release();
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    private static boolean captureAttached() {
        return Arrays.stream(Logger.getLogger("").getHandlers()).anyMatch(h -> h instanceof EarlyLogCapture);
    }

    @Test
    @DisplayName("logs capability off: nothing is attached to the root logger")
    void logsOffAttachesNothing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("ultipanel.capabilities.logs", false);
        withConfig(config);

        assertThat(EarlyLogCapture.startIfLogsEnabled(Collections.emptyList())).isFalse();
        assertThat(captureAttached()).isFalse();
    }

    @Test
    @DisplayName("logs capability on (the default): the capture is attached")
    void logsOnAttachesTheCapture() {
        withConfig(new YamlConfiguration());

        assertThat(EarlyLogCapture.startIfLogsEnabled(Collections.emptyList())).isTrue();
        assertThat(captureAttached()).isTrue();
    }

    @Test
    @DisplayName("UltiTools#onLoad starts the capture through the capability check, never directly")
    void onLoadGoesThroughTheCapabilityCheck() throws IOException {
        Path root = Paths.get("src/main/java/com/ultikits/ultitools");
        String source = new String(Files.readAllBytes(root.resolve("UltiTools.java")), StandardCharsets.UTF_8);

        assertThat(source.length()).as("control: UltiTools.java was read").isGreaterThan(1000);
        assertThat(source).contains("EarlyLogCapture.startIfLogsEnabled(");
        assertThat(source).doesNotContain("EarlyLogCapture.start(");
    }
}
