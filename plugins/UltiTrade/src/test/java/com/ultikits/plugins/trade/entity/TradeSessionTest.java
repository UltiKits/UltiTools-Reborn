package com.ultikits.plugins.trade.entity;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TradeSession Tests")
class TradeSessionTest {

    private Player player1;
    private Player player2;
    private UUID uuid1;
    private UUID uuid2;
    private TradeSession session;

    @BeforeEach
    void setUp() {
        uuid1 = UUID.randomUUID();
        uuid2 = UUID.randomUUID();
        player1 = mock(Player.class);
        player2 = mock(Player.class);
        when(player1.getUniqueId()).thenReturn(uuid1);
        when(player2.getUniqueId()).thenReturn(uuid2);

        session = new TradeSession(player1, player2);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should generate session ID")
        void generateSessionId() {
            assertThat(session.getSessionId()).isNotNull();
        }

        @Test
        @DisplayName("Should set player1 UUID")
        void setPlayer1Uuid() {
            assertThat(session.getPlayer1()).isEqualTo(uuid1);
        }

        @Test
        @DisplayName("Should set player2 UUID")
        void setPlayer2Uuid() {
            assertThat(session.getPlayer2()).isEqualTo(uuid2);
        }

        @Test
        @DisplayName("Should set start time")
        void setStartTime() {
            long now = System.currentTimeMillis();
            assertThat(session.getStartTime()).isCloseTo(now, within(100L));
        }

        @Test
        @DisplayName("Should initialize state as TRADING")
        void initialState() {
            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.TRADING);
        }

        @Test
        @DisplayName("Should initialize confirmation as false")
        void initialConfirmation() {
            assertThat(session.isConfirmed(uuid1)).isFalse();
            assertThat(session.isConfirmed(uuid2)).isFalse();
            assertThat(session.isBothConfirmed()).isFalse();
        }

        @Test
        @DisplayName("Should initialize money as zero")
        void initialMoney() {
            assertThat(session.getPlayerMoney(uuid1)).isZero();
            assertThat(session.getPlayerMoney(uuid2)).isZero();
        }

        @Test
        @DisplayName("Should initialize exp as zero")
        void initialExp() {
            assertThat(session.getPlayerExp(uuid1)).isZero();
            assertThat(session.getPlayerExp(uuid2)).isZero();
        }

        @Test
        @DisplayName("Should initialize items as empty")
        void initialItems() {
            assertThat(session.getPlayerItems(uuid1)).isEmpty();
            assertThat(session.getPlayerItems(uuid2)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Player Identification")
    class PlayerIdentification {

        @Test
        @DisplayName("getOtherPlayer should return correct UUID")
        void getOtherPlayer() {
            assertThat(session.getOtherPlayer(uuid1)).isEqualTo(uuid2);
            assertThat(session.getOtherPlayer(uuid2)).isEqualTo(uuid1);
        }

        @Test
        @DisplayName("isParticipant should return true for both players")
        void isParticipant() {
            assertThat(session.isParticipant(uuid1)).isTrue();
            assertThat(session.isParticipant(uuid2)).isTrue();
        }

        @Test
        @DisplayName("isParticipant should return false for non-participant")
        void isNotParticipant() {
            UUID other = UUID.randomUUID();
            assertThat(session.isParticipant(other)).isFalse();
        }
    }

    @Nested
    @DisplayName("Item Management")
    class ItemManagement {

        @Test
        @DisplayName("setItem should add item for player1")
        void setItemPlayer1() {
            ItemStack item = new ItemStack(Material.DIAMOND, 10);
            session.setItem(uuid1, 0, item);

            Map<Integer, ItemStack> items = session.getPlayerItems(uuid1);
            assertThat(items).containsKey(0);
            assertThat(items.get(0)).isEqualTo(item);
        }

        @Test
        @DisplayName("setItem should add item for player2")
        void setItemPlayer2() {
            ItemStack item = new ItemStack(Material.GOLD_INGOT, 5);
            session.setItem(uuid2, 1, item);

            Map<Integer, ItemStack> items = session.getPlayerItems(uuid2);
            assertThat(items).containsKey(1);
            assertThat(items.get(0)).isNull();
        }

        @Test
        @DisplayName("setItem with null should remove item")
        void removeItem() {
            ItemStack item = new ItemStack(Material.DIAMOND, 10);
            session.setItem(uuid1, 0, item);
            session.setItem(uuid1, 0, null);

            assertThat(session.getPlayerItems(uuid1)).doesNotContainKey(0);
        }

        @Test
        @DisplayName("setItem should reset confirmation")
        void resetConfirmationOnSetItem() {
            session.setConfirmed(uuid1, true);
            session.setConfirmed(uuid2, true);

            session.setItem(uuid1, 0, new ItemStack(Material.DIAMOND));

            assertThat(session.isConfirmed(uuid1)).isFalse();
            assertThat(session.isConfirmed(uuid2)).isFalse();
        }

        @Test
        @DisplayName("getOtherPlayerItems should return correct items")
        void getOtherPlayerItems() {
            ItemStack item = new ItemStack(Material.DIAMOND, 10);
            session.setItem(uuid1, 0, item);

            Map<Integer, ItemStack> otherItems = session.getOtherPlayerItems(uuid2);
            assertThat(otherItems).containsKey(0);
            assertThat(otherItems.get(0)).isEqualTo(item);
        }

        @Test
        @DisplayName("Should handle multiple items per player")
        void multipleItems() {
            session.setItem(uuid1, 0, new ItemStack(Material.DIAMOND, 10));
            session.setItem(uuid1, 1, new ItemStack(Material.GOLD_INGOT, 5));
            session.setItem(uuid2, 0, new ItemStack(Material.EMERALD, 3));

            assertThat(session.getPlayerItems(uuid1)).hasSize(2);
            assertThat(session.getPlayerItems(uuid2)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Money Management")
    class MoneyManagement {

        @Test
        @DisplayName("setMoney should set money for player1")
        void setMoneyPlayer1() {
            session.setMoney(uuid1, 100.0);
            assertThat(session.getPlayerMoney(uuid1)).isEqualTo(100.0);
        }

        @Test
        @DisplayName("setMoney should set money for player2")
        void setMoneyPlayer2() {
            session.setMoney(uuid2, 250.5);
            assertThat(session.getPlayerMoney(uuid2)).isEqualTo(250.5);
        }

        @Test
        @DisplayName("setMoney should reset confirmation")
        void resetConfirmationOnSetMoney() {
            session.setConfirmed(uuid1, true);
            session.setConfirmed(uuid2, true);

            session.setMoney(uuid1, 100.0);

            assertThat(session.isConfirmed(uuid1)).isFalse();
            assertThat(session.isConfirmed(uuid2)).isFalse();
        }

        @Test
        @DisplayName("getOtherPlayerMoney should return correct money")
        void getOtherPlayerMoney() {
            session.setMoney(uuid1, 100.0);
            assertThat(session.getOtherPlayerMoney(uuid2)).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Should handle decimal values")
        void decimalValues() {
            session.setMoney(uuid1, 123.45);
            assertThat(session.getPlayerMoney(uuid1)).isEqualTo(123.45);
        }
    }

    @Nested
    @DisplayName("Experience Management")
    class ExperienceManagement {

        @Test
        @DisplayName("setExp should set exp for player1")
        void setExpPlayer1() {
            session.setExp(uuid1, 100);
            assertThat(session.getPlayerExp(uuid1)).isEqualTo(100);
        }

        @Test
        @DisplayName("setExp should set exp for player2")
        void setExpPlayer2() {
            session.setExp(uuid2, 250);
            assertThat(session.getPlayerExp(uuid2)).isEqualTo(250);
        }

        @Test
        @DisplayName("setExp should reset confirmation")
        void resetConfirmationOnSetExp() {
            session.setConfirmed(uuid1, true);
            session.setConfirmed(uuid2, true);

            session.setExp(uuid1, 100);

            assertThat(session.isConfirmed(uuid1)).isFalse();
            assertThat(session.isConfirmed(uuid2)).isFalse();
        }

        @Test
        @DisplayName("getOtherPlayerExp should return correct exp")
        void getOtherPlayerExp() {
            session.setExp(uuid1, 100);
            assertThat(session.getOtherPlayerExp(uuid2)).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Confirmation")
    class Confirmation {

        @Test
        @DisplayName("setConfirmed should set confirmation for player1")
        void setConfirmedPlayer1() {
            session.setConfirmed(uuid1, true);
            assertThat(session.isConfirmed(uuid1)).isTrue();
        }

        @Test
        @DisplayName("setConfirmed should set confirmation for player2")
        void setConfirmedPlayer2() {
            session.setConfirmed(uuid2, true);
            assertThat(session.isConfirmed(uuid2)).isTrue();
        }

        @Test
        @DisplayName("isBothConfirmed should return true when both confirmed")
        void bothConfirmedTrue() {
            session.setConfirmed(uuid1, true);
            session.setConfirmed(uuid2, true);
            assertThat(session.isBothConfirmed()).isTrue();
        }

        @Test
        @DisplayName("isBothConfirmed should return false when only one confirmed")
        void bothConfirmedFalseOne() {
            session.setConfirmed(uuid1, true);
            assertThat(session.isBothConfirmed()).isFalse();
        }

        @Test
        @DisplayName("isBothConfirmed should return false when none confirmed")
        void bothConfirmedFalseNone() {
            assertThat(session.isBothConfirmed()).isFalse();
        }

        @Test
        @DisplayName("resetConfirmation should reset both confirmations")
        void resetConfirmation() {
            session.setConfirmed(uuid1, true);
            session.setConfirmed(uuid2, true);

            session.resetConfirmation();

            assertThat(session.isConfirmed(uuid1)).isFalse();
            assertThat(session.isConfirmed(uuid2)).isFalse();
            assertThat(session.isBothConfirmed()).isFalse();
        }

        @Test
        @DisplayName("Should unconfirm player")
        void unconfirm() {
            session.setConfirmed(uuid1, true);
            session.setConfirmed(uuid1, false);
            assertThat(session.isConfirmed(uuid1)).isFalse();
        }
    }

    @Nested
    @DisplayName("State Management")
    class StateManagement {

        @Test
        @DisplayName("setState should set state")
        void setState() {
            session.setState(TradeSession.TradeState.COMPLETED);
            assertThat(session.getState()).isEqualTo(TradeSession.TradeState.COMPLETED);
        }

        @Test
        @DisplayName("Should support all states")
        void allStates() {
            for (TradeSession.TradeState state : TradeSession.TradeState.values()) {
                session.setState(state);
                assertThat(session.getState()).isEqualTo(state);
            }
        }

        @Test
        @DisplayName("TradeState enum should have all expected values")
        void tradeStateValues() {
            assertThat(TradeSession.TradeState.values())
                    .containsExactly(
                            TradeSession.TradeState.TRADING,
                            TradeSession.TradeState.CONFIRMED,
                            TradeSession.TradeState.COMPLETED,
                            TradeSession.TradeState.CANCELLED
                    );
        }
    }
}
