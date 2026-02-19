package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemDisplay;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class NavigatorTest {

    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        player = Mockito.mock(Player.class);
        Mockito.when(player.getName()).thenReturn("TestPlayer");
        Mockito.when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testNavigatorInitialRoute() {
        Map<String, RouteBuilder> routes = new HashMap<>();
        routes.put("/", context -> ItemDisplay.builder(new ItemStack(Material.STONE)).build());

        Navigator navigator = new Navigator("/", routes);
        StatefulElement element = (StatefulElement) navigator.createElement();
        BuildContext context = BuildContext.root(player, "test_gui", 6);
        element.assignContext(context);
        element.mount(null); // Root mount

        NavigatorState state = (NavigatorState) element.getState();
        assertNotNull(state);
        
        Widget built = state.build(context);
        assertTrue(built instanceof ItemDisplay);
    }

    @Test
    void testNavigatorPushAndPop() {
        Map<String, RouteBuilder> routes = new HashMap<>();
        routes.put("/", context -> ItemDisplay.builder(new ItemStack(Material.STONE)).build());
        routes.put("/detail", context -> ItemDisplay.builder(new ItemStack(Material.DIAMOND)).build());

        Navigator navigator = new Navigator("/", routes);
        StatefulElement element = (StatefulElement) navigator.createElement();
        BuildContext context = BuildContext.root(player, "test_gui", 6);
        element.assignContext(context);
        element.mount(null);

        NavigatorState state = (NavigatorState) element.getState();
        
        // Initial
        assertTrue(state.build(context) instanceof ItemDisplay);
        assertEquals(Material.STONE, ((ItemDisplay)state.build(context)).getItemStack().getType());

        // Push
        state.push("/detail");
        assertEquals(Material.DIAMOND, ((ItemDisplay)state.build(context)).getItemStack().getType());
        assertTrue(state.canPop());

        // Pop
        state.pop();
        assertEquals(Material.STONE, ((ItemDisplay)state.build(context)).getItemStack().getType());
        assertFalse(state.canPop());
    }

    @Test
    void testNavigatorOfInsideBuild() {
        final NavigatorState[] capturedState = new NavigatorState[1];
        
        Map<String, RouteBuilder> routes = new HashMap<>();
        routes.put("/", context -> new StatelessWidget() {
             @Override
             public Widget build(BuildContext context) {
                 capturedState[0] = Navigator.of(context);
                 return Container.builder().build();
             }
             
             @Override
             public Element createElement() {
                 return new StatelessElement(this);
             }
             
             @Override
             public String toString() {
                 return "TestWidget";
             }
        });

        Navigator navigator = new Navigator("/", routes);
        StatefulElement navElement = (StatefulElement) navigator.createElement();
        BuildContext rootContext = BuildContext.root(player, "test_gui", 6);
        navElement.assignContext(rootContext);
        navElement.mount(null);
        navElement.performRebuild(); // This should trigger build of child
        
        assertNotNull(capturedState[0]);
        assertEquals(navElement.getState(), capturedState[0]);
    }
}
