package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ultikits.ultitools.text.Text;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Runs the reusable display contract against the correct in-memory implementation. */
class InMemoryUiServiceContractTest extends AbstractUiServiceContractTest {
    @Override protected Fixture createFixture() { return new InMemoryUiService.Registry(); }

    @Test
    void defaultTimeout_isTenMinutesForPromptsAndNoneForGrid() {
        ProbeBackend backend = backend(Notice.class, Confirm.class, Form.class, Choice.class, Grid.class);
        UiSession<Ack> notice = service.show(viewer, Notice.builder(TITLE).build());
        UiSession<Boolean> confirm = service.show(server.addPlayer(), confirm(), ShowOptions.builder().noTimeout().build());
        UiSession<FormResponse> form = service.show(server.addPlayer(), Form.builder(TITLE)
                .input(ToggleInput.of("flag", TITLE, false)).build());
        UiSession<String> choice = service.show(server.addPlayer(), Choice.<String>builder(TITLE).option("a", TITLE).build());
        GridSession grid = service.show(server.addPlayer(), Grid.builder(TITLE, 1).build());
        fixture.advanceTicks(12_005);
        assertThat(outcome(notice)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.TIMED_OUT));
        assertThat(outcome(confirm)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.TIMED_OUT));
        assertThat(outcome(form)).isEqualTo(new UiResult.Closed<FormResponse>(CloseReason.TIMED_OUT));
        assertThat(outcome(choice)).isEqualTo(new UiResult.Closed<String>(CloseReason.TIMED_OUT));
        assertThat(grid.isOpen()).isTrue();
        assertThat(backend.closed()).hasSize(4);
    }

    @Test
    void grid_canTimeOutButNeverCompletesWithAnAnswer() {
        ProbeBackend backend = backend(Grid.class);
        GridSession timed = service.show(viewer, Grid.builder(TITLE, 1).build(),
                ShowOptions.builder().timeout(Duration.ofSeconds(1)).build());
        settle();
        backend.callbacks(timed).completed(Ack.ACK);
        settle();
        assertThat(timed.isOpen()).isTrue();
        fixture.advanceTicks(25);
        assertThat(outcome(timed)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.TIMED_OUT));
        GridSession untimed = service.show(viewer, Grid.builder(TITLE, 1).build(), ShowOptions.builder().noTimeout().build());
        fixture.advanceTicks(20_000);
        assertThat(untimed.isOpen()).isTrue();
    }

    @Test
    void genericGridShow_stillReturnsGridSessionAndUpdatesClickSnapshot() {
        ProbeBackend backend = backend(Grid.class);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        UiIntent<Ack> intent = Grid.builder(TITLE, 1).set(0, 0, ICON, click -> oldCalls.incrementAndGet()).build();
        UiSession<Ack> shown = service.show(viewer, intent);
        assertThat(shown).isInstanceOf(GridSession.class);
        GridSession grid = (GridSession) shown;
        settle();
        grid.update(Grid.builder(TITLE, 1).set(0, 0, ICON, click -> newCalls.incrementAndGet()).build());
        backend.callbacks(grid).clicked(GridSlot.of(0, 0), ClickKind.LEFT);
        settle();
        assertThat(oldCalls).hasValue(0);
        assertThat(newCalls).hasValue(1);
        grid.close();
        settle();
        backend.callbacks(grid).clicked(GridSlot.of(0, 0), ClickKind.LEFT);
        settle();
        assertThat(newCalls).hasValue(1);
    }

    @Test
    void quitBetweenReportAndEntityTask_completesOnGlobalThread() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        settle();
        AtomicReference<Boolean> globalThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> globalThread.set(RecordingScheduler.inGlobalTask()));
        backend.callbacks(session).closed(CloseReason.DISCONNECTED);
        fixture.disconnect(viewer);
        settle();
        assertThat(globalThread).hasValue(true);
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.DISCONNECTED));
        assertThat(backend.closed()).containsExactly(session);
    }

    @Test
    void unloadBeforeOpen_doesNotOpenScreen() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> session = service.show(viewer, confirm());
        fixture.unload(service.owner());
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.OWNER_UNLOADED));
        assertThat(backend.opened()).isEmpty();
    }

    @Test
    void showFromAnotherThread_isDeferredToViewerTask() throws InterruptedException {
        backend(Confirm.class);
        AtomicReference<UiSession<Boolean>> reference = new AtomicReference<>();
        Thread caller = new Thread(() -> reference.set(service.show(viewer, confirm())), "ui-test-caller");
        caller.start();
        caller.join();
        UiSession<Boolean> session = reference.get();
        AtomicReference<Boolean> entityThread = new AtomicReference<>();
        session.result().thenAccept(ignored -> entityThread.set(RecordingScheduler.inEntityTask(viewer)));
        settle();
        Thread closer = new Thread(session::close, "ui-test-closer");
        closer.start();
        closer.join();
        settle();
        assertThat(outcome(session)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.CLOSED_BY_CODE));
        assertThat(entityThread).hasValue(true);
    }

    @Test
    void unsupportedSecondShow_stillReplacesPreviousSession() {
        ProbeBackend backend = backend(Confirm.class);
        UiSession<Boolean> first = service.show(viewer, confirm());
        settle();
        UiSession<Ack> unsupported = service.show(viewer, Notice.builder(TITLE).build());
        settle();
        assertThat(outcome(first)).isEqualTo(new UiResult.Closed<Boolean>(CloseReason.REPLACED));
        assertThat(outcome(unsupported)).isEqualTo(new UiResult.Closed<Ack>(CloseReason.UNSUPPORTED));
        assertThat(backend.closed()).containsExactly(first);
    }

    @Test
    void serviceAndGridSession_rejectNullArguments() {
        backend(Grid.class);
        Grid grid = Grid.builder(TITLE, 1).build();
        assertThatNullPointerException().isThrownBy(() -> service.show(null, grid)).withMessage("viewer");
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, (Grid) null)).withMessage("grid");
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, (UiIntent<Boolean>) null)).withMessage("intent");
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, grid, null)).withMessage("options");
        assertThatNullPointerException().isThrownBy(() -> service.show(viewer, confirm(), null)).withMessage("options");
        GridSession session = service.show(viewer, grid);
        assertThatNullPointerException().isThrownBy(() -> session.update(null)).withMessage("next");
        assertThat(session.viewer()).isSameAs(viewer);
        assertThat(session.current().title()).isEqualTo(Text.plain("Contract"));
    }
}
