package com.ultikits.ultitools.bootstrap;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.internal.FrameworkMessages;

/** Early English diagnostics and enable-time reports rendered with the server language. */
final class BootstrapContentReport {
    private BootstrapContentReport() {
        // Reports are derived from the handover and do not own live module state.
    }

    static void declared(ContributionRegistry registry, Logger logger) {
        for (var module : registry.modules()) {
            var entries = module.ledgerEntries();
            if (!entries.isEmpty() && !registry.failed(module.jar())) {
                var count = ContentCounts.of(entries);
                String message = String.format(Locale.ROOT, "[UltiTools] Module '%s' declared custom content: "
                        + "%d enchantment(s), %d tag change(s), %d data pack(s).", module.jar().getFileName(),
                        count.enchantments(), count.tags(), count.datapacks());
                ModuleBootstraps.log(logger, Level.INFO, message, null);
            }
        }
    }

    static void enabled(ContributionRegistry registry, ContributionLedger ledger, Logger logger) {
        if (ledger.unreadable()) {
            logger.warning(FrameworkMessages.plain(BootstrapContentKeys.UNKNOWN));
        }
        for (var module : registry.modules()) {
            var entries = module.ledgerEntries();
            if (!entries.isEmpty() && !registry.failed(module.jar())) {
                var count = ContentCounts.of(entries);
                logger.info(FrameworkMessages.plain(BootstrapContentKeys.SUMMARY, Arg.string("module", module.name()),
                        Arg.number("enchantments", count.enchantments()), Arg.number("tags", count.tags()),
                        Arg.number("datapacks", count.datapacks())));
            }
        }
        ledger.removed().forEach((module, entries) -> warn(logger, BootstrapContentKeys.REMOVED, module, entries));
        ledger.dropped().forEach((module, entries) -> warn(logger, BootstrapContentKeys.DROPPED, module, entries));
    }

    private static void warn(Logger logger, MessageKey key, String module, List<ContentEntry> entries) {
        var count = ContentCounts.of(entries);
        String list = FrameworkMessages.plain(BootstrapContentKeys.LIST, count.args());
        logger.warning(FrameworkMessages.plain(key, Arg.string("module", module), Arg.string("content", list)));
        if (count.enchantments() > 0) {
            logger.warning(FrameworkMessages.plain(BootstrapContentKeys.CONSEQUENCE_ENCHANTMENT));
        }
        if (count.datapacks() > 0) {
            logger.warning(FrameworkMessages.plain(BootstrapContentKeys.CONSEQUENCE_DATAPACK));
        }
        if (count.tags() > 0) {
            logger.info(FrameworkMessages.plain(BootstrapContentKeys.CONSEQUENCE_TAG));
        }
    }
}
