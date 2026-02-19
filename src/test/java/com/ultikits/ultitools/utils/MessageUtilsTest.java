package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.TextComponent;

import static com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState;
import static com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock;

/**
 * MessageUtils 测试类
 */
@DisplayName("MessageUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MessageUtilsTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        ensureCleanState();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        safeUnmock();
    }

    @Nested
    @DisplayName("msg 方法测试")
    class MsgMethodTests {

        @Test
        @DisplayName("应该返回带颜色前缀的消息")
        void shouldReturnColoredMessage() {
            String result = MessageUtils.msg(ChatColor.RED, "Hello World");
            assertThat(result).isEqualTo(ChatColor.RED + "Hello World");
        }

        @ParameterizedTest
        @EnumSource(value = ChatColor.class, names = {"RED", "GREEN", "BLUE", "GOLD", "YELLOW", "WHITE", "BLACK"})
        @DisplayName("应该正确处理各种颜色")
        void shouldHandleVariousColors(ChatColor color) {
            String result = MessageUtils.msg(color, "Test");
            assertThat(result).startsWith(color.toString());
            assertThat(result).endsWith("Test");
        }

        @Test
        @DisplayName("应该正确处理空消息")
        void shouldHandleEmptyMessage() {
            String result = MessageUtils.msg(ChatColor.RED, "");
            assertThat(result).isEqualTo(ChatColor.RED + "");
        }

        @Test
        @DisplayName("应该正确处理格式化颜色")
        void shouldHandleFormatColors() {
            assertThat(MessageUtils.msg(ChatColor.BOLD, "Bold")).startsWith(ChatColor.BOLD.toString());
            assertThat(MessageUtils.msg(ChatColor.ITALIC, "Italic")).startsWith(ChatColor.ITALIC.toString());
            assertThat(MessageUtils.msg(ChatColor.UNDERLINE, "Underline")).startsWith(ChatColor.UNDERLINE.toString());
        }

        @Test
        @DisplayName("应该处理RESET颜色")
        void shouldHandleResetColor() {
            String result = MessageUtils.msg(ChatColor.RESET, "Reset");
            assertThat(result).startsWith(ChatColor.RESET.toString());
        }
    }

    @Nested
    @DisplayName("sendMessage 方法测试")
    class SendMessageMethodTests {

        @Test
        @DisplayName("应该向玩家发送消息")
        void shouldSendMessageToPlayer() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "Hello");
            assertThat(player.nextMessage()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("应该处理颜色代码")
        void shouldHandleColorCodes() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "&cRed &aGreen");
            String message = player.nextMessage();
            assertThat(message).contains(ChatColor.RED.toString());
            assertThat(message).contains(ChatColor.GREEN.toString());
        }

        @Test
        @DisplayName("应该使用自定义颜色代码")
        void shouldUseCustomColorCode() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "#cRed #aGreen", '#');
            String message = player.nextMessage();
            assertThat(message).contains(ChatColor.RED.toString());
            assertThat(message).contains(ChatColor.GREEN.toString());
        }

        @ParameterizedTest
        @ValueSource(chars = {'&', '#', '$', '@', '%'})
        @DisplayName("应该支持各种自定义颜色代码符号")
        void shouldSupportVariousColorCodeSymbols(char symbol) {
            PlayerMock player = server.addPlayer();
            String msg = symbol + "cRed";
            MessageUtils.sendMessage(player, msg, symbol);
            String message = player.nextMessage();
            assertThat(message).contains(ChatColor.RED.toString());
        }

        @ParameterizedTest
        @CsvSource({
            "&0, BLACK",
            "&1, DARK_BLUE",
            "&2, DARK_GREEN",
            "&3, DARK_AQUA",
            "&4, DARK_RED",
            "&5, DARK_PURPLE",
            "&6, GOLD",
            "&7, GRAY",
            "&8, DARK_GRAY",
            "&9, BLUE",
            "&a, GREEN",
            "&b, AQUA",
            "&c, RED",
            "&d, LIGHT_PURPLE",
            "&e, YELLOW",
            "&f, WHITE"
        })
        @DisplayName("应该正确转换所有标准颜色代码")
        void shouldConvertAllStandardColorCodes(String code, String colorName) {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, code + "Test");
            String message = player.nextMessage();
            ChatColor expectedColor = ChatColor.valueOf(colorName);
            assertThat(message).startsWith(expectedColor.toString());
        }

        @Test
        @DisplayName("应该处理空消息")
        void shouldHandleEmptyMessage() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "");
            assertThat(player.nextMessage()).isEmpty();
        }

        @Test
        @DisplayName("应该处理仅包含颜色代码的消息")
        void shouldHandleOnlyColorCodeMessage() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "&c");
            String message = player.nextMessage();
            // 仅包含颜色代码时，translateAlternateColorCodes 可能会返回空或颜色代码
            assertThat(message).isNotNull();
        }
    }

    @Nested
    @DisplayName("coloredMsg 方法测试")
    class ColoredMsgMethodTests {

        @Test
        @DisplayName("应该转换颜色代码")
        void shouldConvertColorCodes() {
            String result = MessageUtils.coloredMsg("&cRed");
            assertThat(result).isEqualTo(ChatColor.RED + "Red");
        }

        @Test
        @DisplayName("应该处理多个颜色代码")
        void shouldHandleMultipleColorCodes() {
            String result = MessageUtils.coloredMsg("&cRed &aGreen &bBlue");
            assertThat(result).contains(ChatColor.RED.toString());
            assertThat(result).contains(ChatColor.GREEN.toString());
            assertThat(result).contains(ChatColor.AQUA.toString());
        }

        @Test
        @DisplayName("应该处理没有颜色代码的消息")
        void shouldHandleNoColorCodes() {
            String result = MessageUtils.coloredMsg("Plain text");
            assertThat(result).isEqualTo("Plain text");
        }

        @Test
        @DisplayName("应该处理格式代码")
        void shouldHandleFormatCodes() {
            String result = MessageUtils.coloredMsg("&lBold &oItalic");
            assertThat(result).contains(ChatColor.BOLD.toString());
            assertThat(result).contains(ChatColor.ITALIC.toString());
        }

        @ParameterizedTest
        @CsvSource({
            "&l, BOLD",
            "&m, STRIKETHROUGH",
            "&n, UNDERLINE",
            "&o, ITALIC",
            "&r, RESET"
        })
        @DisplayName("应该正确转换所有格式代码")
        void shouldConvertAllFormatCodes(String code, String formatName) {
            String result = MessageUtils.coloredMsg(code + "Text");
            ChatColor expectedFormat = ChatColor.valueOf(formatName);
            assertThat(result).startsWith(expectedFormat.toString());
        }

        @Test
        @DisplayName("应该处理混合颜色和格式代码")
        void shouldHandleMixedColorAndFormatCodes() {
            String result = MessageUtils.coloredMsg("&c&lBold Red");
            assertThat(result).contains(ChatColor.RED.toString());
            assertThat(result).contains(ChatColor.BOLD.toString());
        }

        @Test
        @DisplayName("应该保留转义的&符号")
        void shouldPreserveEscapedAmpersand() {
            // && 不会被转换（只有有效的颜色代码才会转换）
            String result = MessageUtils.coloredMsg("&zInvalid");
            assertThat(result).contains("&z"); // &z 不是有效颜色代码
        }

        @Test
        @DisplayName("应该处理连续的颜色代码")
        void shouldHandleConsecutiveColorCodes() {
            String result = MessageUtils.coloredMsg("&c&l&nText");
            assertThat(result).contains(ChatColor.RED.toString());
            assertThat(result).contains(ChatColor.BOLD.toString());
            assertThat(result).contains(ChatColor.UNDERLINE.toString());
        }
    }

    @Nested
    @DisplayName("info 方法测试")
    class InfoMethodTests {

        @Test
        @DisplayName("应该返回青色消息")
        void shouldReturnAquaMessage() {
            String result = MessageUtils.info("Info message");
            assertThat(result).isEqualTo(ChatColor.AQUA + "Info message");
        }

        @Test
        @DisplayName("应该处理空消息")
        void shouldHandleEmptyMessage() {
            String result = MessageUtils.info("");
            assertThat(result).isEqualTo(ChatColor.AQUA + "");
        }

        @Test
        @DisplayName("应该以AQUA颜色开始")
        void shouldStartWithAquaColor() {
            String result = MessageUtils.info("Test");
            assertThat(result).startsWith(ChatColor.AQUA.toString());
        }

        @Test
        @DisplayName("应该保留原始消息内容")
        void shouldPreserveOriginalMessageContent() {
            String original = "Important information";
            String result = MessageUtils.info(original);
            assertThat(result).endsWith(original);
        }
    }

    @Nested
    @DisplayName("warning 方法测试")
    class WarningMethodTests {

        @Test
        @DisplayName("应该返回红色消息")
        void shouldReturnRedMessage() {
            String result = MessageUtils.warning("Warning message");
            assertThat(result).isEqualTo(ChatColor.RED + "Warning message");
        }

        @Test
        @DisplayName("应该以RED颜色开始")
        void shouldStartWithRedColor() {
            String result = MessageUtils.warning("Test");
            assertThat(result).startsWith(ChatColor.RED.toString());
        }

        @Test
        @DisplayName("应该处理空消息")
        void shouldHandleEmptyMessage() {
            String result = MessageUtils.warning("");
            assertThat(result).isEqualTo(ChatColor.RED + "");
        }
    }

    @Nested
    @DisplayName("error 方法测试")
    class ErrorMethodTests {

        @Test
        @DisplayName("应该返回深红色消息")
        void shouldReturnDarkRedMessage() {
            String result = MessageUtils.error("Error message");
            assertThat(result).isEqualTo(ChatColor.DARK_RED + "Error message");
        }

        @Test
        @DisplayName("应该以DARK_RED颜色开始")
        void shouldStartWithDarkRedColor() {
            String result = MessageUtils.error("Test");
            assertThat(result).startsWith(ChatColor.DARK_RED.toString());
        }

        @Test
        @DisplayName("应该处理空消息")
        void shouldHandleEmptyMessage() {
            String result = MessageUtils.error("");
            assertThat(result).isEqualTo(ChatColor.DARK_RED + "");
        }
    }

    @Nested
    @DisplayName("多消息发送测试")
    class MultipleMessagesTests {

        @Test
        @DisplayName("应该按顺序发送多条消息")
        void shouldSendMultipleMessagesInOrder() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "First");
            MessageUtils.sendMessage(player, "Second");
            MessageUtils.sendMessage(player, "Third");
            
            assertThat(player.nextMessage()).isEqualTo("First");
            assertThat(player.nextMessage()).isEqualTo("Second");
            assertThat(player.nextMessage()).isEqualTo("Third");
        }

        @Test
        @DisplayName("应该向多个玩家发送消息")
        void shouldSendToMultiplePlayers() {
            PlayerMock player1 = server.addPlayer("Player1");
            PlayerMock player2 = server.addPlayer("Player2");
            
            MessageUtils.sendMessage(player1, "Hello P1");
            MessageUtils.sendMessage(player2, "Hello P2");
            
            assertThat(player1.nextMessage()).isEqualTo("Hello P1");
            assertThat(player2.nextMessage()).isEqualTo("Hello P2");
        }
    }

    @Nested
    @DisplayName("特殊字符处理测试")
    class SpecialCharactersTests {

        @Test
        @DisplayName("应该处理特殊字符")
        void shouldHandleSpecialCharacters() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "Special: !@#$%^&*()");
            assertThat(player.nextMessage()).isEqualTo("Special: !@#$%^&*()");
        }

        @Test
        @DisplayName("应该处理Unicode字符")
        void shouldHandleUnicodeCharacters() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "中文消息 日本語");
            assertThat(player.nextMessage()).isEqualTo("中文消息 日本語");
        }

        @Test
        @DisplayName("应该处理换行符")
        void shouldHandleNewlines() {
            PlayerMock player = server.addPlayer();
            MessageUtils.sendMessage(player, "Line1\nLine2");
            assertThat(player.nextMessage()).contains("Line1");
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("msg方法应该存在且签名正确")
        void msgMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("msg", ChatColor.class, String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("sendMessage(Player, String)方法应该存在")
        void sendMessageStringMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("sendMessage", Player.class, String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("sendMessage(Player, String, char)方法应该存在")
        void sendMessageWithCharMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("sendMessage", Player.class, String.class, char.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("sendMessage(Player, TextComponent)方法应该存在")
        void sendMessageTextComponentMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("sendMessage", Player.class, TextComponent.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("coloredMsg方法应该存在且签名正确")
        void coloredMsgMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("coloredMsg", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("info方法应该存在且签名正确")
        void infoMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("info", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("warning方法应该存在且签名正确")
        void warningMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("warning", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("error方法应该存在且签名正确")
        void errorMethodShouldExist() throws Exception {
            Method method = MessageUtils.class.getMethod("error", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("MessageUtils类应该是公开的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(MessageUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有正确数量的公开方法")
        void shouldHaveCorrectNumberOfPublicMethods() {
            Method[] methods = MessageUtils.class.getDeclaredMethods();
            long publicStaticMethods = java.util.Arrays.stream(methods)
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .count();
            // msg, sendMessage x3, coloredMsg, info, warning, error
            assertThat(publicStaticMethods).isGreaterThanOrEqualTo(7);
        }
    }

    @Nested
    @DisplayName("消息级别对比测试")
    class MessageLevelComparisonTests {

        @Test
        @DisplayName("info、warning、error应该返回不同颜色")
        void differentLevelsShouldReturnDifferentColors() {
            String infoResult = MessageUtils.info("Test");
            String warningResult = MessageUtils.warning("Test");
            String errorResult = MessageUtils.error("Test");
            
            assertThat(infoResult).startsWith(ChatColor.AQUA.toString());
            assertThat(warningResult).startsWith(ChatColor.RED.toString());
            assertThat(errorResult).startsWith(ChatColor.DARK_RED.toString());
            
            // 三个颜色应该都不同
            assertThat(ChatColor.AQUA).isNotEqualTo(ChatColor.RED);
            assertThat(ChatColor.RED).isNotEqualTo(ChatColor.DARK_RED);
            assertThat(ChatColor.AQUA).isNotEqualTo(ChatColor.DARK_RED);
        }

        @Test
        @DisplayName("相同消息不同级别应该产生不同结果")
        void sameMessageDifferentLevelsShouldProduceDifferentResults() {
            String message = "Same message";
            String info = MessageUtils.info(message);
            String warning = MessageUtils.warning(message);
            String error = MessageUtils.error(message);
            
            assertThat(info).isNotEqualTo(warning);
            assertThat(warning).isNotEqualTo(error);
            assertThat(info).isNotEqualTo(error);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryConditionTests {

        @Test
        @DisplayName("应该处理非常长的消息")
        void shouldHandleVeryLongMessage() {
            String longMessage = "A".repeat(10000);
            PlayerMock player = server.addPlayer();
            
            assertThatCode(() -> MessageUtils.sendMessage(player, longMessage))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("应该处理包含多种Unicode的消息")
        void shouldHandleMultipleUnicodeTypes() {
            PlayerMock player = server.addPlayer();
            String unicodeMessage = "English 中文 日本語 한국어 العربية 🎮🎯🎲";
            
            MessageUtils.sendMessage(player, unicodeMessage);
            assertThat(player.nextMessage()).isEqualTo(unicodeMessage);
        }

        @Test
        @DisplayName("应该处理末尾的颜色代码")
        void shouldHandleTrailingColorCode() {
            String result = MessageUtils.coloredMsg("Text&c");
            assertThat(result).endsWith(ChatColor.RED.toString());
        }

        @Test
        @DisplayName("应该处理开头的颜色代码")
        void shouldHandleLeadingColorCode() {
            String result = MessageUtils.coloredMsg("&cText");
            assertThat(result).startsWith(ChatColor.RED.toString());
        }
    }
}
