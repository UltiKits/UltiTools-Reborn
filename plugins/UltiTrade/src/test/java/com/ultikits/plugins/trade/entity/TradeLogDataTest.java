package com.ultikits.plugins.trade.entity;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TradeLogData Tests")
class TradeLogDataTest {

    private UUID tradeId;
    private UUID player1Uuid;
    private UUID player2Uuid;
    private TradeLogData logData;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.plugins.trade.UltiTradeTestHelper.setUp();
        tradeId = UUID.randomUUID();
        player1Uuid = UUID.randomUUID();
        player2Uuid = UUID.randomUUID();
        logData = new TradeLogData(tradeId, player1Uuid, "Player1", player2Uuid, "Player2");
    }

    @AfterEach
    void tearDown() throws Exception {
        com.ultikits.plugins.trade.UltiTradeTestHelper.tearDown();
    }

    @Nested
    @DisplayName("No-Args Constructor")
    class NoArgsConstructor {

        @Test
        @DisplayName("Should create empty instance")
        void emptyInstance() {
            TradeLogData empty = new TradeLogData();
            assertThat(empty).isNotNull();
            assertThat(empty.getTradeId()).isNull();
            assertThat(empty.getPlayer1Uuid()).isNull();
            assertThat(empty.getPlayer2Uuid()).isNull();
            assertThat(empty.getStatus()).isNull();
            assertThat(empty.getPlayer1Money()).isZero();
            assertThat(empty.getPlayer2Money()).isZero();
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should set trade ID")
        void setTradeId() {
            assertThat(logData.getTradeId()).isEqualTo(tradeId.toString());
        }

        @Test
        @DisplayName("Should set player1 UUID and name")
        void setPlayer1Info() {
            assertThat(logData.getPlayer1Uuid()).isEqualTo(player1Uuid.toString());
            assertThat(logData.getPlayer1Name()).isEqualTo("Player1");
        }

        @Test
        @DisplayName("Should set player2 UUID and name")
        void setPlayer2Info() {
            assertThat(logData.getPlayer2Uuid()).isEqualTo(player2Uuid.toString());
            assertThat(logData.getPlayer2Name()).isEqualTo("Player2");
        }

        @Test
        @DisplayName("Should set trade time to current time")
        void setTradeTime() {
            long now = System.currentTimeMillis();
            assertThat(logData.getTradeTime()).isCloseTo(now, within(100L));
        }

        @Test
        @DisplayName("Should initialize status as PENDING")
        void initialStatus() {
            assertThat(logData.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("Should initialize money values as zero")
        void initialMoney() {
            assertThat(logData.getPlayer1Money()).isZero();
            assertThat(logData.getPlayer2Money()).isZero();
            assertThat(logData.getMoneyTaxCollected()).isZero();
        }

        @Test
        @DisplayName("Should initialize exp values as zero")
        void initialExp() {
            assertThat(logData.getPlayer1Exp()).isZero();
            assertThat(logData.getPlayer2Exp()).isZero();
            assertThat(logData.getExpTaxCollected()).isZero();
        }
    }

    @Nested
    @DisplayName("Item Serialization")
    class ItemSerialization {

        @Test
        @DisplayName("Should serialize player1 items to JSON")
        void serializePlayer1Items() {
            Collection<ItemStack> items = Arrays.asList(
                    new ItemStack(Material.DIAMOND, 10),
                    new ItemStack(Material.GOLD_INGOT, 5)
            );

            logData.setPlayer1Items(items);

            assertThat(logData.getPlayer1ItemsJson()).isNotNull().isNotEmpty();
            assertThat(logData.getPlayer1ItemsJson()).contains("DIAMOND");
            assertThat(logData.getPlayer1ItemsJson()).contains("GOLD_INGOT");
        }

        @Test
        @DisplayName("Should serialize player2 items to JSON")
        void serializePlayer2Items() {
            Collection<ItemStack> items = Collections.singletonList(
                    new ItemStack(Material.EMERALD, 3)
            );

            logData.setPlayer2Items(items);

            assertThat(logData.getPlayer2ItemsJson()).isNotNull().isNotEmpty();
            assertThat(logData.getPlayer2ItemsJson()).contains("EMERALD");
        }

        @Test
        @DisplayName("Should handle empty item collection")
        void emptyItems() {
            logData.setPlayer1Items(Collections.emptyList());

            assertThat(logData.getPlayer1ItemsJson()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Status Management")
    class StatusManagement {

        @Test
        @DisplayName("markCompleted should set status to COMPLETED")
        void markCompleted() {
            logData.markCompleted();

            assertThat(logData.getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("markCancelled should set status and reason")
        void markCancelled() {
            logData.markCancelled("Player disconnected");

            assertThat(logData.getStatus()).isEqualTo("CANCELLED");
            assertThat(logData.getCancelReason()).isEqualTo("Player disconnected");
        }

        @Test
        @DisplayName("markCancelled should handle null reason")
        void markCancelledNullReason() {
            logData.markCancelled(null);

            assertThat(logData.getStatus()).isEqualTo("CANCELLED");
            assertThat(logData.getCancelReason()).isNull();
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersAndGetters {

        @Test
        @DisplayName("Should set and get player money")
        void playerMoney() {
            logData.setPlayer1Money(100.5);
            logData.setPlayer2Money(200.75);

            assertThat(logData.getPlayer1Money()).isEqualTo(100.5);
            assertThat(logData.getPlayer2Money()).isEqualTo(200.75);
        }

        @Test
        @DisplayName("Should set and get player exp")
        void playerExp() {
            logData.setPlayer1Exp(50);
            logData.setPlayer2Exp(75);

            assertThat(logData.getPlayer1Exp()).isEqualTo(50);
            assertThat(logData.getPlayer2Exp()).isEqualTo(75);
        }

        @Test
        @DisplayName("Should set and get tax collected")
        void taxCollected() {
            logData.setMoneyTaxCollected(10.5);
            logData.setExpTaxCollected(5);

            assertThat(logData.getMoneyTaxCollected()).isEqualTo(10.5);
            assertThat(logData.getExpTaxCollected()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpired {

        @Test
        @DisplayName("Should return false for recent trade")
        void notExpired() {
            assertThat(logData.isExpired(30)).isFalse();
        }

        @Test
        @DisplayName("Should return true for old trade")
        void expired() throws Exception {
            long oldTime = System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000); // 31 days ago

            java.lang.reflect.Field field = TradeLogData.class.getDeclaredField("tradeTime");
            field.setAccessible(true);
            field.set(logData, oldTime);

            assertThat(logData.isExpired(30)).isTrue();
        }

        @Test
        @DisplayName("Should return false at boundary")
        void boundaryCase() throws Exception {
            long boundaryTime = System.currentTimeMillis() - (29L * 24 * 60 * 60 * 1000); // 29 days ago

            java.lang.reflect.Field field = TradeLogData.class.getDeclaredField("tradeTime");
            field.setAccessible(true);
            field.set(logData, boundaryTime);

            assertThat(logData.isExpired(30)).isFalse();
        }
    }

    @Nested
    @DisplayName("getSummary")
    class GetSummary {

        @Test
        @DisplayName("Should generate summary with all info")
        void completeSummary() {
            logData.setPlayer1Money(100.0);
            logData.setPlayer2Money(200.0);
            logData.setPlayer1Exp(50);
            logData.setPlayer2Exp(75);
            logData.markCompleted();

            String summary = logData.getSummary();

            assertThat(summary).contains("Player1");
            assertThat(summary).contains("Player2");
            assertThat(summary).contains("100.00");
            assertThat(summary).contains("200.00");
            assertThat(summary).contains("50");
            assertThat(summary).contains("75");
            assertThat(summary).contains("COMPLETED");
        }

        @Test
        @DisplayName("Should show cancelled status")
        void cancelledSummary() {
            logData.markCancelled("Timeout");

            String summary = logData.getSummary();

            assertThat(summary).contains("CANCELLED");
        }
    }
}
