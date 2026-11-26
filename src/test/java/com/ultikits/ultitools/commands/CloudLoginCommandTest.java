package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertColour;
import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.utils.CloudAuthManager;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every login callback uses real catalogues; no secret or argument is parsed as markup. */
@Timeout(30)
class CloudLoginCommandTest {
    private static final String REPAIR_CODE = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    private static final String[] CODE_BLOCK = {
        "========================================",
        " To link this server to UltiCloud, open:",
        " https://panel.ultikits.com/device",
        " and enter the code:",
        " BCDF-GHJK",
        " Or open this link directly: https://panel.ultikits.com/device?code=BCDF-GHJK",
        "========================================",
        "The code expires in 10 minutes. Waiting for confirmation..."
    };
    private static final NamedTextColor[] CODE_COLOURS = {
        NamedTextColor.GREEN, NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.GREEN,
        NamedTextColor.AQUA, NamedTextColor.GRAY, NamedTextColor.GREEN, NamedTextColor.GRAY
    };

    private static List<CommandSender> senders(String language) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.forLanguageTag(language));
        return List.of(mock(ConsoleCommandSender.class), mock(RemoteConsoleCommandSender.class),
                mock(CommandSender.class), player);
    }

    private static boolean remote(CommandSender sender) {
        return !(sender instanceof ConsoleCommandSender) && !(sender instanceof Player);
    }

    private static void login(CommandSender sender, boolean withCode,
            Consumer<CloudAuthManager.DeviceLoginListener> outcome) {
        try (var auth = mockStatic(CloudAuthManager.class)) {
            if (withCode) {
                auth.when(() -> CloudAuthManager.isWellFormedRepairCode(REPAIR_CODE)).thenCallRealMethod();
                auth.when(() -> CloudAuthManager.loginWithCode(anyString(), any())).thenAnswer(call -> {
                    outcome.accept(call.getArgument(1));
                    return null;
                });
                new CloudLoginCommand().login(sender, REPAIR_CODE);
                auth.verify(() -> CloudAuthManager.loginWithCode(eq(REPAIR_CODE), any()));
                auth.verify(() -> CloudAuthManager.login(any()), never());
            } else {
                auth.when(() -> CloudAuthManager.login(any())).thenAnswer(call -> {
                    outcome.accept(call.getArgument(0));
                    return null;
                });
                new CloudLoginCommand().login(sender);
                auth.verify(() -> CloudAuthManager.login(any()));
            }
        }
    }

    private static void assertLogged(CoreCommandMessageTestSupport fixture, CommandSender sender, String... lines) {
        if (remote(sender)) {
            var logged = inOrder(fixture.logger);
            for (String line : lines) {
                logged.verify(fixture.logger).log(Level.INFO, line);
                assertThat(line).doesNotContain("§", REPAIR_CODE);
            }
            logged.verifyNoMoreInteractions();
        } else {
            verifyNoInteractions(fixture.logger);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void alreadyLinkedIsOneYellowLineForBothLoginForms(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (CommandSender sender : senders(language)) {
                for (boolean withCode : new boolean[]{false, true}) {
                    clearInvocations(sender, fixture.logger);
                    login(sender, withCode, listener -> listener.onAlreadyLinked("<red>Survival"));
                    String expected = "This server is already linked to UltiCloud as <red>Survival. Use /ulticloud logout first to link it again.";
                    assertColour(assertLines(sender, expected).getFirst(), NamedTextColor.YELLOW);
                    assertLogged(fixture, sender, expected);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void rateLimitedUsesTheExistingTranslatedSentence(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (CommandSender sender : senders(language)) {
                for (boolean withCode : new boolean[]{false, true}) {
                    clearInvocations(sender, fixture.logger);
                    login(sender, withCode, listener -> listener.onRateLimited(42L));
                    String expected = "zh".equals(language) ? "请等待 42 秒后再试。" : "Please wait 42 seconds before trying again.";
                    assertColour(assertLines(sender, expected).getFirst(), NamedTextColor.RED);
                    assertLogged(fixture, sender, expected);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void requestingSendsRemoteNoticeButLogsTheLocalSentence(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (CommandSender sender : senders(language)) {
                for (boolean withCode : new boolean[]{false, true}) {
                    clearInvocations(sender, fixture.logger);
                    login(sender, withCode, CloudAuthManager.DeviceLoginListener::onRequesting);
                    String local = withCode ? "Redeeming the re-pair code with UltiCloud..."
                            : "Requesting a device code from UltiCloud...";
                    String notice = withCode
                            ? "Redeeming the re-pair code with UltiCloud. The result will be printed in the server console."
                            : "Requesting a device code from UltiCloud. The code and the link will be printed in the server console.";
                    assertColour(assertLines(sender, remote(sender) ? notice : local).getFirst(), NamedTextColor.AQUA);
                    assertLogged(fixture, sender, local);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void codeBlockHasExactlyEightLinesWithColoursAndPlainRemoteLog(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (CommandSender sender : senders(language)) {
                clearInvocations(fixture.logger);
                login(sender, false, listener -> listener.onCode("https://panel.ultikits.com/device", "BCDF-GHJK",
                        "https://panel.ultikits.com/device?code=BCDF-GHJK", 600));
                var lines = assertLines(sender, CODE_BLOCK);
                for (int i = 0; i < lines.size(); i++) {
                    assertColour(lines.get(i), CODE_COLOURS[i]);
                }
                assertLogged(fixture, sender, CODE_BLOCK);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void codeArgumentsAreLiteralAndExpirationRoundsUpToAtLeastOneMinute(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            CommandSender sender = mock(ConsoleCommandSender.class);
            long[] seconds = {0, 1, 61};
            String[] minutes = {"1", "1", "2"};
            for (int i = 0; i < seconds.length; i++) {
                clearInvocations(sender);
                long expires = seconds[i];
                login(sender, false, listener -> listener.onCode("https://example.invalid/<red>", "<red>CODE",
                        "https://example.invalid/?code=<red>", expires));
                assertLines(sender, "========================================", " To link this server to UltiCloud, open:",
                        " https://example.invalid/<red>", " and enter the code:", " <red>CODE",
                        " Or open this link directly: https://example.invalid/?code=<red>",
                        "========================================", "The code expires in " + minutes[i]
                                + " minutes. Waiting for confirmation...");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void requestFailureKeepsTheReasonLiteralForBothLoginForms(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (CommandSender sender : senders(language)) {
                for (boolean withCode : new boolean[]{false, true}) {
                    clearInvocations(sender, fixture.logger);
                    login(sender, withCode, listener -> listener.onRequestFailed("<red>UltiCloud could not be reached"));
                    String expected = (withCode ? "Could not use the re-pair code: " : "Failed to get a device code: ")
                            + "<red>UltiCloud could not be reached";
                    assertColour(assertLines(sender, expected).getFirst(), NamedTextColor.RED);
                    assertLogged(fixture, sender, expected);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void malformedRepairCodeNeverLeavesTheServerOrAppearsInTheReply(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language); var auth = mockStatic(CloudAuthManager.class)) {
            auth.when(() -> CloudAuthManager.isWellFormedRepairCode(anyString())).thenCallRealMethod();
            for (String invalid : new String[]{"short", "has space in it and is long enough to pass the length check ok",
                    "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCd", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbC+E"}) {
                CommandSender sender = mock(ConsoleCommandSender.class);
                new CloudLoginCommand().login(sender, invalid);
                assertLines(sender, "zh".equals(language)
                        ? "这不是有效的 UltiCloud 重新配对代码。请从面板复制完整的代码后重试。"
                        : "That is not a valid UltiCloud re-pair code. Copy the whole code from the panel and try again.");
            }
            auth.verify(() -> CloudAuthManager.loginWithCode(anyString(), any()), never());
        }
    }
}
