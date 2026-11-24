package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

class ReviewFixArgumentsTest {
    @Test void offlineNamesResolveOnlyWhenAnInvocationReadsTheValue() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            OfflinePlayer player = mock(OfflinePlayer.class);
            bukkit.when(() -> Bukkit.getOfflinePlayer("Alice")).thenReturn(player);
            Object parsed = new OfflinePlayerArgumentType().parse(new StringReader("Alice"));
            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), times(0));
            assertThat(parsed).isEqualTo("Alice");
            assertThat(new PaperNativeArgumentTypes().resolve(OfflinePlayer.class, parsed, null)).isSameAs(player);
            bukkit.verify(() -> Bukkit.getOfflinePlayer("Alice"), times(1));
        }
    }

    @Test void emptyOfflineNameIsASyntaxErrorRatherThanABukkitArgumentError() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("")).thenThrow(new IllegalArgumentException("empty name"));
            assertThatThrownBy(() -> new OfflinePlayerArgumentType().parse(new StringReader(" ")))
                    .isInstanceOf(CommandSyntaxException.class);
        }
    }
}
