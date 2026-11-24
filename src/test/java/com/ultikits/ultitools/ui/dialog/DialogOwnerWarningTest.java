package com.ultikits.ultitools.ui.dialog;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Form;
import com.ultikits.ultitools.ui.TextInput;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.runtime.NoContainerRuntime;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(15)
class DialogOwnerWarningTest {
    private ManualScheduler.Engine clock;
    private UiRuntime runtime;
    private Logger logger;
    private PlayerMock viewer;
    private DialogBackend backend;
    private final AtomicReference<DialogTicket> ticket = new AtomicReference<>();
    private final AtomicInteger nameReads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        viewer = MockBukkit.mock().addPlayer("Alice");
        clock = new ManualScheduler.Engine();
        logger = mock(Logger.class);
        runtime = new UiRuntime(clock.scheduler("OwnerWarning"), new NoContainerRuntime(), logger, Thread.currentThread());
        Messages messages = mock(Messages.class);
        when(messages.render(any(Text.class), any(Audience.class))).thenReturn(Component.text("Form"));
        DialogPresenter presenter = new DialogPresenter() {
            @Override public void show(Player player, DialogModel model, DialogTicket shown) { ticket.set(shown); }
            @Override public void dismiss(Player player) { /* No client in this test. */ }
        };
        backend = new DialogBackend(messages, presenter, logger, session -> {
            assertThat(session.isOpen()).as("owner is resolved while opening, never in a late callback").isTrue();
            nameReads.incrementAndGet();
            return runtime.ownerName(session);
        });
    }

    @AfterEach
    void tearDown() {
        try {
            runtime.close();
            clock.close();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void rejectedAnswerNamesTheOwnerExactlyOnce() {
        runtime.register(backend);
        UiSession<?> session = show();
        ticket.get().accept(new ButtonRole.Submit(), mock(DialogResponseView.class), viewer);
        clock.advanceTicks(2);
        assertThat(session.result().toCompletableFuture()).isCompletedWithValue(new UiResult.Closed<>(CloseReason.PLAYER));
        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(Level.WARNING), warning.capture(), any(IllegalArgumentException.class));
        assertThat(warning.getValue()).contains("alpha").doesNotContain("unavailable");
        assertThat(nameReads).hasValue(1);
    }

    @Test
    void differentAudienceWarningNamesTheOwner() {
        runtime.register(backend);
        UiSession<?> session = show();
        ticket.get().accept(new ButtonRole.Submit(), null, MockBukkit.getMock().addPlayer("Bob"));
        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(logger).warning(warning.capture());
        assertThat(warning.getValue()).contains("alpha").doesNotContain("unavailable");
        assertThat(session.isOpen()).isTrue();
        assertThat(nameReads).hasValue(1);
    }

    @Test
    void retainedCallbackUsesTheNameCapturedBeforeTheRuntimeRemovesTheSession() {
        UiBackend screenOwner = mock(UiBackend.class);
        when(screenOwner.supports(any(UiIntent.class), any(Player.class))).thenReturn(true);
        runtime.register(screenOwner);
        UiSession<?> session = show();
        // Keep a separate ticket alive to probe its snapshot after the real runtime has removed the session.
        backend.open(session, mock(UiBackend.BackendCallbacks.class));
        assertThat(nameReads).hasValue(1);
        session.close();
        clock.advanceTicks(2);
        assertThat(session.result().toCompletableFuture()).isDone();
        ticket.get().accept(new ButtonRole.Submit(), mock(DialogResponseView.class), viewer);
        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(Level.WARNING), warning.capture(), any(IllegalArgumentException.class));
        assertThat(warning.getValue()).contains("alpha").doesNotContain("unavailable");
        assertThat(nameReads).hasValue(1);
        backend.close(session);
    }

    private UiSession<?> show() {
        UiSession<?> session = runtime.service("alpha").show(viewer,
                Form.builder(Text.plain("Form")).input(TextInput.builder("name", Text.plain("Name")).build()).build());
        clock.advanceTicks(1);
        return session;
    }
}
