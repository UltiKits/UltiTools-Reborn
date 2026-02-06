package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.MotdConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.event.server.ServerListPingEvent;
import org.junit.jupiter.api.*;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for MotdListener.
 * <p>
 * 测试MOTD监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("MotdListener Tests (Mockito)")
class MotdListenerMockitoTest {

    private MotdListener listener;
    private EssentialsConfig config;
    private MotdConfig motdConfig;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new MotdListener();
        config = new EssentialsConfig();
        motdConfig = new MotdConfig();

        EssentialsTestHelper.setField(listener, "config", config);
        EssentialsTestHelper.setField(listener, "motdConfig", motdConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Test
    @DisplayName("Should skip when MOTD is disabled")
    void shouldSkipWhenDisabled() {
        config.setMotdEnabled(false);

        ServerListPingEvent event = mock(ServerListPingEvent.class);

        listener.onServerListPing(event);

        verify(event, never()).setMotd(anyString());
    }

    @Test
    @DisplayName("Should set custom MOTD when enabled")
    void shouldSetCustomMotd() {
        config.setMotdEnabled(true);
        motdConfig.setLine1("&aServer Name");
        motdConfig.setLine2("&7Welcome!");

        ServerListPingEvent event = mock(ServerListPingEvent.class);

        listener.onServerListPing(event);

        verify(event).setMotd(anyString());
    }

    @Test
    @DisplayName("Should set max players when configured")
    void shouldSetMaxPlayers() {
        config.setMotdEnabled(true);
        motdConfig.setLine1("Line 1");
        motdConfig.setLine2("Line 2");
        motdConfig.setMaxPlayers(200);

        ServerListPingEvent event = mock(ServerListPingEvent.class);

        listener.onServerListPing(event);

        verify(event).setMaxPlayers(200);
    }

    @Test
    @DisplayName("Should not set max players when value is 0 or negative")
    void shouldNotSetMaxPlayersWhenZero() {
        config.setMotdEnabled(true);
        motdConfig.setLine1("Line 1");
        motdConfig.setLine2("Line 2");
        motdConfig.setMaxPlayers(0);

        ServerListPingEvent event = mock(ServerListPingEvent.class);

        listener.onServerListPing(event);

        verify(event, never()).setMaxPlayers(anyInt());
    }

    @Test
    @DisplayName("Should translate color codes in MOTD")
    void shouldTranslateColorCodes() {
        config.setMotdEnabled(true);
        motdConfig.setLine1("&aGreen Line");
        motdConfig.setLine2("&cRed Line");

        ServerListPingEvent event = mock(ServerListPingEvent.class);

        listener.onServerListPing(event);

        verify(event).setMotd(argThat(motd ->
            motd.contains("\u00a7a") && motd.contains("\u00a7c") && motd.contains("\n")
        ));
    }
}
