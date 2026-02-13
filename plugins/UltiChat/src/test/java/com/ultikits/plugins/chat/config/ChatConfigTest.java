package com.ultikits.plugins.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatConfig Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChatConfigTest {

    private ChatConfig config;

    @BeforeEach
    void setUp() {
        config = new ChatConfig();
    }

    @Nested
    @DisplayName("Chat Format Defaults")
    class ChatFormatDefaults {

        @Test
        @DisplayName("Should have chat format enabled by default")
        void shouldHaveChatFormatEnabled() {
            assertThat(config.isChatFormatEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default chat format string")
        void shouldHaveDefaultChatFormat() {
            assertThat(config.getChatFormat())
                    .isEqualTo("&7[&f%player_world%&7] &f{player}&7: &f{message}");
        }
    }

    @Nested
    @DisplayName("Join/Quit Defaults")
    class JoinQuitDefaults {

        @Test
        @DisplayName("Should have join message enabled by default")
        void shouldHaveJoinMessageEnabled() {
            assertThat(config.isJoinMessageEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default join message format")
        void shouldHaveDefaultJoinFormat() {
            assertThat(config.getJoinMessageFormat())
                    .isEqualTo("&a[+] &e%player_name% &7joined the server");
        }

        @Test
        @DisplayName("Should have quit message enabled by default")
        void shouldHaveQuitMessageEnabled() {
            assertThat(config.isQuitMessageEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default quit message format")
        void shouldHaveDefaultQuitFormat() {
            assertThat(config.getQuitMessageFormat())
                    .isEqualTo("&c[-] &e%player_name% &7left the server");
        }

        @Test
        @DisplayName("Should have welcome enabled by default")
        void shouldHaveWelcomeEnabled() {
            assertThat(config.isWelcomeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default welcome lines")
        void shouldHaveDefaultWelcomeLines() {
            assertThat(config.getWelcomeLines()).isNotNull();
            assertThat(config.getWelcomeLines()).hasSize(4);
            assertThat(config.getWelcomeLines().get(1)).contains("Welcome");
        }

        @Test
        @DisplayName("Should have title enabled by default")
        void shouldHaveTitleEnabled() {
            assertThat(config.isTitleEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default title main text")
        void shouldHaveDefaultTitleMain() {
            assertThat(config.getTitleMain()).isEqualTo("&6Welcome Back");
        }

        @Test
        @DisplayName("Should have default title subtitle")
        void shouldHaveDefaultTitleSub() {
            assertThat(config.getTitleSub()).isEqualTo("&7%player_name%");
        }

        @Test
        @DisplayName("Should have default first join message")
        void shouldHaveDefaultFirstJoinMessage() {
            assertThat(config.getFirstJoinMessage())
                    .isEqualTo("&6Welcome new player &e%player_name%&6!");
        }
    }

    @Nested
    @DisplayName("Mention Defaults")
    class MentionDefaults {

        @Test
        @DisplayName("Should have mentions enabled by default")
        void shouldHaveMentionsEnabled() {
            assertThat(config.isMentionsEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default mention format")
        void shouldHaveDefaultMentionFormat() {
            assertThat(config.getMentionFormat()).isEqualTo("&e@{player}&r");
        }

        @Test
        @DisplayName("Should have default mention sound")
        void shouldHaveDefaultMentionSound() {
            assertThat(config.getMentionSound()).isEqualTo("ENTITY_EXPERIENCE_ORB_PICKUP");
        }

        @Test
        @DisplayName("Should have self-mention disabled by default")
        void shouldHaveSelfMentionDisabled() {
            assertThat(config.isSelfMention()).isFalse();
        }
    }

    @Nested
    @DisplayName("Anti-Spam Defaults")
    class AntiSpamDefaults {

        @Test
        @DisplayName("Should have anti-spam enabled by default")
        void shouldHaveAntiSpamEnabled() {
            assertThat(config.isAntiSpamEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default cooldown of 2 seconds")
        void shouldHaveDefaultCooldown() {
            assertThat(config.getAntiSpamCooldown()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should have default max duplicate of 3")
        void shouldHaveDefaultMaxDuplicate() {
            assertThat(config.getAntiSpamMaxDuplicate()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should have default duplicate window of 60 seconds")
        void shouldHaveDefaultDuplicateWindow() {
            assertThat(config.getAntiSpamDuplicateWindow()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should have default mute duration of 30 seconds")
        void shouldHaveDefaultMuteDuration() {
            assertThat(config.getAntiSpamMuteDuration()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should have default caps limit of 70 percent")
        void shouldHaveDefaultCapsLimit() {
            assertThat(config.getAntiSpamCapsLimit()).isEqualTo(70);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update chat format enabled")
        void shouldUpdateChatFormatEnabled() {
            config.setChatFormatEnabled(false);
            assertThat(config.isChatFormatEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update chat format")
        void shouldUpdateChatFormat() {
            config.setChatFormat("{player}: {message}");
            assertThat(config.getChatFormat()).isEqualTo("{player}: {message}");
        }

        @Test
        @DisplayName("Should update join message enabled")
        void shouldUpdateJoinMessageEnabled() {
            config.setJoinMessageEnabled(false);
            assertThat(config.isJoinMessageEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update join message format")
        void shouldUpdateJoinMessageFormat() {
            config.setJoinMessageFormat("&b{player} joined!");
            assertThat(config.getJoinMessageFormat()).isEqualTo("&b{player} joined!");
        }

        @Test
        @DisplayName("Should update quit message enabled")
        void shouldUpdateQuitMessageEnabled() {
            config.setQuitMessageEnabled(false);
            assertThat(config.isQuitMessageEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update quit message format")
        void shouldUpdateQuitMessageFormat() {
            config.setQuitMessageFormat("&c{player} left.");
            assertThat(config.getQuitMessageFormat()).isEqualTo("&c{player} left.");
        }

        @Test
        @DisplayName("Should update welcome enabled")
        void shouldUpdateWelcomeEnabled() {
            config.setWelcomeEnabled(false);
            assertThat(config.isWelcomeEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update welcome lines")
        void shouldUpdateWelcomeLines() {
            List<String> newLines = Arrays.asList("Line 1", "Line 2");
            config.setWelcomeLines(newLines);
            assertThat(config.getWelcomeLines()).hasSize(2);
            assertThat(config.getWelcomeLines()).containsExactly("Line 1", "Line 2");
        }

        @Test
        @DisplayName("Should update title enabled")
        void shouldUpdateTitleEnabled() {
            config.setTitleEnabled(false);
            assertThat(config.isTitleEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update title main")
        void shouldUpdateTitleMain() {
            config.setTitleMain("&aHello!");
            assertThat(config.getTitleMain()).isEqualTo("&aHello!");
        }

        @Test
        @DisplayName("Should update title sub")
        void shouldUpdateTitleSub() {
            config.setTitleSub("&7Subtitle");
            assertThat(config.getTitleSub()).isEqualTo("&7Subtitle");
        }

        @Test
        @DisplayName("Should update first join message")
        void shouldUpdateFirstJoinMessage() {
            config.setFirstJoinMessage("New player: {player}");
            assertThat(config.getFirstJoinMessage()).isEqualTo("New player: {player}");
        }

        @Test
        @DisplayName("Should update mentions enabled")
        void shouldUpdateMentionsEnabled() {
            config.setMentionsEnabled(false);
            assertThat(config.isMentionsEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update mention format")
        void shouldUpdateMentionFormat() {
            config.setMentionFormat("&b@{player}");
            assertThat(config.getMentionFormat()).isEqualTo("&b@{player}");
        }

        @Test
        @DisplayName("Should update mention sound")
        void shouldUpdateMentionSound() {
            config.setMentionSound("BLOCK_NOTE_BLOCK_PLING");
            assertThat(config.getMentionSound()).isEqualTo("BLOCK_NOTE_BLOCK_PLING");
        }

        @Test
        @DisplayName("Should update self mention")
        void shouldUpdateSelfMention() {
            config.setSelfMention(true);
            assertThat(config.isSelfMention()).isTrue();
        }

        @Test
        @DisplayName("Should update anti-spam enabled")
        void shouldUpdateAntiSpamEnabled() {
            config.setAntiSpamEnabled(false);
            assertThat(config.isAntiSpamEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update anti-spam cooldown")
        void shouldUpdateAntiSpamCooldown() {
            config.setAntiSpamCooldown(5);
            assertThat(config.getAntiSpamCooldown()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should update anti-spam max duplicate")
        void shouldUpdateAntiSpamMaxDuplicate() {
            config.setAntiSpamMaxDuplicate(5);
            assertThat(config.getAntiSpamMaxDuplicate()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should update anti-spam duplicate window")
        void shouldUpdateAntiSpamDuplicateWindow() {
            config.setAntiSpamDuplicateWindow(120);
            assertThat(config.getAntiSpamDuplicateWindow()).isEqualTo(120);
        }

        @Test
        @DisplayName("Should update anti-spam mute duration")
        void shouldUpdateAntiSpamMuteDuration() {
            config.setAntiSpamMuteDuration(60);
            assertThat(config.getAntiSpamMuteDuration()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should update anti-spam caps limit")
        void shouldUpdateAntiSpamCapsLimit() {
            config.setAntiSpamCapsLimit(50);
            assertThat(config.getAntiSpamCapsLimit()).isEqualTo(50);
        }
    }
}
