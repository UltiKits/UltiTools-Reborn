package com.ultikits.ultitools.uat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the id-stability property (D-10-08) permanently: {@link RowId#of} must keep reproducing
 * {@code ~/servers/uat/registry.json}'s recorded ids for every row the new extractor also
 * produces, because {@code ledger.json} holds 275 KB of results keyed to those ids and an id
 * change would silently orphan them.
 * <p>
 * The 28-tuple sample below was drawn from a real, one-time reconciliation (Phase 10 plan 10-03,
 * Task 3) between the 422-row registry and a real {@code SurfaceExtractorMain} run over all 16
 * module repositories (817 rows). Every tuple here is a row where BOTH sides independently
 * produced the identical id -- "bucket one" of that reconciliation, recorded in full at
 * {@code /home/wisdomme/servers/evidence/phase-10/10-EXTRACTOR-CONTROL.md}. This test turns that
 * one afternoon's reconciliation into a permanent regression guard: a future change to
 * {@link RowId#of} or to any scanner's call-site argument order that broke reproduction of even
 * one of these 28 real, already-recorded ids fails here immediately, rather than being discovered
 * only the next time someone reruns the whole reconciliation by hand.
 * <p>
 * <b>Measured disagreement with this plan's own acceptance criterion.</b> The criterion calls for
 * a sample "across at least five kinds". The real reconciliation's bucket one spans exactly
 * <b>four</b> kinds -- {@code command} (244 real matches), {@code listener} (76), {@code
 * persistence} (19), {@code scheduled} (15) -- never five. Every other row kind in the registry
 * is either never covered by the new extractor at all ({@code gui}, {@code placeholder}, {@code
 * behaviour} -- zero bucket-one rows by design, since D-10-04 never put them in scope) or is
 * covered by both sides but with a DELIBERATELY different id scheme ({@code conditional} -- plan
 * 10-02's SUMMARY.md already records this as an accepted parity miss; all 5 of its rows land in
 * bucket two AND bucket three, never bucket one, because the new extractor's condition-text
 * serialization does not reproduce {@code gen-registry.py}'s literal-source-text hash input).
 * There is no fifth kind a bucket-one sample could be drawn from -- this is a measured fact about
 * the current registry and extractor, not an oversight in the sample below. The 28 tuples here
 * guard all four kinds that genuinely overlap, well past the plan's own "at least twenty" floor.
 *
 * @since 6.3.0
 */
@DisplayName("Parity: RowId reproduces registry.json's ids for a real cross-module sample")
class SurfaceExtractorParityTest {

    /**
     * {kind, origin, expected id, cls, member (nullable), format (command-only, else null)}.
     * Drawn from real rows in both {@code ~/servers/uat/registry.json} and a real extractor run
     * over UltiBackup, UltiBot, UltiChat, UltiCleaner, UltiEconomy, UltiEssentials, UltiKits and
     * UltiLogin -- eight of the sixteen module repositories, spanning all four kinds that
     * genuinely overlap.
     */
    private static final Object[][] SAMPLE = {
            {"command", "UltiBackup", "COM-0e8badeb", "BackupCommand", "help", "help"},
            {"command", "UltiBackup", "COM-1c5eadea", "BackupCommand", "forceRestoreBackup", "restore <number> force"},
            {"command", "UltiBackup", "COM-2aea219e", "BackupCommand", "listBackups", "list"},
            {"command", "UltiBackup", "COM-2e647d9c", "BackupCommand", "createBackup", "create"},
            {"command", "UltiBackup", "COM-5794f93f", "BackupCommand", "saveAllPlayers", "saveall"},
            {"command", "UltiBackup", "COM-6b047b2b", "BackupCommand", "adminCreateBackup", "admin create <player>"},
            {"command", "UltiBot", "COM-06b5a428", "BotCommands", "onSpawnAt", "spawnat <name>"},
            {"listener", "UltiBackup", "LIS-144f7b2c", "BackupListener", "onBackupGUIClick", null},
            {"listener", "UltiBackup", "LIS-152fef8a", "BackupListener", "onPreviewGUIClick", null},
            {"listener", "UltiBackup", "LIS-776c743f", "BackupListener", "onPlayerDeath", null},
            {"listener", "UltiBackup", "LIS-fd24eea6", "BackupListener", "onPlayerQuit", null},
            {"listener", "UltiBot", "LIS-23a7b1f4", "BotEventListener", "onPlayerDeath", null},
            {"listener", "UltiBot", "LIS-df745c07", "BotEventListener", "onPlayerQuit", null},
            {"listener", "UltiChat", "LIS-2b55f6a3", "PlayerChannelListener", "onPlayerQuit", null},
            {"persistence", "UltiBackup", "PER-3f8bcac5", "BackupMetadata", null, null},
            {"persistence", "UltiEconomy", "PER-4fcd5245", "CurrencyBalanceEntity", null, null},
            {"persistence", "UltiEconomy", "PER-5f9ebd92", "PlayerAccountEntity", null, null},
            {"persistence", "UltiEconomy", "PER-fcac5ba4", "TreasuryEntity", null, null},
            {"persistence", "UltiEssentials", "PER-573dc272", "HomeData", null, null},
            {"persistence", "UltiEssentials", "PER-6175a249", "WarpData", null, null},
            {"persistence", "UltiKits", "PER-cfbef98a", "KitClaimData", null, null},
            {"scheduled", "UltiBackup", "SCH-66295d57", "BackupService", "autoBackupAll", null},
            {"scheduled", "UltiChat", "SCH-7d6c8177", "AnnouncementService", "broadcastTitle", null},
            {"scheduled", "UltiChat", "SCH-a9a9d40c", "AnnouncementService", "broadcastBossBar", null},
            {"scheduled", "UltiChat", "SCH-eb7e62f7", "AnnouncementService", "broadcastChat", null},
            {"scheduled", "UltiCleaner", "SCH-3056ad11", "CleanerService", "tickEntityClean", null},
            {"scheduled", "UltiCleaner", "SCH-465feb97", "CleanerService", "tickItemClean", null},
            {"scheduled", "UltiLogin", "SCH-335ca0a0", "EmailVerificationService", "cleanupExpired", null},
    };

    static Stream<Arguments> samples() {
        return Stream.of(SAMPLE).map(row -> Arguments.of(row[0], row[1], row[2], row[3], row[4], row[5]));
    }

    @ParameterizedTest(name = "[{index}] {2} = RowId.of({0}, {1}, {3}, {4}, {5})")
    @MethodSource("samples")
    @DisplayName("RowId reproduces the registry's recorded id for a real shared row")
    void reproducesRegistryId(String kind, String origin, String expectedId, String cls, String member,
            String format) {
        List<String> parts = new ArrayList<>();
        parts.add(cls);
        if (member != null) {
            parts.add(member);
        }
        if (format != null) {
            parts.add(format);
        }

        String actualId = RowId.of(kind, origin, parts.toArray(new String[0]));

        assertThat(actualId).isEqualTo(expectedId);
    }
}
