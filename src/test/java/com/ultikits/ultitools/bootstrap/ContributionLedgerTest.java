package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

class ContributionLedgerTest {
    private static final String MODULE = "MyModule";
    private static final Instant FIRST = Instant.parse("2026-11-25T12:00:00Z");
    private static final Instant SECOND = FIRST.plusSeconds(1);
    private static final ContentEntry EDGE = new ContentEntry("enchantment", "mymodule:edge");
    private static final ContentEntry PACK = new ContentEntry("datapack", "UltiTools/mymodule.test");
    @TempDir Path data;
    private final Logger logger = mock(Logger.class);

    @Test
    void successfulBootstrapReplacesTheRow() {
        var before = update(start(List.of(EDGE)), FIRST);
        var after = update(start(List.of(PACK, EDGE)), SECOND);
        assertThat(before.rows().get(MODULE).entries()).containsExactly(EDGE);
        assertThat(after.rows().get(MODULE)).satisfies(row -> {
            assertThat(row.present()).isTrue();
            assertThat(row.namespace()).isEqualTo("mymodule");
            assertThat(row.lastStart()).isEqualTo(SECOND.toString());
            assertThat(row.entries()).containsExactly(PACK, EDGE);
            assertThat(row.dropped()).isEmpty();
        });
    }

    @Test
    void recordsDroppedEntriesOnlyOnceAndTreatsRemovedHookAsEmpty() {
        update(start(List.of(EDGE, PACK)), FIRST);
        var after = update(start(List.of(PACK)), SECOND);
        assertThat(after.dropped()).containsEntry(MODULE, List.of(EDGE));
        assertThat(after.rows().get(MODULE).dropped()).containsExactly(EDGE);
        var noHook = new ContributionLedger.Start(Map.of(), Set.of(MODULE), Set.of(MODULE));
        var withoutHook = update(noHook, SECOND.plusSeconds(1));
        assertThat(withoutHook.dropped()).containsEntry(MODULE, List.of(PACK));
        assertThat(withoutHook.rows().get(MODULE).entries()).isEmpty();
        var next = update(noHook, SECOND.plusSeconds(2));
        assertThat(next.rows()).isEmpty();
        assertThat(next.dropped()).isEmpty();
    }

    @Test
    void failureOrUnrunHookKeepsThePreviousRow() {
        var before = update(start(List.of(EDGE)), FIRST);
        var unrun = new ContributionLedger.Start(Map.of(), Set.of(MODULE), Set.of());
        var after = update(unrun, SECOND);
        assertThat(after.rows()).isEqualTo(before.rows());
        assertThat(after.dropped()).isEmpty();
        assertThat(after.removed()).isEmpty();
    }

    @Test
    void removalIsRecordedOnceAndReinstallClearsTheTimestamp() {
        update(start(List.of(EDGE)), FIRST);
        var notInstalled = new ContributionLedger.Start(Map.of(), Set.of(), Set.of());
        var removed = update(notInstalled, SECOND);
        assertThat(removed.removed()).containsEntry(MODULE, List.of(EDGE));
        assertThat(removed.rows().get(MODULE)).satisfies(row -> {
            assertThat(row.present()).isFalse();
            assertThat(row.removedAt()).isEqualTo(SECOND.toString());
            assertThat(row.entries()).containsExactly(EDGE);
        });
        var stillGone = update(notInstalled, SECOND.plusSeconds(1));
        assertThat(stillGone.removed()).isEmpty();
        assertThat(stillGone.rows()).isEqualTo(removed.rows());
        var reinstalled = update(start(List.of(EDGE, PACK)), SECOND.plusSeconds(2));
        assertThat(reinstalled.rows().get(MODULE).removedAt()).isNull();
        assertThat(reinstalled.rows().get(MODULE).present()).isTrue();
    }

    @Test
    void jarRefusedByApiLevelDoesNotProduceARemovalWarning() throws IOException {
        var before = update(start(List.of(EDGE)), FIRST);
        Path jar = BootstrapFixtures.jar(data.resolve("mymodule.jar"),
                BootstrapFixtures.descriptor(MODULE, null).replace("api-version: 700", "api-version: 630"), Map.of());
        var admission = new ModuleJarAdmission();
        assertThat(admission.inspect(jar.toFile()).isApiAllowed()).isFalse();

        var after = ContributionLedger.update(data, admission, new ContributionRegistry(admission, logger), logger);
        assertThat(after.removed()).isEmpty();
        assertThat(after.dropped()).isEmpty();
        assertThat(after.rows()).isEqualTo(before.rows());
    }

    @Test
    void absentJarStillProducesARemovalWarning() throws IOException {
        Path jar = BootstrapFixtures.jar(data.resolve("mymodule.jar"), BootstrapFixtures.descriptor(MODULE, null), Map.of());
        update(start(List.of(EDGE)), FIRST);
        Files.delete(jar);

        var admission = new ModuleJarAdmission();
        var after = ContributionLedger.update(data, admission, new ContributionRegistry(admission, logger), logger);
        assertThat(after.removed()).containsExactlyEntriesOf(Map.of(MODULE, List.of(EDGE)));
        assertThat(after.rows().get(MODULE).present()).isFalse();
    }

    @Test
    void emptySuccessfulModuleDoesNotCreateALedgerFile() {
        var ledger = update(start(List.of()), FIRST);
        assertThat(ledger.rows()).isEmpty();
        assertThat(record()).doesNotExist();
    }

    @Test
    void aFailedWriteLeavesThePreviousDerivedRecordAndUsesNewInMemoryResult() throws Exception {
        // Only the framework-owned record is replaced; operator configuration has a separate path.
        Path operator = Files.writeString(data.resolve("config.yml"), "language: en\n");
        update(start(List.of(EDGE)), FIRST);
        byte[] original = Files.readAllBytes(record());
        var result = ContributionLedger.update(data, start(List.of(PACK)), SECOND, logger, (channel, bytes) -> {
            channel.write(ByteBuffer.wrap(new byte[] {'{'}));
            throw new IOException("write interrupted");
        });
        assertThat(Files.readAllBytes(record())).isEqualTo(original);
        assertThat(operator).hasContent("language: en");
        assertThat(result.rows().get(MODULE).entries()).containsExactly(PACK);
        verify(logger).log(eq(Level.WARNING), contains("Could not save the custom content record"), any(Throwable.class));
    }

    @Test
    void quarantinesUnreadableRecordAndKeepsOnlyThreeCopies() throws Exception {
        Files.createDirectories(record().getParent());
        for (int i = 0; i < 4; i++) {
            Files.writeString(record(), i == 0 ? "{not json" : "{\"schema\":42,\"modules\":{}}");
            var ledger = update(start(List.of(EDGE)), FIRST.plusSeconds(i));
            assertThat(ledger.unreadable()).isTrue();
            assertThat(ledger.rows().get(MODULE).entries()).containsExactly(EDGE);
            assertThat(ledger.successful()).containsOnly(MODULE);
        }
        try (var files = Files.list(record().getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("contributions.json.unreadable-")))
                    .hasSize(3);
        }
        assertThat(record()).content().contains("\"schema\": 1", "\"present\": true");
    }

    @Test
    void unrecognizedKindsRoundTripWithoutBeingDiscarded() {
        var future = new ContentEntry("future_registry", "mymodule:value");
        update(start(List.of(future, EDGE)), FIRST);
        var unrun = new ContributionLedger.Start(Map.of(), Set.of(MODULE), Set.of());
        var after = update(unrun, SECOND);
        assertThat(after.rows().get(MODULE).entries()).containsExactly(future, EDGE);
        assertThat(record()).content().contains("\"kind\": \"future_registry\"");
    }

    private Path record() { return data.resolve("bootstrap/contributions.json"); }

    private ContributionLedger update(ContributionLedger.Start start, Instant time) {
        return ContributionLedger.update(data, start, time, logger, ContributionLedger::writeBytes);
    }

    private static ContributionLedger.Start start(List<ContentEntry> entries) {
        return new ContributionLedger.Start(Map.of(MODULE, entries), Set.of(MODULE), Set.of());
    }
}
