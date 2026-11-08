package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.Arrays;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.handler.ConsoleMirror;
import com.ultikits.ultitools.manager.EarlyLogCapture;

/**
 * #571 item 6a: when {@code UltiTools#onLoad()} throws after it has attached the start-up log
 * capture, nothing else releases it ({@code onEnable} schedules the release), so it stayed on the
 * {@code java.util.logging} root logger, holding records and the plugin class loader, until the
 * next log line after its five-minute limit. A failing {@code onLoad} now detaches the capture and
 * removes the console mirror before the exception leaves it.
 */
@DisplayName("A failing onLoad releases the start-up log capture (#571)")
class UltiToolsOnLoadFailureTest {

    private MockedStatic<UltiTools> instanceMock;
    private MockedStatic<ConsoleMirror> mirrorMock;
    private Object previousInstance;

    @BeforeEach
    void setUp() throws Exception {
        previousInstance = instanceField().get(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        EarlyLogCapture.release();
        if (mirrorMock != null) {
            mirrorMock.close();
        }
        if (instanceMock != null) {
            instanceMock.close();
        }
        instanceField().set(null, previousInstance);
    }

    private static Field instanceField() throws NoSuchFieldException {
        Field field = UltiTools.class.getDeclaredField("ultiTools");
        field.setAccessible(true);
        return field;
    }

    private static boolean captureAttached() {
        return Arrays.stream(Logger.getLogger("").getHandlers()).anyMatch(h -> h instanceof EarlyLogCapture);
    }

    @Test
    @DisplayName("an unparsable server JAR location: the exception propagates, the capture and the mirror are gone")
    void failingOnLoadReleasesTheCapture() throws Exception {
        UltiTools plugin = mock(UltiTools.class);
        lenient().when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("UltiToolsOnLoadFailureTest"));
        // A space is legal in a URL but not in a URI, so toURI() throws URISyntaxException.
        lenient().when(plugin.getServerJar()).thenReturn(new URL("file:/server dir/paper.jar"));
        doCallRealMethod().when(plugin).onLoad();
        instanceMock = mockStatic(UltiTools.class);
        instanceMock.when(UltiTools::getInstance).thenReturn(plugin);
        mirrorMock = mockStatic(ConsoleMirror.class);
        mirrorMock.when(ConsoleMirror::install).thenReturn(true);

        assertThatThrownBy(plugin::onLoad).isInstanceOf(PluginModuleException.class);

        assertThat(captureAttached()).as("the start-up capture is detached from the root logger").isFalse();
        mirrorMock.verify(ConsoleMirror::uninstall);
    }

    @Test
    @DisplayName("control: a successful onLoad keeps the capture attached for onEnable")
    void successfulOnLoadKeepsTheCapture() throws Exception {
        UltiTools plugin = mock(UltiTools.class);
        lenient().when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("UltiToolsOnLoadFailureTest"));
        lenient().when(plugin.getServerJar()).thenReturn(new URL("file:/server/paper.jar"));
        doCallRealMethod().when(plugin).onLoad();
        instanceMock = mockStatic(UltiTools.class);
        instanceMock.when(UltiTools::getInstance).thenReturn(plugin);
        mirrorMock = mockStatic(ConsoleMirror.class);
        mirrorMock.when(ConsoleMirror::install).thenReturn(true);

        plugin.onLoad();

        assertThat(captureAttached()).isTrue();
        mirrorMock.verify(ConsoleMirror::uninstall, org.mockito.Mockito.never());
    }
}
