package com.ultikits.ultitools.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
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
}
