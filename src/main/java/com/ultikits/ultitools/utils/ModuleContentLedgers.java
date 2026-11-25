package com.ultikits.ultitools.utils;

import java.util.Objects;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the ledger that answers custom content queries.
 *
 * @since 7.0.0
 */
@ApiStatus.Internal
public final class ModuleContentLedgers {

    private static volatile ModuleContentLedger ledger = ModuleContentLedger.NONE;

    private ModuleContentLedgers() {
        // Static query and registration entry points only.
    }

    /**
     * Returns the installed ledger, {@link ModuleContentLedger#NONE} until one is installed.
     *
     * @return the installed ledger
     */
    public static ModuleContentLedger current() {
        return ledger;
    }

    /**
     * Installs the ledger; the bootstrap content channels call this once per start.
     * Installing {@link ModuleContentLedger#NONE} restores the default (tests use this to clean up).
     *
     * @param ledger the ledger to install
     * @throws NullPointerException if {@code ledger} is {@code null}
     */
    public static void install(ModuleContentLedger ledger) {
        ModuleContentLedgers.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * Asks the installed ledger, never failing: a ledger that throws a runtime exception or returns
     * {@code null} is answered with {@link ModuleContent#unknown()} so that a removal still warns.
     *
     * @param identifyString the module's normalised identify string, or {@code null} when unknown
     * @param moduleName the {@code name} from the module's {@code plugin.yml}; the primary lookup key
     * @return the content, or unknown content if the ledger fails
     */
    public static ModuleContent contentOf(@Nullable String identifyString, String moduleName) {
        try {
            var content = ledger.contentOf(identifyString, moduleName);
            return content == null ? ModuleContent.unknown() : content;
        } catch (RuntimeException ignored) {
            return ModuleContent.unknown();
        }
    }
}
