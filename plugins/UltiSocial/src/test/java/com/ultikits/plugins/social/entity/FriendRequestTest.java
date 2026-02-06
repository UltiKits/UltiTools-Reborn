package com.ultikits.plugins.social.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FriendRequest Entity Tests")
class FriendRequestTest {

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should create request with all fields")
        void createWithAllFields() {
            UUID sender = UUID.randomUUID();
            UUID receiver = UUID.randomUUID();
            long timestamp = System.currentTimeMillis();

            FriendRequest request = new FriendRequest(sender, "Sender", receiver, timestamp);

            assertThat(request.getSender()).isEqualTo(sender);
            assertThat(request.getSenderName()).isEqualTo("Sender");
            assertThat(request.getReceiver()).isEqualTo(receiver);
            assertThat(request.getTimestamp()).isEqualTo(timestamp);
        }
    }

    @Nested
    @DisplayName("Factory Method")
    class FactoryMethod {

        @Test
        @DisplayName("Should create request with factory method")
        void createRequest() {
            UUID sender = UUID.randomUUID();
            UUID receiver = UUID.randomUUID();
            long beforeCreate = System.currentTimeMillis();

            FriendRequest request = FriendRequest.create(sender, "TestSender", receiver);

            long afterCreate = System.currentTimeMillis();

            assertThat(request.getSender()).isEqualTo(sender);
            assertThat(request.getSenderName()).isEqualTo("TestSender");
            assertThat(request.getReceiver()).isEqualTo(receiver);
            assertThat(request.getTimestamp()).isBetween(beforeCreate, afterCreate);
        }
    }

    @Nested
    @DisplayName("Expiration Check")
    class ExpirationCheck {

        @Test
        @DisplayName("Should not be expired for recent request")
        void notExpiredRecent() {
            FriendRequest request = FriendRequest.create(
                    UUID.randomUUID(),
                    "Sender",
                    UUID.randomUUID()
            );

            boolean expired = request.isExpired(60);

            assertThat(expired).isFalse();
        }

        @Test
        @DisplayName("Should be expired for old request")
        void expiredOld() {
            long oldTimestamp = System.currentTimeMillis() - 120000; // 2 minutes ago
            FriendRequest request = new FriendRequest(
                    UUID.randomUUID(),
                    "Sender",
                    UUID.randomUUID(),
                    oldTimestamp
            );

            boolean expired = request.isExpired(60); // 60 second timeout

            assertThat(expired).isTrue();
        }

        @Test
        @DisplayName("Should be expired exactly at timeout")
        void expiredExact() {
            long exactTimestamp = System.currentTimeMillis() - 60001; // Just over 60 seconds
            FriendRequest request = new FriendRequest(
                    UUID.randomUUID(),
                    "Sender",
                    UUID.randomUUID(),
                    exactTimestamp
            );

            boolean expired = request.isExpired(60);

            assertThat(expired).isTrue();
        }

        @Test
        @DisplayName("Should not be expired just before timeout")
        void notExpiredJustBefore() {
            long recentTimestamp = System.currentTimeMillis() - 30000; // 30 seconds ago
            FriendRequest request = new FriendRequest(
                    UUID.randomUUID(),
                    "Sender",
                    UUID.randomUUID(),
                    recentTimestamp
            );

            boolean expired = request.isExpired(60);

            assertThat(expired).isFalse();
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersAndGetters {

        @Test
        @DisplayName("Should update sender")
        void updateSender() {
            FriendRequest request = FriendRequest.create(
                    UUID.randomUUID(),
                    "OldSender",
                    UUID.randomUUID()
            );

            UUID newSender = UUID.randomUUID();
            request.setSender(newSender);

            assertThat(request.getSender()).isEqualTo(newSender);
        }

        @Test
        @DisplayName("Should update sender name")
        void updateSenderName() {
            FriendRequest request = FriendRequest.create(
                    UUID.randomUUID(),
                    "OldName",
                    UUID.randomUUID()
            );

            request.setSenderName("NewName");

            assertThat(request.getSenderName()).isEqualTo("NewName");
        }

        @Test
        @DisplayName("Should update receiver")
        void updateReceiver() {
            FriendRequest request = FriendRequest.create(
                    UUID.randomUUID(),
                    "Sender",
                    UUID.randomUUID()
            );

            UUID newReceiver = UUID.randomUUID();
            request.setReceiver(newReceiver);

            assertThat(request.getReceiver()).isEqualTo(newReceiver);
        }

        @Test
        @DisplayName("Should update timestamp")
        void updateTimestamp() {
            FriendRequest request = FriendRequest.create(
                    UUID.randomUUID(),
                    "Sender",
                    UUID.randomUUID()
            );

            long newTimestamp = 12345L;
            request.setTimestamp(newTimestamp);

            assertThat(request.getTimestamp()).isEqualTo(newTimestamp);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Should be equal with same data")
        void equalWithSameData() {
            UUID sender = UUID.randomUUID();
            UUID receiver = UUID.randomUUID();

            FriendRequest request1 = new FriendRequest(sender, "Sender", receiver, 1000L);
            FriendRequest request2 = new FriendRequest(sender, "Sender", receiver, 1000L);

            assertThat(request1).isEqualTo(request2);
        }

        @Test
        @DisplayName("Should not be equal with different sender")
        void notEqualDifferentSender() {
            UUID receiver = UUID.randomUUID();

            FriendRequest request1 = new FriendRequest(UUID.randomUUID(), "Sender", receiver, 1000L);
            FriendRequest request2 = new FriendRequest(UUID.randomUUID(), "Sender", receiver, 1000L);

            assertThat(request1).isNotEqualTo(request2);
        }
    }
}
