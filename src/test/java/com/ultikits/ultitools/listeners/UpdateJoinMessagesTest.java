package com.ultikits.ultitools.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ManagerKeys;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class UpdateJoinMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void noticeUsesPlayerLocaleAndRetainsColors(String code) throws Exception {
        var player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.locale()).thenReturn(Locale.forLanguageTag(code));
        var updates = mock(UpdateManager.class);
        when(updates.isCheckComplete()).thenReturn(true);
        when(updates.hasAnyUpdates()).thenReturn(true);
        when(updates.getModuleUpdates()).thenReturn(Map.of("Probe", new UpdateInfo()));
        when(updates.getFrameworkUpdate()).thenReturn(new UpdateInfo());
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.ENGLISH)) {
            new UpdateJoinListener(updates).onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
            var captor = ArgumentCaptor.forClass(Component.class);
            verify(player).sendMessage(captor.capture());
            assertThat(CommandMessageTestSupport.plain(captor.getValue())).isEqualTo("[UltiTools] 2 update(s) available. Run /upm check for details.");
            assertThat(LegacyComponentSerializer.legacySection().serialize(captor.getValue())).isEqualTo("§a[UltiTools] §e2 update(s) available. Run /upm check for details.");
            verify(updates).markPlayerNotified(player.getUniqueId());
        }
    }

    @Test
    void stringArgumentInJoinNoticeReachesPlayerAsLiteralText(@TempDir Path messagesDir) throws Exception {
        // The shipped notice only takes a count. This test-only override supplies a string slot
        // so the same key and player delivery path can exercise untrusted text.
        Files.writeString(messagesDir.resolve("en.yml"), """
                update:
                  join_notice: "<green>[UltiTools] <yellow><count:'0'>: <detail>"
                """);
        var player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.ENGLISH);
        doCallRealMethod().when(player).forEachAudience(any());
        var logger = Logger.getAnonymousLogger();
        var resolver = new ConfiguredLocaleResolver(() -> "auto", () -> "en", logger);
        String input = "<red> <click:run_command:/op x> &c";
        try (var engine = new TextEngine(resolver, logger,
                UltiTools.class.getProtectionDomain().getCodeSource().getLocation(), messagesDir)) {
            engine.messagesFor("ultitools").send(player, ManagerKeys.UPDATE_JOIN_NOTICE,
                    Arg.number("count", 2), Arg.string("detail", input));
        }

        var delivered = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(delivered.capture());
        var component = delivered.getValue();
        assertThat(CommandMessageTestSupport.plain(component)).isEqualTo("[UltiTools] 2: " + input);
        assertThat(LegacyComponentSerializer.legacySection().serialize(component))
                .isEqualTo("§a[UltiTools] §e2: " + input);
        assertThat(component.iterable(ComponentIteratorType.DEPTH_FIRST))
                .allSatisfy(part -> assertThat(part.clickEvent()).isNull());
    }
}
