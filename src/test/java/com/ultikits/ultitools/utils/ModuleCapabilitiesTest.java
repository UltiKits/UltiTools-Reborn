package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-24 and contract answer 11: {@code hello} declares one {@code modules:<id>@<X.Y.Z>} name per
 * module owning at least one responder, with the id derived from the module name and the version
 * from the module's own plugin version supplied through the function port.
 */
class ModuleCapabilitiesTest {
    private final PanelResponderRegistry registry = new PanelResponderRegistry();

    @AfterEach
    void shutdownRegistry() {
        registry.shutdown();
    }

    @Test
    void oneNamePerAnsweringModuleSortedById() {
        register("UltiMail", "ultimail.inbox.list");
        register("UltiKits", "ultikits.kits.list");
        Map<String, String> versions = new HashMap<>();
        versions.put("UltiMail", "1.2.0-beta");
        versions.put("UltiKits", "2");

        assertThat(ModuleCapabilities.names(registry, versions::get))
                .containsExactly("modules:ultikits@2.0.0", "modules:ultimail@1.2.0");
    }

    @Test
    void versionWithoutDigitsFallsBackToZero() {
        register("UltiChat", "ultichat.message.send");

        assertThat(ModuleCapabilities.names(registry, owner -> "beta"))
                .containsExactly("modules:ultichat@0.0.0");
        assertThat(ModuleCapabilities.names(registry, owner -> null))
                .containsExactly("modules:ultichat@0.0.0");
    }

    @Test
    void moduleWithoutRespondersContributesNothing() {
        register("UltiMail", "ultimail.inbox.list");
        Map<String, String> versions = new HashMap<>();
        versions.put("UltiMail", "1.0.0");
        versions.put("UltiChat", "1.0.0");

        assertThat(ModuleCapabilities.names(registry, versions::get))
                .containsExactly("modules:ultimail@1.0.0");
    }

    @Test
    void nameDisappearsWhenTheModuleUnloads() {
        register("UltiMail", "ultimail.inbox.list");
        assertThat(ModuleCapabilities.names(registry, owner -> "1.0.0")).hasSize(1);

        registry.unregisterAll("UltiMail");

        assertThat(ModuleCapabilities.names(registry, owner -> "1.0.0")).isEmpty();
    }

    @Test
    void ownerNameThatCannotFormAnIdentifierIsSkippedWithOneWarning() {
        register("Ulti Mail", "ultimail.inbox.list");
        register("UltiMenu", "ultimenu.open");

        try (WarningCapture capture = new WarningCapture()) {
            List<String> names = ModuleCapabilities.names(registry, owner -> "1.0.0");

            assertThat(names).containsExactly("modules:ultimenu@1.0.0");
            assertThat(onlyMentions(capture.records(), "Ulti Mail")).hasSize(1);
        }
    }

    @Test
    void everyNameSatisfiesTheContractCapabilityShape() {
        register("UltiMail", "ultimail.inbox.list");
        register("UltiKits", "ultikits.kits.list");

        for (String name : ModuleCapabilities.names(registry, owner -> "6.3.1-SNAPSHOT")) {
            assertThat(ContractPrimitives.CAPABILITY.matcher(name).matches()).as(name).isTrue();
            assertThat(ContractPrimitives.isModuleCapability(name)).as(name).isTrue();
        }
    }

    @Test
    void atMostSixtyFourNamesAreDeclared() {
        for (int index = 0; index < 66; index++) {
            String owner = String.format(java.util.Locale.ROOT, "Ulti%02d", index);
            register(owner, "ulti" + index + ".action");
        }

        try (WarningCapture capture = new WarningCapture()) {
            List<String> names = ModuleCapabilities.names(registry, owner -> "1.0.0");

            assertThat(names).hasSize(64).isSorted();
            assertThat(names.get(0)).isEqualTo("modules:ulti00@1.0.0");
            assertThat(capture.records()).hasSize(1);
            assertThat(capture.records().get(0).getMessage()).contains("64");
        }
    }

    /**
     * The contract's semver core forbids leading zeros, so {@code 01.002.3-b} must be declared as
     * {@code 1.2.3}; keeping the zeros would make the whole name contract-invalid and the module
     * would silently vanish from {@code hello}.
     */
    @Test
    void leadingZerosInTheVersionAreDropped() {
        register("UltiMail", "ultimail.inbox.list");

        assertThat(ModuleCapabilities.names(registry, owner -> "01.002.3-b"))
                .containsExactly("modules:ultimail@1.2.3");
    }

    /**
     * Two module names that lower-case to the same id can only be declared once; the contract has no
     * way to name two versions of one id, so the first owner in sorted order keeps its version.
     */
    @Test
    void duplicateIdsKeepTheFirstOwnerInSortedOrder() {
        register("UltiMail", "ultimail.inbox.list");
        register("ultimail", "ultimail.outbox.list");
        Map<String, String> versions = new HashMap<>();
        versions.put("UltiMail", "1.2.3");
        versions.put("ultimail", "9.9.9");

        assertThat(ModuleCapabilities.names(registry, versions::get))
                .containsExactly("modules:ultimail@1.2.3");
    }

    private void register(String owner, String messageType) {
        registry.registerResponder(messageType, data -> CompletableFuture.completedFuture(new JsonObject()), owner);
    }

    private static List<LogRecord> onlyMentions(List<LogRecord> records, String text) {
        List<LogRecord> matching = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getMessage() != null && record.getMessage().contains(text)) {
                matching.add(record);
            }
        }
        return matching;
    }

    /** Captures this class's WARNING records for one test and detaches itself again. */
    private static final class WarningCapture implements AutoCloseable {
        private final Logger logger = Logger.getLogger(ModuleCapabilities.class.getName());
        private final List<LogRecord> records = new ArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
                // Nothing is buffered.
            }

            @Override
            public void close() {
                // Nothing is held.
            }
        };

        private WarningCapture() {
            logger.addHandler(handler);
        }

        private List<LogRecord> records() {
            return records;
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }
}
