package com.ultikits.ultitools.interfaces.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.TempEventHandler;

class PlayerTempListenerTest {

    private static UltiTools originalInstance;
    private static Server mockServer;
    private static PluginManager mockPluginManager;

    @BeforeAll
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static void setUpClass() throws Exception {
        if (Bukkit.getServer() == null) {
            mockServer = mock(Server.class);
            java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        } else {
            mockServer = Bukkit.getServer();
        }
        
        mockPluginManager = mock(PluginManager.class);
        when(mockServer.getPluginManager()).thenReturn(mockPluginManager);

        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        originalInstance = (UltiTools) instanceField.get(null);
        
        UltiTools mockUltiTools = mock(UltiTools.class);
        instanceField.set(null, mockUltiTools);
    }

    @AfterAll
    static void tearDownClass() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, originalInstance);
    }

    @BeforeEach
    void setUp() {
        reset(mockPluginManager);
    }

    @Test
    void testRegisterAndHandleWithPlayer() throws Exception {
        // Arrange
        TempEventHandler<PlayerEvent> mockHandler = mock(TempEventHandler.class);
        when(mockHandler.handle(any())).thenReturn(true);
        
        Player mockPlayer = mock(Player.class);
        PlayerTempListener<PlayerEvent> listener = new PlayerTempListener<>(PlayerEvent.class, mockHandler, mockPlayer);
        
        // Act
        listener.register();
        
        ArgumentCaptor<EventExecutor> executorCaptor = ArgumentCaptor.forClass(EventExecutor.class);
        verify(mockPluginManager).registerEvent(eq(PlayerEvent.class), eq(listener), eq(EventPriority.NORMAL), executorCaptor.capture(), any(Plugin.class));
        
        // Simulate event with matching player
        PlayerEvent mockEvent = mock(PlayerEvent.class);
        when(mockEvent.getPlayer()).thenReturn(mockPlayer);
        
        executorCaptor.getValue().execute(listener, mockEvent);
        
        verify(mockHandler).handle(mockEvent);
    }

    @Test
    void testHandleWithDifferentPlayer() throws Exception {
        // Arrange
        TempEventHandler<PlayerEvent> mockHandler = mock(TempEventHandler.class);
        Player mockPlayer1 = mock(Player.class);
        Player mockPlayer2 = mock(Player.class);
        
        PlayerTempListener<PlayerEvent> listener = new PlayerTempListener<>(PlayerEvent.class, mockHandler, mockPlayer1);
        listener.register();
        
        ArgumentCaptor<EventExecutor> executorCaptor = ArgumentCaptor.forClass(EventExecutor.class);
        verify(mockPluginManager).registerEvent(any(), any(), any(), executorCaptor.capture(), any());
        
        // Simulate event with different player
        PlayerEvent mockEvent = mock(PlayerEvent.class);
        when(mockEvent.getPlayer()).thenReturn(mockPlayer2);
        
        executorCaptor.getValue().execute(listener, mockEvent);
        
        verify(mockHandler, never()).handle(any());
    }

    @Test
    void testHandleWithNullPlayer() throws Exception {
        // Arrange
        TempEventHandler<PlayerEvent> mockHandler = mock(TempEventHandler.class);
        when(mockHandler.handle(any())).thenReturn(true);
        
        // Null player means handle all events
        PlayerTempListener<PlayerEvent> listener = new PlayerTempListener<>(PlayerEvent.class, mockHandler);
        listener.register();
        
        ArgumentCaptor<EventExecutor> executorCaptor = ArgumentCaptor.forClass(EventExecutor.class);
        verify(mockPluginManager).registerEvent(any(), any(), any(), executorCaptor.capture(), any());
        
        PlayerEvent mockEvent = mock(PlayerEvent.class);
        executorCaptor.getValue().execute(listener, mockEvent);
        
        verify(mockHandler).handle(mockEvent);
    }
}
