package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.BanData;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for BanService.
 * Does NOT use MockBukkit - uses pure Mockito mocking.
 * <p>
 * 使用纯 Mockito 测试封禁服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("BanService Tests (Mockito)")
class BanServiceMockitoTest {

    private BanService banService;
    private EssentialsConfig config;

    @SuppressWarnings("unchecked")
    private DataOperator<BanData> banOperator = mock(DataOperator.class);

    @SuppressWarnings("unchecked")
    private Query<BanData> query = mock(Query.class);

    private UUID playerUuid;
    private UUID operatorUuid;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        config = new EssentialsConfig();
        // ban is enabled by default

        banService = new BanService();
        EssentialsTestHelper.setField(banService, "config", config);
        EssentialsTestHelper.setField(banService, "banOperator", banOperator);

        playerUuid = UUID.randomUUID();
        operatorUuid = UUID.randomUUID();

        reset(banOperator, query);

        // Setup default query chaining behavior
        when(banOperator.query()).thenReturn(query);
        when(query.where(anyString())).thenReturn(query);
        when(query.eq(any())).thenReturn(query);
        when(query.and(anyString())).thenReturn(query);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    // ==================== Ban Player Tests ====================

    @Nested
    @DisplayName("banPlayer")
    class BanPlayerTests {

        @Test
        @DisplayName("Should ban player permanently")
        void shouldBanPlayerPermanently() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "Testing ban",
                operatorUuid, "Admin"
            );

            assertThat(result).isEqualTo(BanService.BanResult.SUCCESS);
            verify(banOperator).insert(any(BanData.class));
        }

        @Test
        @DisplayName("Should ban player temporarily")
        void shouldBanPlayerTemporarily() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "Temp ban",
                operatorUuid, "Admin",
                TimeUnit.DAYS.toMillis(7), null
            );

            assertThat(result).isEqualTo(BanService.BanResult.SUCCESS);
            verify(banOperator).insert(any(BanData.class));
        }

        @Test
        @DisplayName("Should reject duplicate ban")
        void shouldRejectDuplicateBan() throws Exception {
            BanData existingBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Already banned")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            when(query.list())
                .thenReturn(Collections.singletonList(existingBan));

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "Test",
                operatorUuid, "Admin"
            );

            assertThat(result).isEqualTo(BanService.BanResult.ALREADY_BANNED);
            verify(banOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should ban with IP address")
        void shouldBanWithIpAddress() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "IP ban",
                operatorUuid, "Admin",
                -1, "192.168.1.1"
            );

            assertThat(result).isEqualTo(BanService.BanResult.SUCCESS);
            verify(banOperator).insert(any(BanData.class));
        }

        @Test
        @DisplayName("Should return DISABLED when ban feature is off")
        void shouldReturnDisabledWhenFeatureOff() throws Exception {
            config.setBanEnabled(false);

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "Test",
                operatorUuid, "Admin"
            );

            assertThat(result).isEqualTo(BanService.BanResult.DISABLED);
            verify(banOperator, never()).query();
            verify(banOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should set default reason when null")
        void shouldSetDefaultReasonWhenNull() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            banService.banPlayer(
                playerUuid, "TestPlayer", null,
                operatorUuid, "Admin"
            );

            verify(banOperator).insert(argThat(ban ->
                ban.getReason().equals("无理由")
            ));
        }

        @Test
        @DisplayName("Should set correct expire time for temporary ban")
        void shouldSetCorrectExpireTimeForTempBan() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());
            long duration = TimeUnit.HOURS.toMillis(12);

            banService.banPlayer(
                playerUuid, "TestPlayer", "Temp",
                operatorUuid, "Admin",
                duration, null
            );

            verify(banOperator).insert(argThat(ban ->
                ban.getExpireTime() > System.currentTimeMillis() - 1000 &&
                ban.getExpireTime() < System.currentTimeMillis() + duration + 1000
            ));
        }

        @Test
        @DisplayName("Should set permanent expire time as -1")
        void shouldSetPermanentExpireTime() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            banService.banPlayer(
                playerUuid, "TestPlayer", "Permanent",
                operatorUuid, "Admin",
                -1, null
            );

            verify(banOperator).insert(argThat(ban ->
                ban.getExpireTime() == -1
            ));
        }

        @Test
        @DisplayName("Should allow console ban with null operator UUID")
        void shouldAllowConsoleBanWithNullOperatorUuid() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "Console ban",
                null, "Console"
            );

            assertThat(result).isEqualTo(BanService.BanResult.SUCCESS);
            verify(banOperator).insert(argThat(ban ->
                ban.getBannedBy() == null && ban.getBannedByName().equals("Console")
            ));
        }

        @Test
        @DisplayName("Should kick online player after ban")
        void shouldKickOnlinePlayerAfterBan() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            // Mock Bukkit.getPlayer to return an online player
            Player onlinePlayer = EssentialsTestHelper.createMockPlayer("TestPlayer", playerUuid);
            when(EssentialsTestHelper.getMockServer().getPlayer(playerUuid)).thenReturn(onlinePlayer);

            banService.banPlayer(
                playerUuid, "TestPlayer", "Kicked",
                operatorUuid, "Admin"
            );

            verify(onlinePlayer).kickPlayer(anyString());
        }

        @Test
        @DisplayName("Should not throw when player is offline during ban")
        void shouldNotThrowWhenPlayerIsOffline() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());
            when(EssentialsTestHelper.getMockServer().getPlayer(playerUuid)).thenReturn(null);

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "Offline ban",
                operatorUuid, "Admin"
            );

            assertThat(result).isEqualTo(BanService.BanResult.SUCCESS);
        }

        @Test
        @DisplayName("Should not ban already banned player even if expired bans exist")
        void shouldNotCountExpiredBansAsActive() throws Exception {
            // Expired ban should not count
            BanData expiredBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Expired")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis() - 200000)
                .expireTime(System.currentTimeMillis() - 100000)
                .active(true)
                .build();

            when(query.list())
                .thenReturn(Collections.singletonList(expiredBan));

            BanService.BanResult result = banService.banPlayer(
                playerUuid, "TestPlayer", "New ban",
                operatorUuid, "Admin"
            );

            assertThat(result).isEqualTo(BanService.BanResult.SUCCESS);
        }
    }

    // ==================== Unban Player Tests ====================

    @Nested
    @DisplayName("unbanPlayer")
    class UnbanPlayerTests {

        @Test
        @DisplayName("Should unban player successfully")
        void shouldUnbanPlayerSuccessfully() throws Exception {
            BanData activeBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Test")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            when(query.list())
                .thenReturn(new ArrayList<>(Collections.singletonList(activeBan)));

            boolean result = banService.unbanPlayer(playerUuid);

            assertThat(result).isTrue();
            verify(banOperator).update(any(BanData.class));
        }

        @Test
        @DisplayName("Should return false when player not banned")
        void shouldReturnFalseWhenPlayerNotBanned() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            boolean result = banService.unbanPlayer(playerUuid);

            assertThat(result).isFalse();
            verify(banOperator, never()).update(any());
        }

        @Test
        @DisplayName("Should unban multiple active bans")
        void shouldUnbanMultipleActiveBans() throws Exception {
            BanData ban1 = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Ban 1")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            BanData ban2 = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Ban 2")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(System.currentTimeMillis() + 100000)
                .active(true)
                .build();

            when(query.list())
                .thenReturn(new ArrayList<>(Arrays.asList(ban1, ban2)));

            boolean result = banService.unbanPlayer(playerUuid);

            assertThat(result).isTrue();
            verify(banOperator, times(2)).update(any(BanData.class));
        }

        @Test
        @DisplayName("Should not unban inactive bans")
        void shouldNotUnbanInactiveBans() throws Exception {
            BanData inactiveBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Inactive")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(false)
                .build();

            when(query.list())
                .thenReturn(new ArrayList<>(Collections.singletonList(inactiveBan)));

            boolean result = banService.unbanPlayer(playerUuid);

            assertThat(result).isFalse();
        }
    }

    // ==================== Unban By Name Tests ====================

    @Nested
    @DisplayName("unbanPlayerByName")
    class UnbanByNameTests {

        @Test
        @DisplayName("Should unban by player name")
        void shouldUnbanByPlayerName() throws Exception {
            BanData ban = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Test")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            when(query.list())
                .thenReturn(new ArrayList<>(Collections.singletonList(ban)));

            boolean result = banService.unbanPlayerByName("TestPlayer");

            assertThat(result).isTrue();
            verify(banOperator).update(any(BanData.class));
        }

        @Test
        @DisplayName("Should return false when name not found")
        void shouldReturnFalseWhenNameNotFound() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            boolean result = banService.unbanPlayerByName("Unknown");

            assertThat(result).isFalse();
        }
    }

    // ==================== IP Ban Tests ====================

    @Nested
    @DisplayName("IP Ban Operations")
    class IpBanTests {

        @Test
        @DisplayName("Should get active IP ban")
        void shouldGetActiveIpBan() throws Exception {
            BanData ipBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("IP Ban")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .ipAddress("192.168.1.1")
                .build();

            when(query.list())
                .thenReturn(Collections.singletonList(ipBan));

            BanData result = banService.getActiveIpBan("192.168.1.1");

            assertThat(result).isNotNull();
            assertThat(result.getIpAddress()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("Should return null for null IP address")
        void shouldReturnNullForNullIp() throws Exception {
            BanData result = banService.getActiveIpBan(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should return null for empty IP address")
        void shouldReturnNullForEmptyIp() throws Exception {
            BanData result = banService.getActiveIpBan("");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should unban IP address")
        void shouldUnbanIpAddress() throws Exception {
            BanData ipBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("IP Ban")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .ipAddress("10.0.0.1")
                .build();

            when(query.list())
                .thenReturn(new ArrayList<>(Collections.singletonList(ipBan)));

            boolean result = banService.unbanIp("10.0.0.1");

            assertThat(result).isTrue();
            verify(banOperator).update(any(BanData.class));
        }

        @Test
        @DisplayName("Should return false when unbanning non-banned IP")
        void shouldReturnFalseWhenIpNotBanned() throws Exception {
            when(query.list()).thenReturn(new ArrayList<>());

            boolean result = banService.unbanIp("10.0.0.1");

            assertThat(result).isFalse();
        }
    }

    // ==================== Get Active Bans Tests ====================

    @Nested
    @DisplayName("getActiveBans")
    class GetActiveBansTests {

        @Test
        @DisplayName("Should get all active bans")
        void shouldGetAllActiveBans() throws Exception {
            BanData activeBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Active")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            BanData inactiveBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(UUID.randomUUID().toString())
                .playerName("Other")
                .reason("Inactive")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(false)
                .build();

            when(banOperator.getAll())
                .thenReturn(Arrays.asList(activeBan, inactiveBan));

            List<BanData> result = banService.getActiveBans();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPlayerName()).isEqualTo("TestPlayer");
        }

        @Test
        @DisplayName("Should return empty list when no active bans")
        void shouldReturnEmptyWhenNoActiveBans() throws Exception {
            when(banOperator.getAll()).thenReturn(new ArrayList<>());

            List<BanData> result = banService.getActiveBans();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should filter expired bans from active list")
        void shouldFilterExpiredBans() throws Exception {
            BanData expiredBan = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("Expired")
                .reason("Expired")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis() - 200000)
                .expireTime(System.currentTimeMillis() - 100000)
                .active(true)
                .build();

            when(banOperator.getAll()).thenReturn(Collections.singletonList(expiredBan));

            List<BanData> result = banService.getActiveBans();

            assertThat(result).isEmpty();
        }
    }

    // ==================== Get Ban History Tests ====================

    @Nested
    @DisplayName("getBanHistory")
    class GetBanHistoryTests {

        @Test
        @DisplayName("Should get ban history for player")
        void shouldGetBanHistoryForPlayer() throws Exception {
            BanData ban = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("History")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(false)
                .build();

            when(query.list())
                .thenReturn(Collections.singletonList(ban));

            List<BanData> history = banService.getBanHistory(playerUuid);

            assertThat(history).hasSize(1);
        }
    }

    // ==================== Format Kick Message Tests ====================

    @Nested
    @DisplayName("formatKickMessage")
    class FormatKickMessageTests {

        @Test
        @DisplayName("Should format permanent ban message")
        void shouldFormatPermanentBanMessage() throws Exception {
            BanData ban = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Cheating")
                .bannedByName("Admin")
                .banTime(System.currentTimeMillis())
                .expireTime(-1)
                .active(true)
                .build();

            String message = banService.formatKickMessage(ban);

            assertThat(message).contains("你已被封禁");
            assertThat(message).contains("Cheating");
            assertThat(message).contains("Admin");
            assertThat(message).contains("永久封禁");
        }

        @Test
        @DisplayName("Should format temporary ban message with remaining time")
        void shouldFormatTempBanMessage() throws Exception {
            BanData ban = BanData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .playerName("TestPlayer")
                .reason("Griefing")
                .bannedByName("Mod")
                .banTime(System.currentTimeMillis())
                .expireTime(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2))
                .active(true)
                .build();

            String message = banService.formatKickMessage(ban);

            assertThat(message).contains("你已被封禁");
            assertThat(message).contains("Griefing");
            assertThat(message).contains("Mod");
            assertThat(message).contains("剩余时间");
        }
    }

    // ==================== Duration Parsing Tests ====================

    @Nested
    @DisplayName("parseDuration (static)")
    class ParseDurationTests {

        @Test
        @DisplayName("Should parse days")
        void shouldParseDays() {
            long duration = BanService.parseDuration("7d");
            assertThat(duration).isEqualTo(TimeUnit.DAYS.toMillis(7));
        }

        @Test
        @DisplayName("Should parse hours")
        void shouldParseHours() {
            long duration = BanService.parseDuration("24h");
            assertThat(duration).isEqualTo(TimeUnit.HOURS.toMillis(24));
        }

        @Test
        @DisplayName("Should parse minutes")
        void shouldParseMinutes() {
            long duration = BanService.parseDuration("30m");
            assertThat(duration).isEqualTo(TimeUnit.MINUTES.toMillis(30));
        }

        @Test
        @DisplayName("Should parse seconds")
        void shouldParseSeconds() {
            long duration = BanService.parseDuration("60s");
            assertThat(duration).isEqualTo(TimeUnit.SECONDS.toMillis(60));
        }

        @Test
        @DisplayName("Should parse weeks")
        void shouldParseWeeks() {
            long duration = BanService.parseDuration("2w");
            assertThat(duration).isEqualTo(TimeUnit.DAYS.toMillis(14));
        }

        @Test
        @DisplayName("Should parse combined duration")
        void shouldParseCombinedDuration() {
            long duration = BanService.parseDuration("1d12h30m");
            long expected = TimeUnit.DAYS.toMillis(1)
                + TimeUnit.HOURS.toMillis(12)
                + TimeUnit.MINUTES.toMillis(30);
            assertThat(duration).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should return -1 for null input")
        void shouldReturnNegativeOneForNull() {
            assertThat(BanService.parseDuration(null)).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return -1 for empty input")
        void shouldReturnNegativeOneForEmpty() {
            assertThat(BanService.parseDuration("")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return -1 for invalid input")
        void shouldReturnNegativeOneForInvalid() {
            assertThat(BanService.parseDuration("invalid")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return -1 for letter-only input")
        void shouldReturnNegativeOneForLetterOnly() {
            assertThat(BanService.parseDuration("abc")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return -1 for number-only input (no suffix)")
        void shouldReturnNegativeOneForNumberOnly() {
            assertThat(BanService.parseDuration("123")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should return -1 for unknown unit character")
        void shouldReturnNegativeOneForUnknownUnit() {
            assertThat(BanService.parseDuration("5x")).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should handle uppercase input via toLowerCase")
        void shouldHandleUppercaseInput() {
            long duration = BanService.parseDuration("7D");
            assertThat(duration).isEqualTo(TimeUnit.DAYS.toMillis(7));
        }
    }

    // ==================== Duration Formatting Tests ====================

    @Nested
    @DisplayName("formatDuration (static)")
    class FormatDurationTests {

        @Test
        @DisplayName("Should format days")
        void shouldFormatDays() {
            String formatted = BanService.formatDuration(TimeUnit.DAYS.toMillis(7));
            assertThat(formatted).contains("7天");
        }

        @Test
        @DisplayName("Should format hours")
        void shouldFormatHours() {
            String formatted = BanService.formatDuration(TimeUnit.HOURS.toMillis(5));
            assertThat(formatted).contains("5小时");
        }

        @Test
        @DisplayName("Should format minutes")
        void shouldFormatMinutes() {
            String formatted = BanService.formatDuration(TimeUnit.MINUTES.toMillis(30));
            assertThat(formatted).contains("30分钟");
        }

        @Test
        @DisplayName("Should format seconds")
        void shouldFormatSeconds() {
            String formatted = BanService.formatDuration(TimeUnit.SECONDS.toMillis(45));
            assertThat(formatted).contains("45秒");
        }

        @Test
        @DisplayName("Should format combined duration")
        void shouldFormatCombinedDuration() {
            long duration = TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(2) + TimeUnit.MINUTES.toMillis(30);
            String formatted = BanService.formatDuration(duration);
            assertThat(formatted).contains("1天");
            assertThat(formatted).contains("2小时");
            assertThat(formatted).contains("30分钟");
        }

        @Test
        @DisplayName("Should format zero as expired")
        void shouldFormatZeroAsExpired() {
            String formatted = BanService.formatDuration(0);
            assertThat(formatted).isEqualTo("已过期");
        }

        @Test
        @DisplayName("Should format negative as expired")
        void shouldFormatNegativeAsExpired() {
            String formatted = BanService.formatDuration(-1000);
            assertThat(formatted).isEqualTo("已过期");
        }

        @Test
        @DisplayName("Should format 0 seconds for very small positive value")
        void shouldFormatZeroSecondsForSmallValue() {
            // 500ms = 0 seconds, 0 minutes, 0 hours, 0 days -> "0秒"
            String formatted = BanService.formatDuration(500);
            assertThat(formatted).contains("0秒");
        }
    }

    // ==================== BanResult Enum Tests ====================

    @Nested
    @DisplayName("BanResult enum")
    class BanResultEnumTests {

        @Test
        @DisplayName("Should have all expected values")
        void shouldHaveAllValues() {
            BanService.BanResult[] values = BanService.BanResult.values();
            assertThat(values).hasSize(3);
            assertThat(values).contains(
                BanService.BanResult.SUCCESS,
                BanService.BanResult.ALREADY_BANNED,
                BanService.BanResult.DISABLED
            );
        }

        @Test
        @DisplayName("Should support valueOf")
        void shouldSupportValueOf() {
            assertThat(BanService.BanResult.valueOf("SUCCESS"))
                .isEqualTo(BanService.BanResult.SUCCESS);
            assertThat(BanService.BanResult.valueOf("ALREADY_BANNED"))
                .isEqualTo(BanService.BanResult.ALREADY_BANNED);
            assertThat(BanService.BanResult.valueOf("DISABLED"))
                .isEqualTo(BanService.BanResult.DISABLED);
        }
    }
}
