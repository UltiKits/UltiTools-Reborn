package com.ultikits.ultitools.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

class TeleportTitleMessagesTest {
    @AfterEach
    void cleanup() {
        InMemeryTeleportService.clearTeleportingData();
        MockBukkitHelper.safeUnmock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void allTitlesUseThePlayersLanguageAndOriginalDurations(String code) throws Exception {
        MockBukkitHelper.ensureCleanState();
        var server = MockBukkit.mock();
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.locale()).thenReturn(Locale.forLanguageTag(code));
        var location = new Location(server.addSimpleWorld("titles"), 0, 64, 0);
        when(player.getLocation()).thenReturn(location);
        try (var support = new CommandMessageTestSupport(code.equals("en") ? "zh" : "en");
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.ENGLISH)) {
            InMemeryTeleportService.setTeleportingStatus(player.getUniqueId(), true);
            InMemeryTeleportService.processDelayTeleportTick(player, location, 3);
            InMemeryTeleportService.processDelayTeleportTick(player, location, 0);
            InMemeryTeleportService.processDelayTeleportTick(player, location, 2);
            var captor = ArgumentCaptor.forClass(Title.class);
            verify(player, times(3)).showTitle(captor.capture());
            var titles = captor.getAllValues();
            var legacy = LegacyComponentSerializer.legacySection();
            assertThat(legacy.serialize(titles.get(0).title())).isEqualTo(code.equals("en") ? "§aTeleporting..." : "§a传送中...");
            assertThat(CommandMessageTestSupport.plain(titles.get(0).subtitle())).isEqualTo(code.equals("en") ? "Teleport in 3 ..." : "离传送还有3秒");
            assertThat(legacy.serialize(titles.get(1).title())).isEqualTo(code.equals("en") ? "§aTeleport Succeed!" : "§a传送成功！");
            assertThat(CommandMessageTestSupport.plain(titles.get(1).subtitle())).isEmpty();
            assertThat(legacy.serialize(titles.get(2).title())).isEqualTo(code.equals("en") ? "§cTeleport Failed!" : "§c传送失败！");
            assertThat(CommandMessageTestSupport.plain(titles.get(2).subtitle())).isEqualTo(code.equals("en") ? "Please Do Not Move!" : "请勿移动！");
            for (int i = 0; i < titles.size(); i++) {
                assertThat(titles.get(i).times().fadeIn()).isEqualTo(Duration.ofMillis(500));
                assertThat(titles.get(i).times().stay()).isEqualTo(Duration.ofMillis(i == 0 ? 3500 : 2500));
                assertThat(titles.get(i).times().fadeOut()).isEqualTo(Duration.ofSeconds(1));
            }
            verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }
    }
}
