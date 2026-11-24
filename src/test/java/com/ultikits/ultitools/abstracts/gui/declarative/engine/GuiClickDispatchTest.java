package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.GridClick;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The UI service, not an engine-owned click table, dispatches actions on the last displayed snapshot. */
@Timeout(30)
class GuiClickDispatchTest {
    private static final int GUI_SIZE = 9;
    private Player viewer;
    private GuiTestHarness harness;
    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState(); viewer = MockBukkit.mock().addPlayer(); harness = new GuiTestHarness();
    }
    @AfterEach void tearDown() { harness.close(); MockBukkitHelper.safeUnmock(); }
    private ButtonPage page(AtomicInteger clicks) {
        ButtonPage page = new ButtonPage(harness, viewer, clicks); harness.open(page); return page;
    }
    @Test void clickInPlayerOwnInventoryAtCollidingSlotDoesNotRunHandler() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        harness.click(harness.session(page), GUI_SIZE + 4, ClickKind.LEFT);
        assertThat(clicks).hasValue(0);
    }
    @Test void clickOnGuiSlotRunsHandlerExactlyOnce() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        harness.click(page, 4);
        assertThat(clicks).hasValue(1);
    }
    @Test void clickOnGuiSlotWithNoHandlerDoesNothing() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        harness.click(page, 2);
        assertThat(clicks).hasValue(0);
    }
    @Test void clickOutsideWindowDoesNothing() {
        AtomicInteger clicks = new AtomicInteger(); page(clicks);
        assertThatIllegalArgumentException().isThrownBy(() -> GridSlot.ofIndex(-999));
        assertThat(clicks).hasValue(0);
    }
    @Test void everyCollidingPlayerInventorySlotIsRejected() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = new ButtonPage(harness, viewer, clicks);
        page.allSlots = true; harness.open(page);
        for (int rawSlot = GUI_SIZE; rawSlot < GUI_SIZE + 36; rawSlot++) {
            harness.click(harness.session(page), rawSlot, ClickKind.LEFT);
        }
        assertThat(clicks).hasValue(0);
    }
    @Test void shiftOrNumberKeyClickFromPlayerInventoryDoesNotTrigger() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        harness.click(harness.session(page), GUI_SIZE + 4, ClickKind.SHIFT_LEFT);
        harness.click(harness.session(page), GUI_SIZE + 4, ClickKind.NUMBER_KEY);
        assertThat(clicks).hasValue(0);
    }
    @Test void repaintMovingHandlerMovesItsClickTarget() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        harness.click(page, 4); assertThat(clicks).hasValue(1);
        page.moveTo(3); harness.drain();
        harness.click(page, 4); assertThat(clicks).hasValue(1);
        harness.click(page, 3); assertThat(clicks).hasValue(2);
    }
    @Test void actionsReceiveViewerSessionSlotAndKindAndStopAfterClose() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        GridSession session = harness.session(page);
        harness.click(session, 4, ClickKind.RIGHT);
        GridClick click = page.last.get();
        assertThat(click.viewer()).isSameAs(viewer);
        assertThat(click.session()).isSameAs(session);
        assertThat(click.slot()).isEqualTo(GridSlot.ofIndex(4));
        assertThat(click.kind()).isEqualTo(ClickKind.RIGHT);
        harness.close(page); harness.click(session, 4, ClickKind.LEFT);
        assertThat(clicks).hasValue(1);
    }
    @Test void removingAnActionDoesNotRetainItsOldHandler() {
        AtomicInteger clicks = new AtomicInteger(); ButtonPage page = page(clicks);
        page.setState(() -> page.actionEnabled = false); harness.drain(); harness.click(page, 4);
        assertThat(clicks).hasValue(0);
    }

    private static final class ButtonPage extends DeclarativeGui {
        private final AtomicInteger clicks;
        private final AtomicReference<GridClick> last = new AtomicReference<>();
        private int slot = 4;
        private boolean allSlots;
        private boolean actionEnabled = true;
        ButtonPage(GuiTestHarness harness, Player viewer, AtomicInteger clicks) {
            super(harness.ui(), harness.scheduler(), viewer, "buttons", Text.plain("Test"), 1); this.clicks = clicks;
        }
        void moveTo(int slot) { setState(() -> this.slot = slot); }
        private Widget button(int at) {
            TextButton.Builder button = TextButton.builder().text(Text.plain("Button")).slot(at);
            if (actionEnabled) { button.onClick(click -> { last.set(click); clicks.incrementAndGet(); }); }
            return button.build();
        }
        @Override public Widget build(BuildContext context) {
            if (!allSlots) { return button(slot); }
            Container.Builder root = Container.builder();
            for (int i = 0; i < GUI_SIZE; i++) { root.child(button(i)); }
            return root.build();
        }
    }
}
