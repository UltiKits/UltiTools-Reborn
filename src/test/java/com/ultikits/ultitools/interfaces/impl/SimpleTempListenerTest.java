package com.ultikits.ultitools.interfaces.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
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
import com.ultikits.ultitools.interfaces.TempListener;

class SimpleTempListenerTest {

    private static UltiTools originalInstance;
    private static Server mockServer;
    private static PluginManager mockPluginManager;

    @BeforeAll
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static void setUpClass() throws Exception {
        // Mock Server and PluginManager
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

        // Mock UltiTools singleton
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
    void testRegisterAndHandle() throws Exception {
        // Arrange
        TempEventHandler<Event> mockHandler = mock(TempEventHandler.class);
        when(mockHandler.handle(any())).thenReturn(true); // Return true to unregister
        
        SimpleTempListener<Event> listener = new SimpleTempListener<>(Event.class, mockHandler);
        
        // Act
        listener.register();
        
        // Assert registration
        ArgumentCaptor<EventExecutor> executorCaptor = ArgumentCaptor.forClass(EventExecutor.class);
        verify(mockPluginManager).registerEvent(eq(Event.class), eq(listener), eq(EventPriority.NORMAL), executorCaptor.capture(), any(Plugin.class));
        
        // Simulate event execution
        Event mockEvent = mock(Event.class);
        executorCaptor.getValue().execute(listener, mockEvent);
        
        // Verify handler called
        verify(mockHandler).handle(mockEvent);
        
        // Verify unregister called (since handler returned true)
        // Note: HandlerList.unregisterAll(listener) is static and hard to verify without static mocking.
        // But we can verify that if handle returns true, the code proceeds to unregister logic.
        // In SimpleTempListener, unregister() calls HandlerList.unregisterAll(this).
        // Since we can't easily mock HandlerList, we assume it works if no exception is thrown.
    }

    @Test
    void testFilter() throws Exception {
        // Arrange
        TempEventHandler<Event> mockHandler = mock(TempEventHandler.class);
        Function<Event, Boolean> filter = event -> false; // Filter out everything
        
        SimpleTempListener<Event> listener = new SimpleTempListener<>(Event.class, mockHandler, filter);
        
        // Act
        listener.register();
        
        ArgumentCaptor<EventExecutor> executorCaptor = ArgumentCaptor.forClass(EventExecutor.class);
        verify(mockPluginManager).registerEvent(eq(Event.class), eq(listener), eq(EventPriority.NORMAL), executorCaptor.capture(), any(Plugin.class));
        
        Event mockEvent = mock(Event.class);
        executorCaptor.getValue().execute(listener, mockEvent);
        
        // Assert
        verify(mockHandler, never()).handle(any());
    }
    
    @Test
    void testConstructors() {
        TempEventHandler<Event> mockHandler = mock(TempEventHandler.class);
        
        SimpleTempListener<Event> l1 = new SimpleTempListener<>(Event.class, mockHandler);
        assertEquals(EventPriority.NORMAL, l1.getPriority());
        
        SimpleTempListener<Event> l2 = new SimpleTempListener<>(Event.class, mockHandler, EventPriority.HIGH);
        assertEquals(EventPriority.HIGH, l2.getPriority());
        
        Function<Event, Boolean> filter = e -> true;
        SimpleTempListener<Event> l3 = new SimpleTempListener<>(Event.class, mockHandler, filter);
        assertEquals(filter, l3.getFilter());
    }

    @Test
    void builderPathShouldProduceListenerWithFilterPriorityAndHandler() {
        // Arrange
        Function<Event, Boolean> filter = e -> true;
        TempEventHandler<Event> handler = mock(TempEventHandler.class);

        // Act - pin the .common(...).filter(...).build() construction path specifically,
        // rather than duplicating the direct-constructor coverage above.
        TempListener listener = TempListener.common(Event.class)
                .priority(EventPriority.HIGH)
                .filter(filter)
                .eventHandler(handler)
                .build();

        // Assert
        assertThat(listener).isInstanceOf(SimpleTempListener.class);
        SimpleTempListener<?> simple = (SimpleTempListener<?>) listener;
        assertThat(simple.getFilter()).isEqualTo(filter);
        assertThat(simple.getPriority()).isEqualTo(EventPriority.HIGH);
        assertThat(simple.getEventHandler()).isEqualTo(handler);
    }

    @Test
    void registerWithoutEventHandlerShouldFailWithDescriptiveMessage() {
        // Arrange - direct construction (no-arg + setters), eventHandler intentionally left null
        SimpleTempListener<Event> listener = new SimpleTempListener<>();
        listener.setEventClass(Event.class);

        // Act + Assert
        assertThatThrownBy(listener::register)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventHandler");
    }

    @Test
    void registerWithoutEventClassShouldFailWithDescriptiveMessage() {
        // Arrange - direct construction (no-arg + setters), eventClass intentionally left null
        SimpleTempListener<Event> listener = new SimpleTempListener<>();
        TempEventHandler<Event> handler = mock(TempEventHandler.class);
        listener.setEventHandler(handler);

        // Act + Assert
        assertThatThrownBy(listener::register)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventClass");
    }
}
