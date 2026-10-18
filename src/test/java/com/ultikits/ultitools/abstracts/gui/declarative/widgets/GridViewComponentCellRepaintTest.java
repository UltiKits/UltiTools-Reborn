package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatelessWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import mc.obliviate.inventory.Gui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Second-frame reproductions for GridView cells built by component (wrapper) widgets.
 * <p>
 * {@code GridViewElement} writes each cell's computed slot onto the leaf {@code RenderNode}s as
 * parent data. On every frame after the first, a component cell's leaf is only updated (and
 * marked dirty) by the GridView's own rebuild; if the GridView writes positions before that
 * leaf has rebuilt, the renderer's later visit resets the leaf's slot from its widget and the
 * computed slot is lost. These tests drive real frames through {@link GuiRenderer} and read the
 * Inventory, so they observe what a player would see after a repaint.
 *
 * @author UltiTools Team
 * @since 6.3.0
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class GridViewComponentCellRepaintTest {

    private static final int GUI_ROWS = 1;
    private static final int GUI_SIZE = GUI_ROWS * 9;
    private static final String EMPTY = "-";

    private Plugin mockPlugin;
    private Player player;
    private BuildContext rootContext;
    private TestGui gui;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        rootContext = BuildContext.root(player, "test-gui", GUI_ROWS);
        gui = new TestGui(player, "test-gui", "Test", GUI_ROWS);
        gui.setInventory(Bukkit.createInventory(gui, GUI_SIZE));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // Task 1 (tracer): stateless wrapper cells through a second frame.
    // ------------------------------------------------------------------

    @Test
    void statelessCellsKeepComputedSlotsOnSecondFrame() {
        GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(new StatelessLabelCell("A"))
                .child(new StatelessLabelCell("B"))
                .child(new StatelessLabelCell("C"))
                .build());

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 1 (precondition): each stateless cell renders at its computed slot");

        renderer.scheduleBuild();

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 2: each stateless cell must still render at its GridView-computed slot");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private GuiRenderer startRenderer(@NotNull Supplier<Widget> supplier) {
        GuiScheduler scheduler = new GuiScheduler(mockPlugin, 0L);
        GuiRenderer renderer = new GuiRenderer(gui, player, scheduler);
        renderer.initialize(supplier, rootContext);
        return renderer;
    }

    /** Labels of the given slots joined by '|': display name, else material, else "-". */
    private String labels(int... slots) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                out.append('|');
            }
            out.append(labelAt(slots[i]));
        }
        return out.toString();
    }

    private String labelAt(int slot) {
        ItemStack stack = gui.getInventory().getItem(slot);
        if (stack == null || stack.getType() == Material.AIR) {
            return EMPTY;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return stack.getType().name();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A stateless component cell wrapping one TextButton that declares no slot. */
    private static final class StatelessLabelCell extends StatelessWidget {
        private final String label;

        StatelessLabelCell(String label) {
            this.label = label;
        }

        @Override
        @NotNull
        public Widget build(@NotNull BuildContext context) {
            return TextButton.builder().text(label).build();
        }
    }

    private static final class TestGui extends Gui {
        TestGui(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }
    }
}
