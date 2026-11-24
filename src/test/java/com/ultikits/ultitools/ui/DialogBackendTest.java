package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.dialog.ButtonRole;
import com.ultikits.ultitools.ui.dialog.DialogBackend;
import com.ultikits.ultitools.ui.dialog.DialogModel;
import com.ultikits.ultitools.ui.dialog.DialogPresenter;
import com.ultikits.ultitools.ui.dialog.DialogTicket;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(20)
class DialogBackendTest {
    private RuntimeFixture fixture;
    private UiService service;
    private PlayerMock viewer;
    private RecordingPresenter presenter;
    private DialogBackend backend;
    private Logger logger;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        viewer = server.addPlayer("Viewer");
        fixture = new RuntimeFixture();
        service = fixture.service("DialogModule");
        presenter = new RecordingPresenter();
        logger = mock(Logger.class);
        Messages messages = mock(Messages.class);
        when(messages.render(any(Text.class), any(Audience.class))).thenAnswer(invocation -> {
            Text text = invocation.getArgument(0);
            return text instanceof Text.Literal literal ? literal.component() : Component.text(((Text.Translated) text).key().translationKey());
        });
        backend = new DialogBackend(messages, presenter, logger);
        fixture.register(backend);
    }

    @AfterEach
    void tearDown() {
        try {
            if (fixture != null) {
                fixture.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    private UiSession<Boolean> confirm() {
        UiSession<Boolean> session = service.show(viewer, Confirm.builder(Text.plain("Confirm")).build());
        fixture.advanceTicks(1);
        return session;
    }

    private UiResult<?> result(UiSession<?> session) throws Exception {
        fixture.advanceTicks(2);
        return session.result().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void buttons_reportNoticeConfirmFormAndChoiceThroughRealRuntime() throws Exception {
        UiSession<Ack> notice = service.show(viewer, Notice.builder(Text.plain("t")).build());
        fixture.advanceTicks(1);
        presenter.last().accept(new ButtonRole.Ack(), null, viewer);
        assertThat(notice.result().toCompletableFuture()).isNotDone();
        assertThat(result(notice)).isEqualTo(new UiResult.Completed<>(Ack.ACK));
        UiSession<Boolean> confirm = confirm();
        presenter.last().accept(new ButtonRole.Yes(), null, viewer);
        assertThat(result(confirm)).isEqualTo(new UiResult.Completed<>(true));
        TextInput input = TextInput.builder("name", Text.plain("Name")).build();
        UiSession<FormResponse> form = service.show(viewer, Form.builder(Text.plain("t")).input(input).build());
        fixture.advanceTicks(1);
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getText("name")).thenReturn("answer");
        presenter.last().accept(new ButtonRole.Submit(), response, viewer);
        assertThat(((FormResponse) result(form).value()).get(input)).isEqualTo("answer");
        UiSession<String> choice = service.show(viewer, Choice.<String>builder(Text.plain("t")).option("chosen", Text.plain("Pick")).build());
        fixture.advanceTicks(1);
        presenter.last().accept(new ButtonRole.Pick(0), null, viewer);
        assertThat(result(choice)).isEqualTo(new UiResult.Completed<>("chosen"));
        assertThat(presenter.dismissed).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "cancel", "timeout", "replace", "quit", "unload", "code"})
    void confirm_noAndEveryCloseNeverYieldTrue(String ending) throws Exception {
        UiSession<Boolean> session = confirm();
        AtomicInteger completed = new AtomicInteger();
        session.result().thenAccept(ignored -> completed.incrementAndGet());
        CloseReason reason = switch (ending) {
            case "no" -> { presenter.last().accept(new ButtonRole.No(), null, viewer); yield null; }
            case "cancel" -> { presenter.last().accept(new ButtonRole.Cancel(), null, viewer); yield CloseReason.PLAYER; }
            case "timeout" -> { fixture.advanceTicks(12_001); yield CloseReason.TIMED_OUT; }
            case "replace" -> { service.show(viewer, Notice.builder(Text.plain("next")).build()); yield CloseReason.REPLACED; }
            case "quit" -> { fixture.disconnect(viewer); yield CloseReason.DISCONNECTED; }
            case "unload" -> { fixture.unload("DialogModule"); yield CloseReason.OWNER_UNLOADED; }
            default -> { session.close(); yield CloseReason.CLOSED_BY_CODE; }
        };
        UiResult<?> outcome = result(session);
        assertThat(outcome).isNotEqualTo(new UiResult.Completed<>(true));
        assertThat(outcome).isEqualTo(reason == null ? new UiResult.Completed<>(false) : new UiResult.Closed<>(reason));
        assertThat(completed).hasValue(1);
    }

    @Test
    void pressedButton_doesNotDismissTheNextDialogOrReportTwice() throws Exception {
        UiSession<Boolean> first = confirm();
        DialogTicket stale = presenter.last();
        first.result().thenAccept(ignored -> service.show(viewer, Notice.builder(Text.plain("next")).build()));
        stale.accept(new ButtonRole.No(), null, viewer);
        stale.accept(new ButtonRole.Yes(), null, viewer);
        assertThat(result(first)).isEqualTo(new UiResult.Completed<>(false));
        assertThat(presenter.models).hasSize(2);
        assertThat(presenter.dismissed).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace", "timeout", "code", "unload"})
    void unpressedDialog_dismissesExactlyOnce(String ending) throws Exception {
        UiSession<Boolean> session = confirm();
        switch (ending) {
            case "replace" -> service.show(viewer, Notice.builder(Text.plain("next")).build());
            case "timeout" -> fixture.advanceTicks(12_001);
            case "unload" -> fixture.unload("DialogModule");
            default -> session.close();
        }
        assertThat(result(session)).isInstanceOf(UiResult.Closed.class);
        session.close();
        fixture.advanceTicks(1);
        assertThat(presenter.dismissed).containsExactly(viewer);
    }

    @Test
    void disconnectedViewer_isNeverDismissed() throws Exception {
        UiSession<Boolean> session = confirm();
        fixture.disconnect(viewer);
        assertThat(result(session)).isEqualTo(new UiResult.Closed<>(CloseReason.DISCONNECTED));
        assertThat(presenter.dismissed).isEmpty();
    }

    @Test
    void lateCallback_afterCloseDoesNotReport() {
        UiSession<Boolean> session = mock(UiSession.class);
        when(session.viewer()).thenReturn(viewer);
        when(session.intent()).thenReturn(Confirm.builder(Text.plain("t")).build());
        UiBackend.BackendCallbacks<Boolean> callbacks = mock(UiBackend.BackendCallbacks.class);
        backend.open(session, callbacks);
        DialogTicket ticket = presenter.last();
        backend.close(session);
        ticket.accept(new ButtonRole.Yes(), null, viewer);
        verifyNoInteractions(callbacks);
        assertThat(presenter.dismissed).containsExactly(viewer);
    }

    @Test
    void wrongAudience_isIgnoredWithoutConsumingTheTicket() throws Exception {
        UiSession<Boolean> session = confirm();
        presenter.last().accept(new ButtonRole.Yes(), null, Audience.empty());
        presenter.last().accept(new ButtonRole.Yes(), null, MockBukkit.getMock().addPlayer("Stranger"));
        assertThat(session.isOpen()).isTrue();
        presenter.last().accept(new ButtonRole.No(), null, viewer);
        assertThat(result(session)).isEqualTo(new UiResult.Completed<>(false));
    }

    @Test
    void supports_rejectsUnrepresentableSlidersAndGrids() {
        assertThat(backend.supports(Confirm.builder(Text.plain("t")).build(), viewer)).isTrue();
        assertThat(backend.supports(Notice.builder(Text.plain("t")).build(), viewer)).isTrue();
        assertThat(backend.supports(Choice.<Integer>builder(Text.plain("t")).option(1, Text.plain("one")).build(), viewer)).isTrue();
        assertThat(backend.supports(Grid.builder(Text.plain("g"), 1).build(), viewer)).isFalse();
        for (SliderInput slider : List.of(
                SliderInput.builder("a", Text.plain("a"), 16_777_216, 16_777_218).step(1).initial(16_777_217).build(),
                SliderInput.builder("a", Text.plain("a"), 16_777_217, 16_777_219).step(1).build(),
                SliderInput.builder("a", Text.plain("a"), 16_777_216, 16_777_217).step(1).build(),
                SliderInput.builder("a", Text.plain("a"), 0, 1e-46).step(1e-46).build(),
                SliderInput.builder("a", Text.plain("a"), 0, 1e100).step(1e100).build())) {
            assertThat(backend.supports(Form.builder(Text.plain("t")).input(slider).build(), viewer)).isFalse();
        }
        SliderInput ordinary = SliderInput.builder("level", Text.plain("Level"), 0, 10).step(0.1).initial(0.3).build();
        assertThat(backend.supports(Form.builder(Text.plain("t")).input(ordinary).build(), viewer)).isTrue();
    }

    @Test
    void priority100_winsOverPriorityZero() {
        AtomicInteger fallbackOpens = new AtomicInteger();
        fixture.register(new UiBackend() {
            @Override public Key id() { return Key.key("probe", "fallback"); }
            @Override public int priority() { return 0; }
            @Override public boolean supports(UiIntent<?> intent, Player player) { return true; }
            @Override public <R> void open(UiSession<R> session, BackendCallbacks<R> callbacks) { fallbackOpens.incrementAndGet(); }
            @Override public void update(GridSession session, Grid previous, Grid next) { /* Not a grid probe. */ }
            @Override public void close(UiSession<?> session) { /* The probe owns no resources. */ }
        });
        confirm();
        assertThat(backend.id()).isEqualTo(Key.key("ultitools", "dialog"));
        assertThat(backend.priority()).isEqualTo(100);
        assertThat(presenter.models).hasSize(1);
        assertThat(fallbackOpens).hasValue(0);
        assertThatThrownBy(() -> backend.update(mock(GridSession.class), Grid.builder(Text.plain("a"), 1).build(),
                Grid.builder(Text.plain("b"), 1).build())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectedForm_closesWithoutCompletingAnInvalidAnswer() throws Exception {
        TextInput input = TextInput.builder("name", Text.plain("Name")).maxLength(3).build();
        UiSession<FormResponse> session = service.show(viewer, Form.builder(Text.plain("Form")).input(input).build());
        fixture.advanceTicks(1);
        DialogResponseView response = mock(DialogResponseView.class);
        when(response.getText("name")).thenReturn("too long");
        presenter.last().accept(new ButtonRole.Submit(), response, viewer);
        assertThat(result(session)).isEqualTo(new UiResult.Closed<>(CloseReason.PLAYER));
        assertThat(presenter.dismissed).isEmpty();
    }

    @Test
    void closeWithoutAnOpenedTicket_isAnIdempotentNoOp() {
        UiSession<?> session = mock(UiSession.class);
        backend.close(session);
        backend.close(session);
        assertThat(presenter.models).isEmpty();
        assertThat(presenter.dismissed).isEmpty();
    }

    private static final class RecordingPresenter implements DialogPresenter {
        private final List<DialogModel> models = new ArrayList<>();
        private final List<DialogTicket> tickets = new ArrayList<>();
        private final List<Player> dismissed = new ArrayList<>();
        @Override public void show(Player player, DialogModel model, DialogTicket ticket) {
            models.add(model);
            tickets.add(ticket);
        }
        @Override public void dismiss(Player player) { dismissed.add(player); }
        DialogTicket last() { return tickets.getLast(); }
    }
}
