package com.ultikits.ultitools.utils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The per-server UltiCloud credential (protocol v2, prefix {@code ucs_}) and the facts the device
 * authorization returned with it.
 * <p>
 * The credential is opaque, never expires and has no refresh token; it is revoked by the owner in the
 * panel or replaced by a rotation. The secret is readable only through the package-private
 * {@link #bearerValue()}, and {@link #toString()} leaves it out, so a log line built from this object
 * cannot carry it.
 *
 * @since 6.3.1
 */
final class ServerCredential {

    static final String FIELD_CREDENTIAL = "credential";
    static final String FIELD_CREDENTIAL_ID = "credentialId";
    static final String FIELD_SERVER_ID = "serverId";
    static final String FIELD_USER_ID = "userId";
    static final String FIELD_SERVER_NAME = "serverName";
    static final String FIELD_LINKED_AT = "linkedAt";
    /** The prefix every server credential carries. */
    static final String SECRET_PREFIX = "ucs_";

    private final String credential;
    private final String credentialId;
    private final String serverId;
    private final Long userId;
    private final String serverName;
    private final long linkedAt;

    /**
     * @param credential   the {@code ucs_} secret; must start with {@link #SECRET_PREFIX}
     * @param credentialId the credential's public id, or {@code null}
     * @param serverId     the server's public id (a ULID); must be non-empty
     * @param userId       the linking owner's id, or {@code null}
     * @param serverName   the name the owner chose on the confirmation page, or {@code null}
     * @param linkedAt     when the link was made, epoch milliseconds
     */
    ServerCredential(String credential, String credentialId, String serverId, Long userId,
            String serverName, long linkedAt) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.credentialId = credentialId;
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.userId = userId;
        this.serverName = serverName;
        this.linkedAt = linkedAt;
    }

    /**
     * @param credential the candidate secret
     * @return {@code true} if it has the {@code ucs_} prefix and at least one character after it
     */
    static boolean isWellFormedSecret(String credential) {
        return credential != null && credential.startsWith(SECRET_PREFIX)
                && credential.length() > SECRET_PREFIX.length();
    }

    /**
     * Reads one {@code cloud_credential} document entry.
     *
     * @param entry the value stored under the key, as parsed by {@link CredentialStore}
     * @return the credential, or {@code null} if the entry is not an object, the secret is not
     *         well formed or the server id is missing
     */
    static ServerCredential fromDocument(Object entry) {
        if (!(entry instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) entry;
        String secret = text(map.get(FIELD_CREDENTIAL));
        String serverId = text(map.get(FIELD_SERVER_ID));
        if (!isWellFormedSecret(secret) || serverId == null || serverId.isEmpty()) {
            return null;
        }
        Object linkedAt = map.get(FIELD_LINKED_AT);
        long linkedAtMillis = linkedAt instanceof Number ? ((Number) linkedAt).longValue() : 0L;
        return new ServerCredential(secret, text(map.get(FIELD_CREDENTIAL_ID)), serverId,
                storedUserId(map.get(FIELD_USER_ID)), text(map.get(FIELD_SERVER_NAME)), linkedAtMillis);
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** Reads legacy decimal text only at the persistence boundary, never on the REST wire. */
    private static Long storedUserId(Object value) {
        if (value instanceof Number) {
            return parseUserId((Number) value);
        }
        if (value instanceof String && ((String) value).matches("[0-9]+")) {
            return parseUserId(new BigDecimal((String) value));
        }
        return null;
    }

    /** @return the exact contract integer in [1, 2^53 - 1], or null; never truncates or wraps */
    static Long parseUserId(Number value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal number = new BigDecimal(value.toString());
            if (number.compareTo(BigDecimal.ONE) < 0
                    || number.compareTo(BigDecimal.valueOf(9007199254740991L)) > 0) {
                return null;
            }
            return number.longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    /** @return the document entry {@link ServerCredentialStore} writes under {@code cloud_credential} */
    Map<String, Object> toDocument() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(FIELD_CREDENTIAL, credential);
        entry.put(FIELD_CREDENTIAL_ID, credentialId);
        entry.put(FIELD_SERVER_ID, serverId);
        entry.put(FIELD_USER_ID, userId);
        entry.put(FIELD_SERVER_NAME, serverName);
        entry.put(FIELD_LINKED_AT, linkedAt);
        return entry;
    }

    /**
     * The same link with a rotated secret (plan 22-21, D-10): server id, owner, name and link time are kept, exactly
     * as {@link ServerCredentialStore#replace} keeps them on disk.
     *
     * @param rotatedSecret the new {@code ucs_} secret
     * @param rotatedId     the new credential's public id
     * @return the rotated credential
     */
    ServerCredential rotated(String rotatedSecret, String rotatedId) {
        return new ServerCredential(rotatedSecret, rotatedId, serverId, userId, serverName, linkedAt);
    }

    /** @return the secret, for the {@code Authorization: Bearer} header only -- never log it */
    String bearerValue() {
        return credential;
    }

    /** @return the credential's public id, or {@code null} */
    String getCredentialId() {
        return credentialId;
    }

    /** @return the server's public id (a ULID); never {@code null} */
    String getServerId() {
        return serverId;
    }

    /** @return the linking owner's id, or {@code null} */
    Long getUserId() {
        return userId;
    }

    /** @return the server name chosen on the confirmation page, or {@code null} */
    String getServerName() {
        return serverName;
    }

    /** @return when the link was made, epoch milliseconds */
    long getLinkedAt() {
        return linkedAt;
    }

    /** @return the public fields only -- never the secret */
    @Override
    public String toString() {
        return "ServerCredential{credentialId=" + credentialId + ", serverId=" + serverId
                + ", serverName=" + serverName + ", linkedAt=" + linkedAt + "}";
    }
}
