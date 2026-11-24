package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.AbstractUiServiceContractTest.Fixture;
import com.ultikits.ultitools.ui.AbstractUiServiceContractTest.ProbeBackend;
import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridClick;
import com.ultikits.ultitools.ui.GridItem;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.UiService;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import static org.mockito.Mockito.mock;

/** Test access to the unchanged contract fixture; no product or contract visibility is widened. */
public final class GuiTestHarness implements AutoCloseable {
    private final Fixture fixture;
    private final ProbeBackend backend = new ProbeBackend("declarative", 0, Grid.class);
    private final Map<DeclarativeGui, GridSession> sessions = new IdentityHashMap<>();

    public GuiTestHarness() {
        try {
            // The existing package-private reference implementation is intentionally left unchanged.
            // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
            Class<?> type = Class.forName("com.ultikits.ultitools.ui.InMemoryUiService$Registry");
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true); // NOPMD - test-only access to the unchanged contract fixture
            fixture = (Fixture) constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create the UI contract fixture", failure);
        }
        fixture.register(backend);
    }

    public UiService ui() { return fixture.service("DeclarativeTests"); }
    public Scheduler scheduler() { return fixture.scheduler(); }
    public Fixture fixture() { return fixture; }
    public ProbeBackend backend() { return backend; }
    public void tick() { fixture.advanceTicks(1); }
    public void drain() { fixture.advanceTicks(6); }
    public GridSession open(DeclarativeGui page) {
        GridSession session = page.open();
        sessions.put(page, session);
        drain();
        return session;
    }
    public Grid current(DeclarativeGui page) { return sessions.get(page).current(); }
    public GridSession session(DeclarativeGui page) { return sessions.get(page); }
    public void close(DeclarativeGui page) { sessions.get(page).close(); drain(); }
    public void click(DeclarativeGui page, int slot) { click(session(page), slot, ClickKind.LEFT); }
    public void click(GridSession session, int slot, ClickKind kind) {
        backend.callbacks(session).clicked(GridSlot.ofIndex(slot), kind);
        drain();
    }
    @Override public void close() { fixture.close(); }

    public static String plain(Text text) {
        return PlainTextComponentSerializer.plainText().serialize(((Text.Literal) text).component());
    }
    public static ItemSpec item(Grid grid, int slot) { return grid.item(GridSlot.ofIndex(slot)).orElseThrow().icon(); }
    public static String label(Grid grid, int slot) {
        return grid.item(GridSlot.ofIndex(slot)).map(item -> item.icon().name() == null
                ? item.icon().itemType().value().toUpperCase(Locale.ROOT)
                : plain(item.icon().name())).orElse("-");
    }

    /** Records complete snapshots, never rendering them into an inventory in these engine tests. */
    public static final class SnapshotProbe implements Consumer<Grid> {
        private final List<Grid> frames = new ArrayList<>();
        @Override public void accept(Grid grid) { frames.add(grid); }
        public Grid current() { return frames.get(frames.size() - 1); }
        public List<Grid> frames() { return List.copyOf(frames); }
        public ItemSpec item(int slot) { return GuiTestHarness.item(current(), slot); }
        public String label(int slot) { return GuiTestHarness.label(current(), slot); }
        public void click(int slot) {
            current().item(GridSlot.ofIndex(slot)).flatMap(GridItem::action)
                    .ifPresent(action -> action.onClick(mock(GridClick.class)));
        }
    }
}
