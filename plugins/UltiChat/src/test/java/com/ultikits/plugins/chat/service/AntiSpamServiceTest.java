package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.ChatConfig;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AntiSpamService")
class AntiSpamServiceTest {

    private AntiSpamService service;
    private ChatConfig config;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();
        config = new ChatConfig();
        config.setAntiSpamEnabled(true);
        config.setAntiSpamCooldown(2);
        config.setAntiSpamMaxDuplicate(3);
        config.setAntiSpamDuplicateWindow(30);
        config.setAntiSpamMuteDuration(60);
        config.setAntiSpamCapsLimit(70);

        service = new AntiSpamService();
        ChatTestHelper.setField(service, "config", config);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    private Player createPlayer() {
        return ChatTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
    }

    private Player createPlayerWithId(UUID uuid) {
        return ChatTestHelper.createMockPlayer("TestPlayer", uuid);
    }

    // -------------------------------------------------------------------------
    // checkSpam
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkSpam - disabled / null safety")
    class CheckSpamDisabledTests {

        @Test
        @DisplayName("should return null when anti-spam is disabled")
        void shouldReturnNullWhenDisabled() {
            config.setAntiSpamEnabled(false);
            Player player = createPlayer();
            assertThat(service.checkSpam(player, "hello")).isNull();
        }

        @Test
        @DisplayName("should return null when player is null")
        void shouldReturnNullWhenPlayerNull() {
            assertThat(service.checkSpam(null, "hello")).isNull();
        }

        @Test
        @DisplayName("should return null when message is null")
        void shouldReturnNullWhenMessageNull() {
            Player player = createPlayer();
            assertThat(service.checkSpam(player, null)).isNull();
        }

        @Test
        @DisplayName("should return null for normal message")
        void shouldReturnNullForNormalMessage() {
            Player player = createPlayer();
            assertThat(service.checkSpam(player, "hello world")).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Mute checks
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkSpam - mute")
    class CheckSpamMuteTests {

        @Test
        @DisplayName("should return mute reason when player is muted")
        void shouldReturnMuteReasonWhenMuted() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            // Directly put a future mute time
            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");
            mutedUntil.put(playerId, System.currentTimeMillis() + 60000);

            String reason = service.checkSpam(player, "hello");
            assertThat(reason).isEqualTo("你已被临时禁言！");
        }

        @Test
        @DisplayName("should allow message when mute has expired")
        void shouldAllowMessageWhenMuteExpired() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");
            mutedUntil.put(playerId, System.currentTimeMillis() - 1000);

            String reason = service.checkSpam(player, "hello");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should clean up expired mute entry")
        void shouldCleanUpExpiredMuteEntry() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");
            mutedUntil.put(playerId, System.currentTimeMillis() - 1000);

            service.checkSpam(player, "hello");
            assertThat(mutedUntil).doesNotContainKey(playerId);
        }
    }

    // -------------------------------------------------------------------------
    // Cooldown checks
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkSpam - cooldown")
    class CheckSpamCooldownTests {

        @Test
        @DisplayName("should return cooldown reason when sending too fast")
        void shouldReturnCooldownReasonWhenTooFast() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            // Simulate a recent message
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            lastMessageTime.put(playerId, System.currentTimeMillis());

            String reason = service.checkSpam(player, "too fast");
            assertThat(reason).isEqualTo("发送消息太快了！");
        }

        @Test
        @DisplayName("should allow message after cooldown expires")
        void shouldAllowAfterCooldownExpires() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            // Set last message to 3 seconds ago (cooldown is 2s)
            lastMessageTime.put(playerId, System.currentTimeMillis() - 3000);

            String reason = service.checkSpam(player, "allowed now");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should allow first message from player")
        void shouldAllowFirstMessage() {
            Player player = createPlayer();
            String reason = service.checkSpam(player, "first message");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should use zero cooldown correctly")
        void shouldUseZeroCooldownCorrectly() throws Exception {
            config.setAntiSpamCooldown(0);
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            lastMessageTime.put(playerId, System.currentTimeMillis());

            // 0 second cooldown means message is never too fast
            String reason = service.checkSpam(player, "immediate");
            assertThat(reason).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Duplicate detection
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkSpam - duplicate detection")
    class CheckSpamDuplicateTests {

        @Test
        @DisplayName("should detect duplicate messages")
        void shouldDetectDuplicateMessages() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            // Simulate 3 identical messages in history (maxDuplicate is 3)
            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = new LinkedList<String>();
            messages.add("spam");
            messages.add("spam");
            messages.add("spam");
            recentMessages.put(playerId, messages);

            String reason = service.checkSpam(player, "spam");
            assertThat(reason).isEqualTo("请不要发送重复消息！");
        }

        @Test
        @DisplayName("should allow message when below duplicate threshold")
        void shouldAllowBelowDuplicateThreshold() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = new LinkedList<String>();
            messages.add("spam");
            messages.add("spam");
            // Only 2 duplicates, threshold is 3
            recentMessages.put(playerId, messages);

            String reason = service.checkSpam(player, "spam");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should allow different messages")
        void shouldAllowDifferentMessages() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = new LinkedList<String>();
            messages.add("message1");
            messages.add("message2");
            messages.add("message3");
            recentMessages.put(playerId, messages);

            String reason = service.checkSpam(player, "message4");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should handle empty recent messages")
        void shouldHandleEmptyRecentMessages() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            recentMessages.put(playerId, new LinkedList<String>());

            String reason = service.checkSpam(player, "hello");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should handle no recent messages entry")
        void shouldHandleNoRecentMessagesEntry() {
            Player player = createPlayer();
            String reason = service.checkSpam(player, "hello");
            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("should handle maxDuplicate of zero")
        void shouldHandleMaxDuplicateOfZero() throws Exception {
            config.setAntiSpamMaxDuplicate(0);
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = new LinkedList<String>();
            messages.add("spam");
            messages.add("spam");
            messages.add("spam");
            recentMessages.put(playerId, messages);

            String reason = service.checkSpam(player, "spam");
            // maxDuplicate <= 0 means duplicate check is disabled
            assertThat(reason).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Caps detection
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("checkSpam - caps detection")
    class CheckSpamCapsTests {

        @Test
        @DisplayName("should detect excessive caps")
        void shouldDetectExcessiveCaps() {
            // 70% limit, 100% caps in a 10-char message
            Player player = createPlayer();
            String reason = service.checkSpam(player, "HELLOWORLD");
            assertThat(reason).isEqualTo("消息中大写字母过多！");
        }

        @Test
        @DisplayName("should allow message with acceptable caps ratio")
        void shouldAllowAcceptableCapsRatio() {
            Player player = createPlayer();
            // 2/10 = 20% caps, below 70%
            String reason = service.checkSpam(player, "HEllo worl");
            assertThat(reason).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // isExcessiveCaps
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isExcessiveCaps")
    class IsExcessiveCapsTests {

        @Test
        @DisplayName("should return false for null message")
        void shouldReturnFalseForNull() {
            assertThat(service.isExcessiveCaps(null)).isFalse();
        }

        @Test
        @DisplayName("should return false for short messages (< 5 chars)")
        void shouldReturnFalseForShortMessages() {
            assertThat(service.isExcessiveCaps("ABCD")).isFalse();
        }

        @Test
        @DisplayName("should return false for exactly 4 char message")
        void shouldReturnFalseForFourChars() {
            assertThat(service.isExcessiveCaps("ABCD")).isFalse();
        }

        @Test
        @DisplayName("should detect caps in 5+ char message")
        void shouldDetectCapsInFiveCharMessage() {
            // 5/5 = 100% > 70%
            assertThat(service.isExcessiveCaps("ABCDE")).isTrue();
        }

        @Test
        @DisplayName("should return false when caps at exactly limit")
        void shouldReturnFalseWhenCapsAtExactLimit() {
            config.setAntiSpamCapsLimit(70);
            // 7/10 = 70% which is NOT > 70% (strictly greater)
            assertThat(service.isExcessiveCaps("ABCDEFGhij")).isFalse();
        }

        @Test
        @DisplayName("should return true when caps above limit")
        void shouldReturnTrueWhenCapsAboveLimit() {
            config.setAntiSpamCapsLimit(70);
            // 8/10 = 80% > 70%
            assertThat(service.isExcessiveCaps("ABCDEFGHij")).isTrue();
        }

        @Test
        @DisplayName("should ignore non-letter characters in ratio calculation")
        void shouldIgnoreNonLetterCharacters() {
            config.setAntiSpamCapsLimit(70);
            // Letters: A,B,C,D,E,F,G,h,i,j = 10, uppercase: 7, ratio = 70%, not > 70%
            assertThat(service.isExcessiveCaps("A1B2C3D4E5F6G7h8i9j0")).isFalse();
        }

        @Test
        @DisplayName("should return false for message with only non-letter characters")
        void shouldReturnFalseForOnlyNonLetters() {
            assertThat(service.isExcessiveCaps("12345 67890")).isFalse();
        }

        @Test
        @DisplayName("should return false when caps limit is zero")
        void shouldReturnFalseWhenCapsLimitZero() {
            config.setAntiSpamCapsLimit(0);
            assertThat(service.isExcessiveCaps("ABCDE")).isFalse();
        }

        @Test
        @DisplayName("should return false when caps limit is 100 or more")
        void shouldReturnFalseWhenCapsLimit100() {
            config.setAntiSpamCapsLimit(100);
            assertThat(service.isExcessiveCaps("ABCDE")).isFalse();
        }

        @Test
        @DisplayName("should return false for empty string")
        void shouldReturnFalseForEmptyString() {
            assertThat(service.isExcessiveCaps("")).isFalse();
        }

        @Test
        @DisplayName("should handle all lowercase")
        void shouldHandleAllLowercase() {
            assertThat(service.isExcessiveCaps("abcdefgh")).isFalse();
        }

        @Test
        @DisplayName("should handle mixed case below threshold")
        void shouldHandleMixedCaseBelowThreshold() {
            config.setAntiSpamCapsLimit(50);
            // 2/10 = 20% < 50%
            assertThat(service.isExcessiveCaps("ABcdefghij")).isFalse();
        }

        @Test
        @DisplayName("should handle mixed case above threshold")
        void shouldHandleMixedCaseAboveThreshold() {
            config.setAntiSpamCapsLimit(50);
            // 8/10 = 80% > 50%
            assertThat(service.isExcessiveCaps("ABCDEFGHij")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // recordMessage
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("recordMessage")
    class RecordMessageTests {

        @Test
        @DisplayName("should update lastMessageTime")
        void shouldUpdateLastMessageTime() throws Exception {
            UUID playerId = UUID.randomUUID();
            long before = System.currentTimeMillis();
            service.recordMessage(playerId, "hello");
            long after = System.currentTimeMillis();

            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            assertThat(lastMessageTime.get(playerId)).isBetween(before, after);
        }

        @Test
        @DisplayName("should add message to recent messages")
        void shouldAddMessageToRecentMessages() throws Exception {
            UUID playerId = UUID.randomUUID();
            service.recordMessage(playerId, "hello");

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            assertThat(recentMessages.get(playerId)).containsExactly("hello");
        }

        @Test
        @DisplayName("should maintain bounded list of recent messages")
        void shouldMaintainBoundedList() throws Exception {
            UUID playerId = UUID.randomUUID();
            config.setAntiSpamMaxDuplicate(3);

            service.recordMessage(playerId, "msg1");
            service.recordMessage(playerId, "msg2");
            service.recordMessage(playerId, "msg3");
            service.recordMessage(playerId, "msg4");

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = recentMessages.get(playerId);
            assertThat(messages).hasSize(3);
            assertThat(messages).containsExactly("msg2", "msg3", "msg4");
        }

        @Test
        @DisplayName("should handle null playerId gracefully")
        void shouldHandleNullPlayerId() {
            // Should not throw
            service.recordMessage(null, "hello");
        }

        @Test
        @DisplayName("should handle null message gracefully")
        void shouldHandleNullMessage() {
            // Should not throw
            service.recordMessage(UUID.randomUUID(), null);
        }

        @Test
        @DisplayName("should use default max duplicate when config is zero")
        void shouldUseDefaultMaxDuplicateWhenZero() throws Exception {
            config.setAntiSpamMaxDuplicate(0);
            UUID playerId = UUID.randomUUID();

            for (int i = 0; i < 5; i++) {
                service.recordMessage(playerId, "msg" + i);
            }

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = recentMessages.get(playerId);
            // Default is 3 when config is 0
            assertThat(messages).hasSize(3);
        }
    }

    // -------------------------------------------------------------------------
    // mutePlayer
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("mutePlayer")
    class MutePlayerTests {

        @Test
        @DisplayName("should mute player for configured duration")
        void shouldMuteForConfiguredDuration() throws Exception {
            UUID playerId = UUID.randomUUID();
            config.setAntiSpamMuteDuration(30);
            long before = System.currentTimeMillis();

            service.mutePlayer(playerId);

            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");
            Long muteEnd = mutedUntil.get(playerId);
            assertThat(muteEnd).isNotNull();
            // Should be approximately now + 30 seconds
            assertThat(muteEnd).isGreaterThanOrEqualTo(before + 30000);
            assertThat(muteEnd).isLessThanOrEqualTo(before + 31000);
        }

        @Test
        @DisplayName("should handle null playerId gracefully")
        void shouldHandleNullPlayerId() {
            // Should not throw
            service.mutePlayer(null);
        }

        @Test
        @DisplayName("should override existing mute")
        void shouldOverrideExistingMute() throws Exception {
            UUID playerId = UUID.randomUUID();
            config.setAntiSpamMuteDuration(10);

            service.mutePlayer(playerId);
            long firstMute = ((Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil")).get(playerId);

            // Wait a tiny bit then re-mute
            Thread.sleep(5);
            config.setAntiSpamMuteDuration(60);
            service.mutePlayer(playerId);

            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");
            Long secondMute = mutedUntil.get(playerId);
            assertThat(secondMute).isGreaterThan(firstMute);
        }

        @Test
        @DisplayName("muted player should be blocked by checkSpam")
        void mutedPlayerShouldBeBlockedByCheckSpam() {
            Player player = createPlayer();
            service.mutePlayer(player.getUniqueId());

            String reason = service.checkSpam(player, "hello");
            assertThat(reason).isEqualTo("你已被临时禁言！");
        }
    }

    // -------------------------------------------------------------------------
    // cleanup
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("cleanup")
    class CleanupTests {

        @Test
        @DisplayName("should remove all state for player")
        void shouldRemoveAllState() throws Exception {
            UUID playerId = UUID.randomUUID();

            // Add state
            service.recordMessage(playerId, "hello");
            service.mutePlayer(playerId);

            // Verify state exists
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");

            assertThat(lastMessageTime).containsKey(playerId);
            assertThat(recentMessages).containsKey(playerId);
            assertThat(mutedUntil).containsKey(playerId);

            // Cleanup
            service.cleanup(playerId);

            assertThat(lastMessageTime).doesNotContainKey(playerId);
            assertThat(recentMessages).doesNotContainKey(playerId);
            assertThat(mutedUntil).doesNotContainKey(playerId);
        }

        @Test
        @DisplayName("should handle null playerId gracefully")
        void shouldHandleNullPlayerId() {
            // Should not throw
            service.cleanup(null);
        }

        @Test
        @DisplayName("should handle cleanup of non-existent player")
        void shouldHandleCleanupOfNonExistentPlayer() {
            // Should not throw
            service.cleanup(UUID.randomUUID());
        }

        @Test
        @DisplayName("should not affect other players")
        void shouldNotAffectOtherPlayers() throws Exception {
            UUID player1 = UUID.randomUUID();
            UUID player2 = UUID.randomUUID();

            service.recordMessage(player1, "msg1");
            service.recordMessage(player2, "msg2");
            service.mutePlayer(player1);
            service.mutePlayer(player2);

            service.cleanup(player1);

            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            @SuppressWarnings("unchecked")
            Map<UUID, Long> mutedUntil = (Map<UUID, Long>) ChatTestHelper.getField(service, "mutedUntil");

            assertThat(lastMessageTime).doesNotContainKey(player1);
            assertThat(lastMessageTime).containsKey(player2);
            assertThat(recentMessages).doesNotContainKey(player1);
            assertThat(recentMessages).containsKey(player2);
            assertThat(mutedUntil).doesNotContainKey(player1);
            assertThat(mutedUntil).containsKey(player2);
        }
    }

    // -------------------------------------------------------------------------
    // Integration scenarios
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Integration scenarios")
    class IntegrationTests {

        @Test
        @DisplayName("full flow: record, then trigger cooldown")
        void fullFlowCooldown() {
            Player player = createPlayer();

            // First message should pass
            assertThat(service.checkSpam(player, "hello")).isNull();
            service.recordMessage(player.getUniqueId(), "hello");

            // Immediate second message should trigger cooldown
            assertThat(service.checkSpam(player, "world")).isEqualTo("发送消息太快了！");
        }

        @Test
        @DisplayName("full flow: record duplicates then trigger spam")
        void fullFlowDuplicate() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();
            config.setAntiSpamCooldown(0); // Disable cooldown for this test

            // Record 3 "spam" messages
            service.recordMessage(playerId, "spam");
            service.recordMessage(playerId, "spam");
            service.recordMessage(playerId, "spam");

            // 4th "spam" should be detected as duplicate
            String reason = service.checkSpam(player, "spam");
            assertThat(reason).isEqualTo("请不要发送重复消息！");
        }

        @Test
        @DisplayName("mute then cleanup allows sending again")
        void muteCleanupAllowsSending() {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            service.mutePlayer(playerId);
            assertThat(service.checkSpam(player, "hello")).isEqualTo("你已被临时禁言！");

            service.cleanup(playerId);
            assertThat(service.checkSpam(player, "hello")).isNull();
        }

        @Test
        @DisplayName("check order: mute before cooldown before duplicate before caps")
        void checkOrderMuteFirst() throws Exception {
            Player player = createPlayer();
            UUID playerId = player.getUniqueId();

            // Set up ALL conditions: muted + cooldown + duplicate + caps
            service.mutePlayer(playerId);
            @SuppressWarnings("unchecked")
            Map<UUID, Long> lastMessageTime = (Map<UUID, Long>) ChatTestHelper.getField(service, "lastMessageTime");
            lastMessageTime.put(playerId, System.currentTimeMillis());

            @SuppressWarnings("unchecked")
            Map<UUID, LinkedList<String>> recentMessages =
                    (Map<UUID, LinkedList<String>>) ChatTestHelper.getField(service, "recentMessages");
            LinkedList<String> messages = new LinkedList<String>();
            messages.add("SPAM");
            messages.add("SPAM");
            messages.add("SPAM");
            recentMessages.put(playerId, messages);

            // Mute should be checked first
            assertThat(service.checkSpam(player, "SPAM")).isEqualTo("你已被临时禁言！");
        }

        @Test
        @DisplayName("multiple players are tracked independently")
        void multiplePlayersTrackedIndependently() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();
            Player player1 = createPlayerWithId(uuid1);
            Player player2 = createPlayerWithId(uuid2);

            // Player 1 sends a message
            assertThat(service.checkSpam(player1, "hello")).isNull();
            service.recordMessage(uuid1, "hello");

            // Player 2 should not be affected by player 1's cooldown
            assertThat(service.checkSpam(player2, "hello")).isNull();
        }
    }
}
