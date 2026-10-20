package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A click outside the GUI cannot reach a GUI handler (D-09 item 4 / T-05-54..58).
 * <p>
 * obliviate-invs 4.3.0's {@code InvListener.onClick} was read from the jar's bytecode: it calls
 * {@code Gui.onClick(event)} (which {@link DeclarativeGui#onClick} overrides) unconditionally,
 * BEFORE it applies its own {@code getRawSlot()} bookkeeping. The framework's override therefore
 * sees every click, including ones in the player's own inventory, before the library has filtered
 * anything for it. Before this class existed, {@link GuiRenderer#handleClick} looked the handler
 * up in a map keyed by {@code node.getSlotIndex()} using {@code event.getSlot()} at lookup time,
 * with no bounds check at all -- {@code getSlot()} is relative to whichever inventory was
 * clicked, so it collides between the GUI's own top inventory and the player's own inventory. A
 * button at GUI slot 4 was reachable from hotbar slot 4.
 * <p>
 * Every test here drives the actual production entry point, {@link DeclarativeGui#onClick}, the
 * same method obliviate-invs calls in production -- never an internal slot-lookup helper directly.
 */
@DisplayName("GUI click dispatch — one slot space, framework-owned bounds check (D-09 item 4)")
class GuiClickDispatchTest {

    /** A one-row GUI's size, per {@code Gui}'s {@code rows * 9} convention. */
    private static final int GUI_SIZE = 9;

    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        // The ServerMock itself is only needed here, to spawn the test player -- unlike player
        // it is never referenced again by any @Test method, so it stays a local rather than a
        // field (PMD SingularField).
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        player = server.addPlayer();
        TestHelper.mockUltiToolsInstance();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // Test 1 (the security assertion): a click in the player's OWN inventory, at the same
    // event.getSlot() value as a registered GUI handler, must not run it.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A click in the player's OWN inventory at the same getSlot() value as a GUI "
            + "handler's slot does not run the handler")
    void clickInPlayerOwnInventoryAtCollidingSlotDoesNotRunHandler() {
        AtomicInteger clicks = new AtomicInteger(0);
        SingleButtonPage page = newButtonPage(4, clicks);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        // Same numeric getSlot() as the GUI's button (4), but the raw slot places the click in
        // the player's OWN inventory: the GUI is 1 row (size 9), so raw slot 13 (9 + 4) is the
        // first row of the player's own inventory in the combined view, not the GUI's own
        // top inventory.
        when(event.getSlot()).thenReturn(4);
        when(event.getRawSlot()).thenReturn(GUI_SIZE + 4);

        page.onClick(event);

        assertEquals(0, clicks.get(),
                "RED at HEAD: GuiRenderer.handleClick looked the handler up by event.getSlot() "
                        + "with no bounds check, so a click on the player's own inventory at the "
                        + "same numeric slot value reached a GUI handler it should never reach.");
    }

    // ------------------------------------------------------------------
    // Test 2: clicking the actual GUI slot runs the handler exactly once.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Clicking the GUI's own slot runs its handler exactly once")
    void clickOnGuiSlotRunsHandlerExactlyOnce() {
        AtomicInteger clicks = new AtomicInteger(0);
        SingleButtonPage page = newButtonPage(4, clicks);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getSlot()).thenReturn(4);
        when(event.getRawSlot()).thenReturn(4);

        page.onClick(event);

        assertEquals(1, clicks.get(), "a click on the GUI's own slot must run its handler exactly once");
    }

    // ------------------------------------------------------------------
    // Test 3: a GUI slot with no handler runs nothing and throws nothing.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A GUI slot with no handler runs nothing and throws nothing")
    void clickOnGuiSlotWithNoHandlerDoesNothing() {
        AtomicInteger clicks = new AtomicInteger(0);
        SingleButtonPage page = newButtonPage(4, clicks);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getSlot()).thenReturn(2);
        when(event.getRawSlot()).thenReturn(2);

        assertDoesNotThrow(() -> page.onClick(event));
        assertEquals(0, clicks.get());
    }

    // ------------------------------------------------------------------
    // Test 4: a click with a null clicked inventory (outside the window entirely) runs
    // nothing and throws nothing.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A click outside the window entirely (null clicked inventory) runs nothing "
            + "and throws nothing")
    void clickOutsideWindowDoesNothing() {
        AtomicInteger clicks = new AtomicInteger(0);
        SingleButtonPage page = newButtonPage(4, clicks);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        // Bukkit reports rawSlot == -999 and a null clicked inventory for a click outside the
        // window entirely (e.g. dropping an item by clicking outside it).
        when(event.getSlot()).thenReturn(-999);
        when(event.getRawSlot()).thenReturn(-999);
        when(event.getClickedInventory()).thenReturn(null);

        assertDoesNotThrow(() -> page.onClick(event));
        assertEquals(0, clicks.get());
    }

    // ------------------------------------------------------------------
    // Test 5: every player-inventory raw slot that collides with a handler-bearing GUI slot
    // is checked, not just slot 4.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Every player-inventory raw slot that collides with a GUI slot is rejected, "
            + "not just one")
    void everyCollidingPlayerInventorySlotIsRejected() {
        AtomicInteger clicks = new AtomicInteger(0);
        AllSlotsButtonPage page = new AllSlotsButtonPage(player, clicks);
        page.setInventory(Bukkit.createInventory(page, GUI_SIZE));
        page.onOpen(mock(InventoryOpenEvent.class));

        // A standard player inventory is 36 slots (27 main + 9 hotbar); drive the whole raw
        // slot range that follows the GUI's own 9 slots in the combined InventoryView, cycling
        // getSlot() through every GUI-local value (0-8) so each one is actually exercised.
        for (int rawSlot = GUI_SIZE; rawSlot < GUI_SIZE + 36; rawSlot++) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            int collidingLocalSlot = (rawSlot - GUI_SIZE) % GUI_SIZE;
            when(event.getSlot()).thenReturn(collidingLocalSlot);
            when(event.getRawSlot()).thenReturn(rawSlot);

            page.onClick(event);
        }

        assertEquals(0, clicks.get(),
                "no player-inventory raw slot -- across the whole collision range -- may reach "
                        + "a GUI handler");
    }

    // ------------------------------------------------------------------
    // Test 6: a shift-click or number-key click originating in the player inventory does not
    // trigger a GUI handler.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A shift-click or number-key click originating in the player inventory does "
            + "not trigger a GUI handler")
    void shiftOrNumberKeyClickFromPlayerInventoryDoesNotTrigger() {
        AtomicInteger clicks = new AtomicInteger(0);
        SingleButtonPage page = newButtonPage(4, clicks);

        InventoryClickEvent shiftClick = mock(InventoryClickEvent.class);
        when(shiftClick.getSlot()).thenReturn(4);
        when(shiftClick.getRawSlot()).thenReturn(GUI_SIZE + 4);
        when(shiftClick.getClick()).thenReturn(ClickType.SHIFT_LEFT);
        when(shiftClick.getAction()).thenReturn(InventoryAction.MOVE_TO_OTHER_INVENTORY);
        page.onClick(shiftClick);

        InventoryClickEvent numberKeyClick = mock(InventoryClickEvent.class);
        when(numberKeyClick.getSlot()).thenReturn(4);
        when(numberKeyClick.getRawSlot()).thenReturn(GUI_SIZE + 4);
        when(numberKeyClick.getClick()).thenReturn(ClickType.NUMBER_KEY);
        when(numberKeyClick.getAction()).thenReturn(InventoryAction.HOTBAR_SWAP);
        page.onClick(numberKeyClick);

        assertEquals(0, clicks.get());
    }

    // ------------------------------------------------------------------
    // Test 7: after a repaint moves a handler, the old slot runs nothing and the new slot
    // runs the handler. Unreachable before plan 05-11 made repaints real.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("After a repaint moves a handler, the old slot runs nothing and the new slot "
            + "runs it")
    void repaintMovingHandlerMovesItsClickTarget() {
        AtomicInteger clicks = new AtomicInteger(0);
        MovableButtonPage page = new MovableButtonPage(player, clicks);
        page.setInventory(Bukkit.createInventory(page, GUI_SIZE));
        page.onOpen(mock(InventoryOpenEvent.class));

        InventoryClickEvent atInitialSlot = mock(InventoryClickEvent.class);
        when(atInitialSlot.getSlot()).thenReturn(0);
        when(atInitialSlot.getRawSlot()).thenReturn(0);
        page.onClick(atInitialSlot);
        assertEquals(1, clicks.get(), "the handler must fire at its initial slot");

        // Move the button from slot 0 to slot 3 and repaint.
        page.slot = 3;
        page.markNeedsBuild();

        InventoryClickEvent oldSlot = mock(InventoryClickEvent.class);
        when(oldSlot.getSlot()).thenReturn(0);
        when(oldSlot.getRawSlot()).thenReturn(0);
        page.onClick(oldSlot);
        assertEquals(1, clicks.get(), "the OLD slot must no longer run the handler after the repaint");

        InventoryClickEvent newSlot = mock(InventoryClickEvent.class);
        when(newSlot.getSlot()).thenReturn(3);
        when(newSlot.getRawSlot()).thenReturn(3);
        page.onClick(newSlot);
        assertEquals(2, clicks.get(), "the NEW slot must run the handler after the repaint");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private SingleButtonPage newButtonPage(int slot, AtomicInteger clicks) {
        SingleButtonPage page = new SingleButtonPage(player, slot, clicks);
        page.setInventory(Bukkit.createInventory(page, GUI_SIZE));
        page.onOpen(mock(InventoryOpenEvent.class));
        return page;
    }

    private static final class SingleButtonPage extends DeclarativeGui {
        private final int slot;
        private final AtomicInteger clicks;

        SingleButtonPage(Player player, int slot, AtomicInteger clicks) {
            super(player, "single-button-page", "Test", 1);
            this.slot = slot;
            this.clicks = clicks;
        }

        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder()
                    .text("Button")
                    .slot(slot)
                    .onClick(clicks::incrementAndGet)
                    .build();
        }
    }

    private static final class MovableButtonPage extends DeclarativeGui {
        private final AtomicInteger clicks;
        volatile int slot = 0;

        MovableButtonPage(Player player, AtomicInteger clicks) {
            super(player, "movable-button-page", "Test", 1);
            this.clicks = clicks;
        }

        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder()
                    .text("Button")
                    .slot(slot)
                    .onClick(clicks::incrementAndGet)
                    .build();
        }
    }

    private static final class AllSlotsButtonPage extends DeclarativeGui {
        private final AtomicInteger clicks;

        AllSlotsButtonPage(Player player, AtomicInteger clicks) {
            super(player, "all-slots-button-page", "Test", 1);
            this.clicks = clicks;
        }

        @Override
        public Widget build(BuildContext context) {
            TextButton[] buttons = new TextButton[GUI_SIZE];
            for (int i = 0; i < GUI_SIZE; i++) {
                buttons[i] = TextButton.builder()
                        .text("Button " + i)
                        .slot(i)
                        .onClick(clicks::incrementAndGet)
                        .build();
            }
            return Container.builder().children(buttons).build();
        }
    }
}
