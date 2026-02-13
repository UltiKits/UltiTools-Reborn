package com.ultikits.plugins.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnnouncementConfig Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AnnouncementConfigTest {

    private AnnouncementConfig config;

    @BeforeEach
    void setUp() {
        config = new AnnouncementConfig();
    }

    @Nested
    @DisplayName("Chat Announcement Defaults")
    class ChatAnnouncementDefaults {

        @Test
        @DisplayName("Should have chat enabled by default")
        void shouldHaveChatEnabled() {
            assertThat(config.isChatEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default chat interval of 300 seconds")
        void shouldHaveDefaultChatInterval() {
            assertThat(config.getChatInterval()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should have default chat prefix")
        void shouldHaveDefaultChatPrefix() {
            assertThat(config.getChatPrefix()).isEqualTo("&6[Announcement] &f");
        }

        @Test
        @DisplayName("Should have default chat messages")
        void shouldHaveDefaultChatMessages() {
            assertThat(config.getChatMessages()).isNotNull();
            assertThat(config.getChatMessages()).hasSize(2);
            assertThat(config.getChatMessages().get(0)).contains("Welcome");
        }
    }

    @Nested
    @DisplayName("BossBar Announcement Defaults")
    class BossBarAnnouncementDefaults {

        @Test
        @DisplayName("Should have boss bar disabled by default")
        void shouldHaveBossBarDisabled() {
            assertThat(config.isBossBarEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have default boss bar interval of 60 seconds")
        void shouldHaveDefaultBossBarInterval() {
            assertThat(config.getBossBarInterval()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should have default boss bar duration of 10 seconds")
        void shouldHaveDefaultBossBarDuration() {
            assertThat(config.getBossBarDuration()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have default boss bar color BLUE")
        void shouldHaveDefaultBossBarColor() {
            assertThat(config.getBossBarColor()).isEqualTo("BLUE");
        }

        @Test
        @DisplayName("Should have default boss bar messages")
        void shouldHaveDefaultBossBarMessages() {
            assertThat(config.getBossBarMessages()).isNotNull();
            assertThat(config.getBossBarMessages()).hasSize(1);
            assertThat(config.getBossBarMessages().get(0)).contains("Welcome");
        }
    }

    @Nested
    @DisplayName("Title Announcement Defaults")
    class TitleAnnouncementDefaults {

        @Test
        @DisplayName("Should have title disabled by default")
        void shouldHaveTitleDisabled() {
            assertThat(config.isTitleEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have default title interval of 600 seconds")
        void shouldHaveDefaultTitleInterval() {
            assertThat(config.getTitleInterval()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should have default title fade-in of 10 ticks")
        void shouldHaveDefaultTitleFadeIn() {
            assertThat(config.getTitleFadeIn()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have default title stay of 70 ticks")
        void shouldHaveDefaultTitleStay() {
            assertThat(config.getTitleStay()).isEqualTo(70);
        }

        @Test
        @DisplayName("Should have default title fade-out of 20 ticks")
        void shouldHaveDefaultTitleFadeOut() {
            assertThat(config.getTitleFadeOut()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should have default title messages with separator")
        void shouldHaveDefaultTitleMessages() {
            assertThat(config.getTitleMessages()).isNotNull();
            assertThat(config.getTitleMessages()).hasSize(1);
            assertThat(config.getTitleMessages().get(0)).contains("||");
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update chat enabled")
        void shouldUpdateChatEnabled() {
            config.setChatEnabled(false);
            assertThat(config.isChatEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update chat interval")
        void shouldUpdateChatInterval() {
            config.setChatInterval(600);
            assertThat(config.getChatInterval()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should update chat prefix")
        void shouldUpdateChatPrefix() {
            config.setChatPrefix("&c[Notice] &f");
            assertThat(config.getChatPrefix()).isEqualTo("&c[Notice] &f");
        }

        @Test
        @DisplayName("Should update chat messages")
        void shouldUpdateChatMessages() {
            List<String> newMessages = Arrays.asList("Message 1", "Message 2", "Message 3");
            config.setChatMessages(newMessages);
            assertThat(config.getChatMessages()).hasSize(3);
            assertThat(config.getChatMessages()).containsExactly("Message 1", "Message 2", "Message 3");
        }

        @Test
        @DisplayName("Should update boss bar enabled")
        void shouldUpdateBossBarEnabled() {
            config.setBossBarEnabled(true);
            assertThat(config.isBossBarEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update boss bar interval")
        void shouldUpdateBossBarInterval() {
            config.setBossBarInterval(120);
            assertThat(config.getBossBarInterval()).isEqualTo(120);
        }

        @Test
        @DisplayName("Should update boss bar duration")
        void shouldUpdateBossBarDuration() {
            config.setBossBarDuration(15);
            assertThat(config.getBossBarDuration()).isEqualTo(15);
        }

        @Test
        @DisplayName("Should update boss bar color")
        void shouldUpdateBossBarColor() {
            config.setBossBarColor("RED");
            assertThat(config.getBossBarColor()).isEqualTo("RED");
        }

        @Test
        @DisplayName("Should update boss bar messages")
        void shouldUpdateBossBarMessages() {
            config.setBossBarMessages(Collections.singletonList("New bar msg"));
            assertThat(config.getBossBarMessages()).hasSize(1);
            assertThat(config.getBossBarMessages().get(0)).isEqualTo("New bar msg");
        }

        @Test
        @DisplayName("Should update title enabled")
        void shouldUpdateTitleEnabled() {
            config.setTitleEnabled(true);
            assertThat(config.isTitleEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update title interval")
        void shouldUpdateTitleInterval() {
            config.setTitleInterval(1200);
            assertThat(config.getTitleInterval()).isEqualTo(1200);
        }

        @Test
        @DisplayName("Should update title fade-in")
        void shouldUpdateTitleFadeIn() {
            config.setTitleFadeIn(20);
            assertThat(config.getTitleFadeIn()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should update title stay")
        void shouldUpdateTitleStay() {
            config.setTitleStay(100);
            assertThat(config.getTitleStay()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should update title fade-out")
        void shouldUpdateTitleFadeOut() {
            config.setTitleFadeOut(30);
            assertThat(config.getTitleFadeOut()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should update title messages")
        void shouldUpdateTitleMessages() {
            List<String> newMessages = Arrays.asList("Title1||Sub1", "Title2||Sub2");
            config.setTitleMessages(newMessages);
            assertThat(config.getTitleMessages()).hasSize(2);
            assertThat(config.getTitleMessages().get(0)).isEqualTo("Title1||Sub1");
        }
    }
}
