package com.ultikits.ultitools.utils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import com.ultikits.ultitools.UltiTools;

/**
 * The only reader and writer of the {@code cloud_credential} entry of
 * {@code <server root>/.ultikits/credentials.json}, always through {@link CredentialStore}.
 * <p>
 * The entry is {@code {credential, credentialId, serverId, userId, serverName, linkedAt}}. Saving it
 * removes the 6.3.0 user token ({@code cloud_token}) in the same atomic write, because a server holds
 * only its server credential from protocol v2 on. Every other key -- the 32-hex {@code uuid} install
 * id among them -- is carried through unchanged.
 * <p>
 * <b>Why these writes cannot overwrite operator content.</b> {@code credentials.json} is
 * framework-owned state, not operator configuration: {@link CredentialStore} is its sole owner, it is
 * created and maintained by the framework alone (owner-only file mode), and no operator workflow
 * edits it. Both write paths here change only the two framework keys named above inside one
 * {@link CredentialStore#update} call, and {@link CredentialStore#update} refuses to write over a
 * file it cannot parse, so a hand-damaged file is never replaced.
 *
 * @since 6.3.1
 */
final class ServerCredentialStore {

    /** The document key of the server credential. */
    static final String KEY = "cloud_credential";
    /** The document key of the 6.3.0 user OAuth token, which a successful link removes. */
    static final String LEGACY_TOKEN_KEY = "cloud_token";

    /** Whether the current malformed entry was already reported, so one bad file warns once. */
    private static final AtomicBoolean REPORTED_MALFORMED = new AtomicBoolean();

    private ServerCredentialStore() {
    }

    /**
     * @return the stored credential, or {@code null} when the file is absent or unreadable, the key is
     *         missing, or the entry is malformed (reported once with a WARNING that names the file,
     *         never the content)
     */
    static ServerCredential load() {
        CredentialStore.ReadResult result = CredentialStore.read();
        if (!result.isParsed()) {
            // Absent: nothing linked. Unreadable: CredentialStore reports the file itself.
            return null;
        }
        Object entry = result.data().get(KEY);
        if (entry == null) {
            return null;
        }
        ServerCredential credential = ServerCredential.fromDocument(entry);
        if (credential == null) {
            if (REPORTED_MALFORMED.compareAndSet(false, true)) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "UltiCloud: the " + KEY + " entry in " + CredentialStore.CREDENTIAL_FILE_NAME
                        + " is not a valid server credential and was ignored. Run /ulticloud login to "
                        + "link this server again.");
            }
            return null;
        }
        REPORTED_MALFORMED.set(false);
        return credential;
    }

    /**
     * Writes {@code credential} under {@link #KEY} and removes {@link #LEGACY_TOKEN_KEY}, in one
     * atomic write. Framework-owned state only; see the class javadoc for why this cannot overwrite
     * operator content.
     *
     * @param credential the credential to persist
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if the write fails or the
     *         current file cannot be parsed
     */
    static void save(ServerCredential credential) {
        CredentialStore.update(document -> {
            document.put(KEY, credential.toDocument());
            document.remove(LEGACY_TOKEN_KEY);
            return document;
        });
        REPORTED_MALFORMED.set(false);
    }

    /**
     * Removes the credential only if its public id still matches the rejected connection's id.
     * The comparison and removal share one CredentialStore lock acquisition, so a newer login or
     * rotation cannot be erased by a late rejection. Framework-owned state only; see the class
     * javadoc for why this cannot overwrite operator content.
     *
     * @param credentialId the id used by the rejected connection
     * @return whether that entry was removed
     */
    static boolean deleteIfMatches(String credentialId) {
        if (credentialId == null) {
            return false;
        }
        AtomicBoolean removed = new AtomicBoolean();
        CredentialStore.update(document -> {
            ServerCredential stored = ServerCredential.fromDocument(document.get(KEY));
            if (stored != null && credentialId.equals(stored.getCredentialId())) {
                document.remove(KEY);
                removed.set(true);
            }
            return document;
        });
        return removed.get();
    }

    /**
     * Removes only the {@link #KEY} entry. The file is not rewritten when the entry is absent.
     * Framework-owned state only; see the class javadoc.
     *
     * @return {@code true} if an entry existed and was removed
     */
    static boolean delete() {
        CredentialStore.ReadResult result = CredentialStore.read();
        if (!result.isParsed() || !result.data().containsKey(KEY)) {
            return false;
        }
        AtomicBoolean existed = new AtomicBoolean();
        CredentialStore.update(document -> {
            existed.set(document.remove(KEY) != null);
            return document;
        });
        return existed.get();
    }
}
