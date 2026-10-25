package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code modules:<id>@<X.Y.Z>} capability names an UltiCloud protocol v2 {@code hello} declares
 * for the modules that answer {@code module.request}; internal, not for module use.
 *
 * <p>One name is produced per module owning at least one responder, and none for a module that owns
 * none — the gateway sends a module's request only to a plugin that declared that module's
 * capability (D-24, contract answer 11). The id is the owner module name lower-cased, taken only
 * when it forms a valid contract module identifier (lowercase letters, digits, dot and hyphen
 * inside, at most 64 characters); a name that cannot is skipped with one WARNING rather than
 * mangled into something the gateway would route wrongly.</p>
 *
 * <p>The version is supplied by the caller from the module's own {@code plugin.yml} version and cut
 * to its first three numeric parts, so {@code 1.2.0-beta} becomes {@code 1.2.0}, {@code 2} becomes
 * {@code 2.0.0}, and a version with no digits at all becomes {@code 0.0.0}. The list is sorted by id
 * so repeated calls and repeated connections declare exactly the same names in the same order.</p>
 *
 * <p>The contract caps a {@code hello} at 64 capability names in total, which the framework's own
 * capabilities also draw from. This class never returns more than 64; the caller that adds the
 * framework's own capabilities keeps the combined list within the cap.</p>
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
final class ModuleCapabilities {

    /** The contract's {@code capabilitiesMaxItems}; the framework's own names share this cap. */
    static final int MAX_NAMES = 64;
    /** The contract's bound on a module identifier. */
    private static final int MODULE_IDENTIFIER_MAX_LENGTH = 64;
    private static final String NAME_PREFIX = "modules:";
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private static final Logger LOGGER = Logger.getLogger(ModuleCapabilities.class.getName());

    private ModuleCapabilities() {
    }

    /**
     * The capability names to declare for every module that currently owns a responder.
     *
     * @param registry            the registry holding module responders
     * @param moduleVersionByName the module's own plugin version by owner module name, as recorded
     *                            in its {@code plugin.yml}; a {@code null} result is treated as a
     *                            version with no digits
     * @return an unmodifiable list sorted by module id, at most {@link #MAX_NAMES} long; empty when
     *         no module owns a responder
     */
    static List<String> names(PanelResponderRegistry registry, Function<String, String> moduleVersionByName) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(moduleVersionByName, "moduleVersionByName");
        Map<String, String> namesById = new TreeMap<>();
        for (String owner : registry.ownerModules()) {
            String id = identifier(owner);
            if (id == null) {
                LOGGER.warning("UltiCloud: module '" + owner + "' cannot form a protocol module identifier;"
                        + " its responders are not declared to the panel.");
                continue;
            }
            String name = NAME_PREFIX + id + "@" + version(moduleVersionByName.apply(owner));
            if (!ContractPrimitives.CAPABILITY.matcher(name).matches()
                    || !ContractPrimitives.isModuleCapability(name)) {
                LOGGER.warning("UltiCloud: capability name for module '" + owner + "' is not contract-valid"
                        + " and was not declared.");
                continue;
            }
            if (!namesById.containsKey(id)) {
                namesById.put(id, name);
            }
        }
        List<String> names = new ArrayList<>();
        for (String name : namesById.values()) {
            if (names.size() >= MAX_NAMES) {
                LOGGER.warning("UltiCloud: only the first " + MAX_NAMES + " module capability names are declared;"
                        + " the protocol accepts no more in one hello.");
                break;
            }
            names.add(name);
        }
        return Collections.unmodifiableList(names);
    }

    /** The owner module name as a contract module identifier, or {@code null} when it cannot be one. */
    private static String identifier(String owner) {
        if (owner == null) {
            return null;
        }
        String id = owner.toLowerCase(Locale.ROOT);
        if (id.length() > MODULE_IDENTIFIER_MAX_LENGTH
                || !ContractPrimitives.MODULE_IDENTIFIER.matcher(id).matches()) {
            return null;
        }
        return id;
    }

    /**
     * The first three numeric parts of a plugin version, padded with zeros to a full
     * {@code X.Y.Z}. Leading zeros inside a part are dropped, so {@code 01.2} is {@code 1.2.0}: the
     * contract's semver core forbids them.
     */
    static String version(String pluginVersion) {
        String[] parts = new String[3];
        if (pluginVersion != null) {
            Matcher matcher = DIGITS.matcher(pluginVersion);
            int count = 0;
            while (count < parts.length && matcher.find()) {
                parts[count] = withoutLeadingZeros(matcher.group());
                count++;
            }
        }
        StringBuilder version = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                version.append('.');
            }
            version.append(parts[index] == null ? "0" : parts[index]);
        }
        return version.toString();
    }

    private static String withoutLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }
}
