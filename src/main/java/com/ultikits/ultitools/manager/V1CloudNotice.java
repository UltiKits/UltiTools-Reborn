package com.ultikits.ultitools.manager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The one INFO line per run that a kept protocol v1 entry point of a manager writes when it is
 * called (plan 22-25, S-04 option b).
 * <p>
 * As of 6.3.1 the framework never calls these entry points (the {@code setWebSocketClient} setters,
 * the {@code initialize} methods that take a v1 client, the v1 transmitter constructor): its own
 * UltiCloud session speaks protocol 2.0.0. They still accept the client a caller hands in, because
 * the v1 senders behind them stay until 6.4.0 (S-67); the line makes that state explicit instead of
 * letting the call look as if it connected anything. Each line goes to the owning class's logger.
 */
final class V1CloudNotice {

    /** {@code Owner#member} keys that already logged their line in this run. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private V1CloudNotice() {
    }

    /**
     * Logs the line for {@code member} of {@code owner}, unless this run already did.
     *
     * @param owner  the class that declares the entry point
     * @param member the entry point as it should read in the line, for example
     *               {@code "setWebSocketClient"}
     */
    static void once(Class<?> owner, String member) {
        String name = owner.getSimpleName() + "#" + member;
        if (LOGGED.add(name)) {
            Logger.getLogger(owner.getName()).info("UltiCloud: " + name + " belongs to the protocol v1 "
                    + "cloud, which UltiCloud no longer serves; the framework never calls it and a v1 client "
                    + "opens no connection, so it only feeds the kept v1 senders. It is deprecated and removed "
                    + "in 6.4.0; the framework's own UltiCloud session (protocol 2.0.0) carries the panel.");
        }
    }

    /** Test-only: forgets every line this run already logged. */
    static void resetForTesting() {
        LOGGED.clear();
    }
}
