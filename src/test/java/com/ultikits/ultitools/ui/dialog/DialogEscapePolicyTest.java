package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.ui.runtime.NoContainerRuntime;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Choice;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Confirm;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.FormResponse;
import com.ultikits.ultitools.ui.Notice;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.UiBackend.BackendCallbacks;
import com.ultikits.ultitools.ui.UiSession;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogEscapePolicyTest {
    private final Messages messages = mock(Messages.class);
    private final DialogPresenter presenter = mock(DialogPresenter.class);
    private final Player viewer = mock(Player.class);
    private final Logger logger = mock(Logger.class);
    private final ManualScheduler.Engine clock = new ManualScheduler.Engine();
    private final UiRuntime runtime = new UiRuntime(clock.scheduler("EscapePolicy"), new NoContainerRuntime(),
            logger, Thread.currentThread());
    private final DialogBackend backend = new DialogBackend(messages, presenter, logger, runtime::ownerName);

    @AfterEach
    void closeRuntime() {
        runtime.close();
        clock.close();
    }

    @BeforeEach
    void setUp() {
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(messages.render(any(Text.class), any(Audience.class))).thenAnswer(invocation -> {
            Text text = invocation.getArgument(0);
            return text instanceof Text.Literal literal ? literal.component() : Component.text(((Text.Translated) text).key().translationKey());
        });
    }

    @Test
    void noticeAndConfirm_cannotCloseWithEscape() {
        DialogMapper mapper = new DialogMapper(messages);
        assertThat(mapper.map(Notice.builder(Text.plain("t")).build(), viewer, DialogBackend.ESCAPE_CLOSES).escapeCloses()).isFalse();
        assertThat(mapper.map(Confirm.builder(Text.plain("t")).build(), viewer, DialogBackend.ESCAPE_CLOSES).escapeCloses()).isFalse();
        assertThat(DialogBackend.paper(messages, logger, runtime::ownerName).priority()).isEqualTo(100);
    }

    @Test
    void form_hasAnExplicitCancelButtonAndReportsPlayerClosed() {
        UiSession<FormResponse> session = runtime.service("EscapePolicy").show(viewer, Form.builder(Text.plain("t"))
                .input(TextInput.builder("name", Text.plain("Name")).build()).build());
        BackendCallbacks<FormResponse> callbacks = mock(BackendCallbacks.class);
        backend.open(session, callbacks);
        ArgumentCaptor<DialogModel> model = ArgumentCaptor.forClass(DialogModel.class);
        ArgumentCaptor<DialogTicket> ticket = ArgumentCaptor.forClass(DialogTicket.class);
        verify(presenter).show(eq(viewer), model.capture(), ticket.capture());
        assertThat(model.getValue().escapeCloses()).isFalse();
        assertThat(model.getValue().kind()).isEqualTo(DialogModel.Kind.CONFIRMATION);
        assertThat(model.getValue().buttons()).containsExactly(
                new DialogModel.Button(Component.text("ultitools:ui.submit"), null, new ButtonRole.Submit()),
                new DialogModel.Button(Component.text("ultitools:ui.cancel"), null, new ButtonRole.Cancel()));
        ticket.getValue().accept(model.getValue().buttons().get(1).role(), null, viewer);
        verify(callbacks).closed(CloseReason.PLAYER);
        verify(callbacks, never()).completed(any());
        backend.close(session);
        verify(presenter, never()).dismiss(any());
    }

    @Test
    void choice_hasAnExplicitCancelExitAndReportsPlayerClosed() {
        UiSession<String> session = runtime.service("EscapePolicy").show(viewer,
                Choice.<String>builder(Text.plain("t")).option("value", Text.plain("Pick")).build());
        BackendCallbacks<String> callbacks = mock(BackendCallbacks.class);
        backend.open(session, callbacks);
        ArgumentCaptor<DialogModel> model = ArgumentCaptor.forClass(DialogModel.class);
        ArgumentCaptor<DialogTicket> ticket = ArgumentCaptor.forClass(DialogTicket.class);
        verify(presenter).show(eq(viewer), model.capture(), ticket.capture());
        assertThat(model.getValue().escapeCloses()).isFalse();
        assertThat(model.getValue().kind()).isEqualTo(DialogModel.Kind.MULTI_ACTION);
        assertThat(model.getValue().exit()).isEqualTo(
                new DialogModel.Button(Component.text("ultitools:ui.cancel"), null, new ButtonRole.Cancel()));
        ticket.getValue().accept(model.getValue().exit().role(), null, viewer);
        verify(callbacks).closed(CloseReason.PLAYER);
        verify(callbacks, never()).completed(any());
        backend.close(session);
        verify(presenter, never()).dismiss(any());
    }
}
