package com.ultikits.plugins.essentials.utils;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MessageUtils.
 * <p>
 * 测试消息工具类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("MessageUtils Tests")
class MessageUtilsTest {

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        // Mock server-wide methods that parsePlaceholders always calls
        when(EssentialsTestHelper.getMockServer().getOnlinePlayers()).thenReturn(new ArrayList<>());
        when(EssentialsTestHelper.getMockServer().getMaxPlayers()).thenReturn(100);
        when(EssentialsTestHelper.getMockServer().getName()).thenReturn("TestServer");
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("colorize")
    class ColorizeTests {

        @Test
        @DisplayName("Should translate color codes")
        void shouldTranslateColorCodes() {
            String result = MessageUtils.colorize("&aGreen &cRed");
            // ChatColor.translateAlternateColorCodes converts & to section sign
            assertThat(result).contains("\u00a7a");
            assertThat(result).contains("\u00a7c");
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            String result = MessageUtils.colorize(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return text without color codes unchanged")
        void shouldReturnPlainTextUnchanged() {
            String result = MessageUtils.colorize("Hello World");
            assertThat(result).isEqualTo("Hello World");
        }
    }

    @Nested
    @DisplayName("stripColor")
    class StripColorTests {

        @Test
        @DisplayName("Should strip color codes")
        void shouldStripColorCodes() {
            // First colorize, then strip
            String colored = MessageUtils.colorize("&aGreen &cRed");
            String stripped = MessageUtils.stripColor(colored);
            assertThat(stripped).isEqualTo("Green Red");
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            String result = MessageUtils.stripColor(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return plain text unchanged")
        void shouldReturnPlainTextUnchanged() {
            String result = MessageUtils.stripColor("Hello World");
            assertThat(result).isEqualTo("Hello World");
        }
    }

    @Nested
    @DisplayName("parsePlaceholders")
    class ParsePlaceholdersTests {

        @Test
        @DisplayName("Should replace player name placeholders")
        void shouldReplacePlayerNamePlaceholders() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "Hello %player_name%!");
            assertThat(result).contains("Steve");
        }

        @Test
        @DisplayName("Should replace {player} placeholder")
        void shouldReplaceBracketPlayerPlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Alex", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "Welcome {player}");
            assertThat(result).contains("Alex");
        }

        @Test
        @DisplayName("Should replace %player% placeholder")
        void shouldReplacePercentPlayerPlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Alex", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "Welcome %player%");
            assertThat(result).contains("Alex");
        }

        @Test
        @DisplayName("Should replace health placeholder")
        void shouldReplaceHealthPlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "HP: %health%");
            assertThat(result).contains("20");
        }

        @Test
        @DisplayName("Should replace food placeholder")
        void shouldReplaceFoodPlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "Food: %food%");
            assertThat(result).contains("20");
        }

        @Test
        @DisplayName("Should replace level placeholder")
        void shouldReplaceLevelPlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "Level: %level%");
            assertThat(result).contains("30");
        }

        @Test
        @DisplayName("Should replace world placeholder")
        void shouldReplaceWorldPlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            String result = MessageUtils.parsePlaceholders(player, "World: %world%");
            assertThat(result).contains("world");
        }

        @Test
        @DisplayName("Should replace displayname placeholder")
        void shouldReplaceDisplayNamePlaceholder() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            // Default display name is same as name in the test helper

            String result = MessageUtils.parsePlaceholders(player, "Name: %displayname%");
            assertThat(result).contains("Steve");
        }

        @Test
        @DisplayName("Should handle null player for server-wide placeholders")
        void shouldHandleNullPlayer() throws Exception {
            String result = MessageUtils.parsePlaceholders(null, "Max: %max_players%");
            assertThat(result).isEqualTo("Max: 100");
        }

        @Test
        @DisplayName("Should handle null text")
        void shouldHandleNullText() throws Exception {
            String result = MessageUtils.parsePlaceholders(null, null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should replace server name placeholder")
        void shouldReplaceServerNamePlaceholder() throws Exception {
            String result = MessageUtils.parsePlaceholders(null, "Server: %server_name%");
            assertThat(result).isEqualTo("Server: TestServer");
        }

        @Test
        @DisplayName("Should replace online players placeholder")
        void shouldReplaceOnlinePlayersPlaceholder() throws Exception {
            String result = MessageUtils.parsePlaceholders(null, "Online: %online_players%");
            assertThat(result).isEqualTo("Online: 0");
        }
    }

    @Nested
    @DisplayName("sendMessage")
    class SendMessageTests {

        @Test
        @DisplayName("Should send formatted message to player")
        void shouldSendFormattedMessage() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            MessageUtils.sendMessage(player, "&aHello %player_name%!");

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not throw for null player")
        void shouldNotThrowForNullPlayer() {
            // Should not throw
            MessageUtils.sendMessage(null, "Hello");
        }

        @Test
        @DisplayName("Should not send for null message")
        void shouldNotSendForNullMessage() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            MessageUtils.sendMessage(player, null);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not send for empty message")
        void shouldNotSendForEmptyMessage() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            MessageUtils.sendMessage(player, "");

            verify(player, never()).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("formatLocation")
    class FormatLocationTests {

        @Test
        @DisplayName("Should format location correctly")
        void shouldFormatLocationCorrectly() {
            String result = MessageUtils.formatLocation("world", 100.5, 64.0, -200.5);
            assertThat(result).isEqualTo("world (100.5, 64.0, -200.5)");
        }

        @Test
        @DisplayName("Should format with zero coordinates")
        void shouldFormatWithZeroCoordinates() {
            String result = MessageUtils.formatLocation("nether", 0.0, 0.0, 0.0);
            assertThat(result).isEqualTo("nether (0.0, 0.0, 0.0)");
        }

        @Test
        @DisplayName("Should format with negative coordinates")
        void shouldFormatWithNegativeCoordinates() {
            String result = MessageUtils.formatLocation("end", -100.0, -50.0, -200.0);
            assertThat(result).isEqualTo("end (-100.0, -50.0, -200.0)");
        }
    }

    @Nested
    @DisplayName("formatDuration")
    class FormatDurationTests {

        @Test
        @DisplayName("Should format negative as permanent")
        void shouldFormatNegativeAsPermanent() {
            assertThat(MessageUtils.formatDuration(-1)).isEqualTo("永久");
        }

        @Test
        @DisplayName("Should format zero as 0 seconds")
        void shouldFormatZeroAsZeroSeconds() {
            assertThat(MessageUtils.formatDuration(0)).isEqualTo("0秒");
        }

        @Test
        @DisplayName("Should format seconds only")
        void shouldFormatSeconds() {
            String result = MessageUtils.formatDuration(30000);
            assertThat(result).contains("30秒");
        }

        @Test
        @DisplayName("Should format minutes and seconds")
        void shouldFormatMinutesAndSeconds() {
            String result = MessageUtils.formatDuration(90000);
            assertThat(result).contains("1分钟");
            assertThat(result).contains("30秒");
        }

        @Test
        @DisplayName("Should format complex duration with all units")
        void shouldFormatComplexDuration() {
            // 1 day, 2 hours, 30 minutes, 15 seconds
            long ms = (1 * 86400 + 2 * 3600 + 30 * 60 + 15) * 1000L;
            String result = MessageUtils.formatDuration(ms);
            assertThat(result).contains("1天");
            assertThat(result).contains("2小时");
            assertThat(result).contains("30分钟");
            assertThat(result).contains("15秒");
        }

        @Test
        @DisplayName("Should not be empty for any positive value")
        void shouldNotBeEmptyForPositiveValue() {
            assertThat(MessageUtils.formatDuration(1000)).isNotEmpty();
            assertThat(MessageUtils.formatDuration(60000)).isNotEmpty();
            assertThat(MessageUtils.formatDuration(3600000)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("sendTitle")
    class SendTitleTests {

        @Test
        @DisplayName("Should send title to player")
        void shouldSendTitle() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            MessageUtils.sendTitle(player, "&6Title", "&7Subtitle", 10, 70, 20);

            verify(player).sendTitle(anyString(), anyString(), eq(10), eq(70), eq(20));
        }

        @Test
        @DisplayName("Should not throw for null player")
        void shouldNotThrowForNullPlayer() {
            // Should not throw
            MessageUtils.sendTitle(null, "Title", "Subtitle", 10, 70, 20);
        }

        @Test
        @DisplayName("Should handle null title")
        void shouldHandleNullTitle() throws Exception {
            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            MessageUtils.sendTitle(player, null, "Subtitle", 10, 70, 20);

            verify(player).sendTitle(anyString(), anyString(), eq(10), eq(70), eq(20));
        }
    }
}
