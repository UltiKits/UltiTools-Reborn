package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState;
import static com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.manager.ChatCallbackManager;
import com.ultikits.ultitools.widgets.impl.ChatConfirm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Interactive components must reach the player with their click and hover events intact.
 *
 * <p>Measured on Paper 1.21.11 (phase 18 real-machine session, reproduced on a scratch server):
 * {@code MessageUtils.sendMessage(Player, TextComponent)} used to send through
 * {@code adventure-platform-bukkit} 4.3.2's {@code BukkitAudiences}. On that server version the
 * library's reflective NMS serializer is unsupported ({@code MinecraftComponentSerializer.isSupported()}
 * logged {@code false}), so its chat facet falls back to legacy section-sign text: every click and
 * hover event is dropped and each {@code \n} becomes a separate chat line. The {@code /upm list}
 * install/uninstall buttons and the chat confirm dialog's buttons therefore did nothing when clicked.
 *
 * <p>These tests assert on what the player object actually receives, so they fail whenever the
 * component is routed anywhere other than the player's own (native Paper) {@code Audience}.
 *
 * <p>中文：交互组件必须带着点击/悬停事件原样送达玩家。旧实现经 adventure-platform-bukkit 4.3.2 发送，
 * 在 Paper 1.21.11 上退化为旧式颜色码文本，点击事件全部丢失。
 */
@DisplayName("Interactive component delivery keeps click and hover events")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MessageUtilsComponentDeliveryTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        ensureCleanState();
        server = MockBukkit.mock();
        // A plugin identity is stubbed so a platform-library send path (the defect) can run to
        // completion and be observed, instead of failing early on a missing description.
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            org.mockito.Mockito.lenient().when(ultiTools.getName()).thenReturn("UltiTools");
            org.mockito.Mockito.lenient().when(ultiTools.getDescription()).thenReturn(
                    new org.bukkit.plugin.PluginDescriptionFile("UltiTools", "6.3.0", "com.ultikits.ultitools.UltiTools"));
            org.mockito.Mockito.lenient().when(ultiTools.getLogger())
                    .thenReturn(java.util.logging.Logger.getLogger("UltiTools"));
        });
        player = server.addPlayer("clicker");
    }

    @AfterEach
    void tearDown() {
        safeUnmock();
    }

    @Test
    @DisplayName("sendMessage(Player, TextComponent) delivers the click and hover events to the player")
    void sendMessage_keepsClickAndHoverEvents() {
        TextComponent button = Component.text("     | Uninstall |     \n")
                .hoverEvent(Component.text("Click to uninstall"))
                .clickEvent(ClickEvent.runCommand("/upm uninstall UltiTools-Economy"));

        MessageUtils.sendMessage(player, button);

        Component received = player.nextComponentMessage();
        assertThat(received)
                .as("the player must receive the component itself, not a legacy-text rendering of it")
                .isNotNull();
        assertThat(clickCommands(received))
                .as("the click event is what makes the button do anything")
                .containsExactly("/upm uninstall UltiTools-Economy");
        assertThat(hasHover(received)).as("hover text must survive too").isTrue();
        assertThat(player.nextComponentMessage())
                .as("one component is one chat message; a newline inside it must not split it")
                .isNull();
    }

    @Test
    @DisplayName("ChatConfirm.show delivers both button click events to the player")
    void chatConfirm_buttonsKeepClickEvents() {
        UUID confirmId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID cancelId = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Runnable onConfirm = () -> { };
        Runnable onCancel = () -> { };
        try (MockedStatic<ChatCallbackManager> callbacks = mockStatic(ChatCallbackManager.class)) {
            callbacks.when(() -> ChatCallbackManager.registerCallback(any()))
                    .thenAnswer(inv -> inv.getArgument(0) == onConfirm ? confirmId : cancelId);

            new ChatConfirm(player, "Title", "Description", onConfirm, onCancel).show();
        }

        List<String> commands = new ArrayList<>();
        Component received;
        while ((received = player.nextComponentMessage()) != null) {
            commands.addAll(clickCommands(received));
        }
        assertThat(commands)
                .as("the confirm and cancel buttons must each carry their callback command")
                .containsExactly("/ultitools_callback " + confirmId, "/ultitools_callback " + cancelId);
    }

    /**
     * Collects every run-command click value in the component tree, depth first.
     */
    static List<String> clickCommands(Component root) {
        List<String> out = new ArrayList<>();
        collectClicks(root, out);
        return out;
    }

    private static void collectClicks(Component node, List<String> out) {
        ClickEvent click = node.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
            out.add(click.value());
        }
        for (Component child : node.children()) {
            collectClicks(child, out);
        }
    }

    private static boolean hasHover(Component node) {
        HoverEvent<?> hover = node.hoverEvent();
        if (hover != null) {
            return true;
        }
        for (Component child : node.children()) {
            if (hasHover(child)) {
                return true;
            }
        }
        return false;
    }
}
