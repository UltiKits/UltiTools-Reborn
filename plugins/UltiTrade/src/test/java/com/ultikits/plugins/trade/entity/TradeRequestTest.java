package com.ultikits.plugins.trade.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TradeRequest Tests")
class TradeRequestTest {

    private UUID senderUuid;
    private UUID receiverUuid;
    private TradeRequest request;

    @BeforeEach
    void setUp() {
        senderUuid = UUID.randomUUID();
        receiverUuid = UUID.randomUUID();
        request = new TradeRequest(senderUuid, receiverUuid);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should set sender UUID")
        void setSenderUuid() {
            assertThat(request.getSender()).isEqualTo(senderUuid);
        }

        @Test
        @DisplayName("Should set receiver UUID")
        void setReceiverUuid() {
            assertThat(request.getReceiver()).isEqualTo(receiverUuid);
        }

        @Test
        @DisplayName("Should set timestamp to current time")
        void setTimestamp() {
            long now = System.currentTimeMillis();
            assertThat(request.getTimestamp()).isCloseTo(now, within(100L));
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpired {

        @Test
        @DisplayName("Should return false for fresh request")
        void notExpiredFresh() {
            assertThat(request.isExpired(30)).isFalse();
        }

        @Test
        @DisplayName("Should return true for old request")
        void expiredOld() throws Exception {
            // Create request with old timestamp using reflection
            TradeRequest oldRequest = new TradeRequest(senderUuid, receiverUuid);
            long oldTimestamp = System.currentTimeMillis() - 31000L; // 31 seconds ago

            java.lang.reflect.Field field = TradeRequest.class.getDeclaredField("timestamp");
            field.setAccessible(true);
            field.set(oldRequest, oldTimestamp);

            assertThat(oldRequest.isExpired(30)).isTrue();
        }

        @Test
        @DisplayName("Should return false right at timeout boundary")
        void notExpiredBoundary() throws Exception {
            TradeRequest boundaryRequest = new TradeRequest(senderUuid, receiverUuid);
            long boundaryTime = System.currentTimeMillis() - 29000L; // 29 seconds ago

            java.lang.reflect.Field field = TradeRequest.class.getDeclaredField("timestamp");
            field.setAccessible(true);
            field.set(boundaryRequest, boundaryTime);

            assertThat(boundaryRequest.isExpired(30)).isFalse();
        }

        @Test
        @DisplayName("Should handle different timeout values")
        void differentTimeouts() throws Exception {
            TradeRequest testRequest = new TradeRequest(senderUuid, receiverUuid);
            long testTime = System.currentTimeMillis() - 55000L; // 55 seconds ago

            java.lang.reflect.Field field = TradeRequest.class.getDeclaredField("timestamp");
            field.setAccessible(true);
            field.set(testRequest, testTime);

            assertThat(testRequest.isExpired(30)).isTrue();   // 55s > 30s
            assertThat(testRequest.isExpired(60)).isFalse();  // 55s < 60s
            assertThat(testRequest.isExpired(120)).isFalse(); // 55s < 120s
        }
    }

    @Nested
    @DisplayName("Getters")
    class Getters {

        @Test
        @DisplayName("getSender should return sender UUID")
        void getSender() {
            assertThat(request.getSender()).isNotNull().isEqualTo(senderUuid);
        }

        @Test
        @DisplayName("getReceiver should return receiver UUID")
        void getReceiver() {
            assertThat(request.getReceiver()).isNotNull().isEqualTo(receiverUuid);
        }

        @Test
        @DisplayName("getTimestamp should return positive value")
        void getTimestamp() {
            assertThat(request.getTimestamp()).isPositive();
        }
    }
}
