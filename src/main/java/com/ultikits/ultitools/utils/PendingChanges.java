package com.ultikits.ultitools.utils;

import java.io.File;
import java.util.List;
import java.util.Objects;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/** A bounded view of the transaction journal and its single-entry revocation operation. */
@ApiStatus.Internal
public final class PendingChanges {
    /** Maximum number of entries in the list exposed to callers. */
    public static final int MAX_ITEMS = 200;

    private final ModuleFileTransactions transactions;

    public PendingChanges(ModuleFileTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** The operation that will take effect at the next start. */
    public enum Kind { INSTALL, UPDATE, UNINSTALL }

    /** The states visible while the server is running. */
    public enum State { PENDING, DOWNLOADING, FAILED, NEEDS_OPERATOR, IN_PROGRESS }

    /** The kind and optional name of the sender that requested a change. */
    public record Requester(String kind, String name) {
        static Requester from(CommandSender sender) {
            if (sender == null) {
                return null;
            }
            if (sender instanceof Player player) {
                return new Requester("player", player.getName());
            }
            if (sender instanceof ConsoleCommandSender && !(sender instanceof RemoteConsoleCommandSender)) {
                return new Requester("console", null);
            }
            String name = sender.getName();
            if (name != null && name.length() > 64) {
                int length = Character.isHighSurrogate(name.charAt(63)) ? 63 : 64;
                name = name.substring(0, length);
            }
            return new Requester("remote", name);
        }
    }

    /** One transaction; a download has a provisional id and no on-disk revision. */
    public record PendingChange(String id, String revision, Kind kind, String module, String fromVersion,
                                String toVersion, List<String> files, Long requestedAt, Requester requestedBy,
                                State state, String failure, File recordFile, ModuleContent content) {
        public PendingChange {
            files = List.copyOf(files);
        }

        /** @return whether this state admits a revocation attempt */
        public boolean revocable() {
            return state == State.PENDING || state == State.DOWNLOADING || state == State.FAILED;
        }
    }

    /** A bounded list with the number omitted and the generation observed in the same read. */
    public record Snapshot(List<PendingChange> entries, int truncated, long generation) {
        public Snapshot {
            entries = List.copyOf(entries);
        }

        /** @return the number of visible and truncated changes combined */
        public int total() {
            return entries.size() + truncated;
        }
    }

    /** @return the first {@link #MAX_ITEMS} entries, oldest first, then by id */
    public List<PendingChange> list() {
        return snapshot().entries();
    }

    /** @return the entries, their truncation count and the generation from one locked read */
    public Snapshot snapshot() {
        return transactions.pendingSnapshot();
    }

    /** @return the entry matching the identify string, module name or JAR name, or null */
    public PendingChange findByModule(String name) {
        return name == null || name.isBlank() ? null : transactions.findPending(name);
    }

    /** @return the result of finding and revoking a module in one locked operation */
    public RevokeResult revokeByModule(String name) {
        return name == null || name.isBlank() ? RevokeResult.notFound() : transactions.revokeByModule(name);
    }

    /**
     * Revokes one transaction by id; the revision check prevents acting on a stale view.
     * A null expected revision matches only a download when checking is enabled.
     *
     * @param id the transaction id, as returned by {@link #list()}
     * @param expectedRevision the revision observed by the caller, or null for a download
     * @param checkRevision whether to compare the caller's revision before mutating anything
     * @return the result of the attempt
     */
    public RevokeResult revoke(String id, String expectedRevision, boolean checkRevision) {
        return transactions.revokePending(id, expectedRevision, checkRevision);
    }

    /** The result of revoking one entry; before is also available for an unrevocable entry. */
    public record RevokeResult(Outcome outcome, PendingChange before, String error) {
        /** A revocation decision made while holding the transaction lock. */
        public enum Outcome { REVOKED, NOT_FOUND, CHANGED, NOT_REVOCABLE, FAILED }

        static RevokeResult revoked(PendingChange before) { return new RevokeResult(Outcome.REVOKED, before, null); }
        static RevokeResult notFound() { return new RevokeResult(Outcome.NOT_FOUND, null, null); }
        static RevokeResult changed() { return new RevokeResult(Outcome.CHANGED, null, null); }
        static RevokeResult notRevocable(PendingChange before) { return new RevokeResult(Outcome.NOT_REVOCABLE, before, null); }
        static RevokeResult failed(String error) { return new RevokeResult(Outcome.FAILED, null, error); }
    }
}
