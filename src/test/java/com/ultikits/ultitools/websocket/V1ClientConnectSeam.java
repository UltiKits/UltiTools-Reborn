package com.ultikits.ultitools.websocket;

/**
 * Test-scope access to {@link UltiPanelWebSocketClient}'s package-private connect switch, for the
 * kept v1 log-stream tests in other packages (plan 22-25).
 * <p>
 * As of 6.3.1 a v1 client never opens a socket. Those tests still drive the v1 log senders the
 * managers keep until 6.4.0 (S-67) against their own local socket server, so they permit connecting
 * for the duration of one test and revoke it afterwards.
 */
public final class V1ClientConnectSeam {

    private V1ClientConnectSeam() {
    }

    /**
     * Lets (or stops letting) a v1 client connect in this JVM.
     *
     * @param permit {@code true} to connect normally, {@code false} for the 6.3.1 refusal
     */
    public static void permitConnect(boolean permit) {
        UltiPanelWebSocketClient.permitConnectForTesting(permit);
    }
}
