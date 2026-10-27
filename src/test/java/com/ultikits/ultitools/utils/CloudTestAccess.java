package com.ultikits.ultitools.utils;

import java.nio.file.Path;

/**
 * Lets tests outside this package drive the cloud login against a temporary credential file and a
 * chosen base URL; the hooks themselves are package-private so production code cannot reach them.
 */
public final class CloudTestAccess {

    private CloudTestAccess() {
    }

    /**
     * Points the credential store at {@code directory}/credentials.json and installs a fresh session.
     *
     * @param directory a temporary directory
     */
    public static void useTemporaryCredentials(Path directory) {
        CredentialStore.setTargetPathForTesting(directory.resolve("credentials.json"));
        CredentialStore.setOldLocationForTesting(directory.resolve("pre-migration-data.json"));
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
    }

    /** Restores the credential store, the session and the rate limiter. */
    public static void reset() {
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        HttpRequestUtils.resetBaseUrl();
    }

    /** @param url the UltiCloud base URL; {@code ""} makes every request fail locally */
    public static void setBaseUrl(String url) {
        HttpRequestUtils.setBaseUrlForTesting(url);
    }

    /**
     * Stores a synthetic linked server credential, as a completed device login would.
     *
     * @param serverName the linked server's name
     * @param serverId   the linked server's id
     */
    public static void storeLinkedCredential(String serverName, String serverId) {
        ServerCredentialStore.save(new ServerCredential("ucs_TESTSECRET", "cred-1", serverId, 43L, serverName,
                1_700_000_000_000L));
    }
}
