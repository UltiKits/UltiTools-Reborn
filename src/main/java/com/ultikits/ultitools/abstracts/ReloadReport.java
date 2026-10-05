package com.ultikits.ultitools.abstracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a module's reload did not manage to do (#529).
 * <p>
 * The framework hands a fresh report to {@link UltiToolsPlugin#onReload(ReloadReport)} on every
 * reload. A module whose reload completed only in part records each part it could not reload with
 * {@link #partial(String)} and returns normally. {@code /ul reload <name>} then tells the sender
 * which parts did not reload, and a full {@code /ul reload} lists the module with those parts in
 * its summary, instead of reporting an unconditional success. The framework's own reload log line
 * for the module says the same.
 * <p>
 * The framework records the parts of its own reload steps that did not take effect in the same
 * report, before the hook runs: a config-bound {@code @Scheduled} or {@code @CmdCD} value it refused
 * and kept, or a binding step that failed (#595).
 * <p>
 * Record a part and carry on when the rest of the module still works. Throw instead when the reload
 * cannot continue at all: {@code reloadSelf()} then logs a failure line naming the module,
 * {@code /ul reload <name>} replies failure, and a full {@code /ul reload} carries on with the next
 * module and names this one as failed.
 * <pre>
 * &#64;Override
 * protected void onReload(ReloadReport report) {
 *     for (String service : services) {
 *         try {
 *             restart(service);
 *         } catch (RuntimeException e) {
 *             getLogger().error(e, "Could not restart " + service);
 *             report.partial(service + " did not restart: " + e.getMessage());
 *         }
 *     }
 * }
 * </pre>
 * <p>
 * A report is safe to write from more than one thread. Readers get immutable snapshots. The
 * framework creates one per reload with the public no-argument constructor; a module's own test
 * may create one the same way to call its {@code onReload(ReloadReport)} directly.
 *
 * @since 6.3.0
 */
public final class ReloadReport {

    /** Recorded in place of a missing or blank reason, so the reload is still reported as partial. */
    static final String UNSPECIFIED_REASON = "(no reason given)";

    private final List<String> partialReasons = new ArrayList<>();

    /**
     * Records one part of this reload that did not reload.
     *
     * @param reason what did not reload and, if known, why -- shown to the operator as written; a
     *               {@code null} or blank reason is recorded as "(no reason given)"
     */
    public synchronized void partial(String reason) {
        partialReasons.add(reason == null || reason.trim().isEmpty() ? UNSPECIFIED_REASON : reason);
    }

    /**
     * Whether any part of this reload was reported as not reloaded.
     *
     * @return {@code true} if {@link #partial(String)} was called at least once
     */
    public synchronized boolean isPartial() {
        return !partialReasons.isEmpty();
    }

    /**
     * The parts reported as not reloaded, in the order they were recorded.
     *
     * @return an immutable snapshot; later calls to {@link #partial(String)} do not change it
     */
    public synchronized List<String> getPartialReasons() {
        return Collections.unmodifiableList(new ArrayList<>(partialReasons));
    }
}
