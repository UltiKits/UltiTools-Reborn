package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.utils.ModuleContent;
import com.ultikits.ultitools.utils.ModuleContentKind;
import com.ultikits.ultitools.utils.ModuleContentLedger;
import com.ultikits.ultitools.utils.ModuleContentLedgers;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

class ContributionLedgerQueryTest {
    @TempDir Path folder;

    @AfterEach void restoreDefault() {
        ModuleContentLedgers.install(ModuleContentLedger.NONE);
    }

    @Test
    void successfulBootstrapInstallsTheQueryWithPrimaryModuleNameAndMemoryOnlyAnswers() throws Exception {
        Path data = Files.createDirectories(folder.resolve("plugins/UltiTools"));
        Path jar = ModuleFileTransactions.modulesFolder(data.toFile()).toPath().resolve("Boot2Content.jar");
        String hook = "fixture.Content";
        BootstrapFixtures.jar(jar, BootstrapFixtures.descriptor("Boot2Content", hook),
                Map.of(hook, BootstrapFixtures.allChannelsHook(hook)), Map.of("datapacks/probe/pack.mcmeta", "{}"));
        var bootstrap = spy(new UltiToolsBootstrap());
        var context = mock(BootstrapContext.class);
        when(context.getDataDirectory()).thenReturn(data);
        when(context.getLogger()).thenReturn(mock(ComponentLogger.class));
        doNothing().when(bootstrap).channelInstaller(eq(context), any(), any());
        Path record = data.resolve("bootstrap/contributions.json");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "{not json");
        bootstrap.bootstrap(context);
        try (var loader = bootstrap.result().classLoader()) {
            assertThat(bootstrap.result().classPathFailure()).isNull();
            assertThat(loader.getURLs()).contains(jar.toUri().toURL());
            Files.delete(record); // Neither a missing record nor a change on disk may affect a query.
            var ledger = ModuleContentLedgers.current();
            assertThat(ledger.contentOf(null, "Boot2Content").kinds()).containsExactly(
                    new ModuleContentKind("enchantment", 1), new ModuleContentKind("datapack", 1),
                    new ModuleContentKind("tag", 2));
            assertThat(ledger.contentOf("boot2content", "NotTheName")).isEqualTo(ledger.contentOf("other", "Boot2Content"));
            assertThat(ledger.contentOf(null, "NoSuccessfulHook")).isEqualTo(ModuleContent.unknown());
        }
    }

    @Test
    void retainedContentIsCappedInCanonicalOrderWithoutDiscardingTheModule() throws Exception {
        Path data = Files.createDirectories(folder.resolve("plugins/UltiTools"));
        var entries = new ArrayList<>(IntStream.range(0, 20).mapToObj(i ->
                new ContentEntry(String.format(Locale.ROOT, "future_%02d", 19 - i), "demo:future")).toList());
        entries.addAll(List.of(new ContentEntry("enchantment", "demo:e"), new ContentEntry("datapack", "UltiTools/demo.p"),
                new ContentEntry("tag/minecraft:item", "demo:tag"), new ContentEntry("tag/minecraft:item", "demo:tag"),
                new ContentEntry("unknown", "marker"), new ContentEntry("invalid-kind", "demo:bad")));
        Logger logger = mock(Logger.class);
        ContributionLedger.update(data, new ContributionLedger.Start(Map.of("Demo", entries), Set.of("Demo"), Set.of()),
                Instant.now(), logger, ContributionLedger::writeBytes);
        var bootstrap = new UltiToolsBootstrap();
        var context = mock(BootstrapContext.class);
        ComponentLogger early = mock(ComponentLogger.class);
        when(context.getDataDirectory()).thenReturn(data);
        when(context.getLogger()).thenReturn(early);
        bootstrap.bootstrap(context);
        try (var loader = bootstrap.result().classLoader()) {
            assertThat(loader.getURLs()).isNotEmpty();
            assertThat(ModuleContentLedgers.current().contentOf(null, "Demo").kinds())
                    .extracting(ModuleContentKind::kind).containsExactly("enchantment", "datapack", "tag", "unknown",
                            "future_00", "future_01", "future_02", "future_03", "future_04", "future_05",
                            "future_06", "future_07", "future_08", "future_09", "future_10", "future_11");
            assertThat(ModuleContentLedgers.contentOf(null, "Demo").find("tag")).isEqualTo(new ModuleContentKind("tag", 1));
            assertThat(ModuleContentLedgers.contentOf(null, "Missing")).isSameAs(ModuleContent.NONE);
            verify(early).warn(contains("Module 'Demo' declared 24 custom content kinds"), eq((Throwable) null));
        }
    }
}
