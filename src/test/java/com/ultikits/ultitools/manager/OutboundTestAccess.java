package com.ultikits.ultitools.manager;

/**
 * Lets the wiring tests in {@code utils} build and read a real {@link ServerMonitorManager} without widening its
 * package-private sampling hooks.
 */
public final class OutboundTestAccess {

    private static volatile ServerMonitorManager last;

    private OutboundTestAccess() {
    }

    /** @return a fresh monitor, remembered for {@link #currentTps()} */
    public static ServerMonitorManager newMonitor() {
        ServerMonitorManager monitor = new ServerMonitorManager();
        last = monitor;
        return monitor;
    }

    /**
     * @param error an exception
     * @return the group key the error-report collector files it under
     */
    public static String fingerprintOf(Throwable error) {
        return ErrorReportCollector.fingerprintOf(error);
    }

    /** @return the one-minute TPS the last created monitor reports */
    public static double currentTps() {
        return last.getCurrentTPS();
    }
}
