package com.ultikits.ultitools.utils;

import java.util.Locale;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * The one derivation of a module's UltiCloud identifier; internal, not for module use.
 *
 * <p>UltiCloud addresses a module by its catalogue {@code identifier}: the {@code <id>} a {@code
 * hello} declares in {@code modules:<id>@X.Y.Z}, the {@code module} of a {@code module.request}, the
 * {@code moduleName} of an {@code error.report} and the {@code :moduleRef} of every catalogue route
 * must all be that identifier, lower-case, in the contract's {@code ModuleIdentifier} syntax (a
 * lower-case letter first, segments of lower-case letters and digits joined by a single {@code .} or
 * {@code -}, at most 64 characters). The framework derives it from the module's {@code
 * identify-string}, never from its {@code plugin.yml} name: the two differ for some modules (for
 * example name {@code UltiTools-Chat}, identify-string {@code ultichat}), and the name is free text the
 * server does not match.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class ModuleIds {

    /** The contract's bound on a module identifier. */
    public static final int MAX_LENGTH = 64;

    private ModuleIds() {
    }

    /**
     * A module's identifier from its identify-string: trimmed and lower-cased; {@code null} when the
     * result is empty, longer than {@link #MAX_LENGTH} or not in the contract's identifier syntax.
     *
     * @param identifyString an identify-string, possibly {@code null}
     * @return the identifier, or {@code null} when it cannot be one
     */
    public static String of(String identifyString) {
        if (identifyString == null) {
            return null;
        }
        String normalized = identifyString.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH
                || !ContractPrimitives.MODULE_IDENTIFIER.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    /**
     * A loaded module's identifier, from its identify-string.
     *
     * @param module the module, possibly {@code null}
     * @return the identifier, or {@code null} when there is no module or its identify-string cannot be one
     */
    public static String of(UltiToolsPlugin module) {
        return module == null ? null : of(module.getIdentifyString());
    }
}
