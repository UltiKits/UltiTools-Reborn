package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.ultikits.ultitools.entities.Capability;

/**
 * The operator's switches as UltiCloud protocol capabilities (plan 22-21, S-65, S-66).
 * <p>
 * <b>Mapping.</b> {@code MONITORING} → {@code telemetry}; {@code LOGS} → {@code logs}; {@code PLAYER_EVENTS} →
 * {@code events:players}, {@code events:commands}; {@code ultipanel.logging.error-reporting.enabled} →
 * {@code errors}; {@code COMMANDS} → {@code commands}; {@code PLAYER_ACTIONS} → {@code players:actions};
 * {@code FILE_READ} → {@code files:read}, {@code config:read}; {@code FILE_WRITE} → {@code files:write},
 * {@code config:write}; {@code FILE_DELETE} → {@code files:delete}; {@code SERVER_PROPERTIES} →
 * {@code properties:read}, {@code properties:write}. {@code power} is always declared, so the panel can show "turned
 * off by the operator" from {@code settings.powerActionsEnabled} while the handler refuses with
 * {@code power_disabled}; {@code link} is always declared, because a player link carries no action that is dangerous
 * to the server. {@code backups} is never declared. Module names {@code modules:<id>@<X.Y.Z>} follow, sorted, and the
 * whole list never exceeds {@link #MAX_NAMES}.
 * <p>
 * <b>Per request (S-66).</b> The hello list changes only on the next connection, so {@link #enabled(String)} reads the
 * switch again for every request; a switch turned off after the handshake refuses the request with
 * {@code capability_not_accepted} and {@link #refusal(String)} names its configuration key. Whether the gateway
 * accepted the capability at the handshake is checked separately, by the router's gate in {@link AgentProtocol}.
 * <p>
 * <b>Availability (22-22 fix).</b> A switched-on name whose feature cannot run on this server (the {@code telemetry}
 * of a server without a monitor) is neither declared nor admitted per request; its refusal says it is not available
 * instead of naming a switch that is already on. The hello and the router therefore read one predicate.
 */
final class AgentCapabilities {

    /** The configuration key of the error-report switch that decides {@code errors}. */
    static final String ERROR_REPORTING_KEY = "ultipanel.logging.error-reporting.enabled";
    /** The contract's bound on the names of one hello. */
    static final int MAX_NAMES = AgentProtocol.MAX_CAPABILITIES;

    private static final String ERRORS = "errors";
    private static final String POWER = "power";
    private static final String LINK = "link";
    private static final String MODULE_PREFIX = "modules:";
    private static final String UNAVAILABLE = "This capability is not available on this server.";

    /** Contract name to the switch that decides it, in hello order; {@code null} is the error-report key. */
    private static final Map<String, Capability> SWITCHES = new LinkedHashMap<>();

    static {
        SWITCHES.put("telemetry", Capability.MONITORING);
        SWITCHES.put("logs", Capability.LOGS);
        SWITCHES.put("events:players", Capability.PLAYER_EVENTS);
        SWITCHES.put("events:commands", Capability.PLAYER_EVENTS);
        SWITCHES.put(ERRORS, null);
        SWITCHES.put("commands", Capability.COMMANDS);
        SWITCHES.put("players:actions", Capability.PLAYER_ACTIONS);
        SWITCHES.put("files:read", Capability.FILE_READ);
        SWITCHES.put("config:read", Capability.FILE_READ);
        SWITCHES.put("files:write", Capability.FILE_WRITE);
        SWITCHES.put("config:write", Capability.FILE_WRITE);
        SWITCHES.put("files:delete", Capability.FILE_DELETE);
        SWITCHES.put("properties:read", Capability.SERVER_PROPERTIES);
        SWITCHES.put("properties:write", Capability.SERVER_PROPERTIES);
    }

    private final Predicate<Capability> switchOn;
    private final BooleanSupplier errorReporting;
    private final Supplier<List<String>> moduleNames;
    private final Predicate<String> available;

    /**
     * @param switchOn       whether an operator switch is on now (production: {@link Capability#isEnabled()})
     * @param errorReporting whether {@value #ERROR_REPORTING_KEY} is on now
     * @param moduleNames    the {@code modules:<id>@<X.Y.Z>} names of the modules that answer requests
     */
    AgentCapabilities(Predicate<Capability> switchOn, BooleanSupplier errorReporting,
                      Supplier<List<String>> moduleNames) {
        this(switchOn, errorReporting, moduleNames, name -> true);
    }

    /**
     * @param switchOn       whether an operator switch is on now
     * @param errorReporting whether {@value #ERROR_REPORTING_KEY} is on now
     * @param moduleNames    the {@code modules:<id>@<X.Y.Z>} names of the modules that answer requests
     * @param available      whether the feature behind a switch-mapped contract name can run on this server now
     */
    AgentCapabilities(Predicate<Capability> switchOn, BooleanSupplier errorReporting,
                      Supplier<List<String>> moduleNames, Predicate<String> available) {
        this.switchOn = Objects.requireNonNull(switchOn, "switchOn");
        this.errorReporting = Objects.requireNonNull(errorReporting, "errorReporting");
        this.moduleNames = Objects.requireNonNull(moduleNames, "moduleNames");
        this.available = Objects.requireNonNull(available, "available");
    }

    /**
     * @return the names the next hello declares, framework names first in the order of the mapping, then module names
     *         sorted; at most {@link #MAX_NAMES}
     */
    List<String> helloCapabilities() {
        List<String> names = new ArrayList<>();
        for (String name : SWITCHES.keySet()) {
            if (enabled(name)) {
                names.add(name);
            }
        }
        names.add(POWER);
        names.add(LINK);
        List<String> modules = moduleNames.get();
        if (modules != null) {
            for (String module : new TreeSet<>(modules)) {
                if (names.size() >= MAX_NAMES) {
                    break;
                }
                names.add(module);
            }
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * @param contractCapability a contract capability name
     * @return the operator switch that decides it, or {@code null} when no {@link Capability} switch does (the
     *         error-report key, {@code power}, {@code link}, module names, an unknown name)
     */
    static Capability switchFor(String contractCapability) {
        return contractCapability == null ? null : SWITCHES.get(contractCapability);
    }

    /**
     * @param contractCapability a contract capability name, or the module sentinel
     * @return whether the operator allows it now: its switch for a mapped name; always for {@code power} (its handler
     *         applies the switch), {@code link}, module names and the module sentinel; never for {@code backups} or an
     *         unknown name
     */
    boolean enabled(String contractCapability) {
        if (contractCapability == null) {
            return false;
        }
        if (POWER.equals(contractCapability) || LINK.equals(contractCapability)
                || AgentProtocol.MODULE_CAPABILITY_SENTINEL.equals(contractCapability)
                || contractCapability.startsWith(MODULE_PREFIX)) {
            return true;
        }
        if (!SWITCHES.containsKey(contractCapability) || !available.test(contractCapability)) {
            return false;
        }
        Capability capability = SWITCHES.get(contractCapability);
        return capability == null ? errorReporting.getAsBoolean() : switchOn.test(capability);
    }

    /**
     * @param contractCapability a refused contract capability name
     * @return the refusal sentence naming the switch's configuration key, or a fixed sentence for a name no switch
     *         decides
     */
    String refusal(String contractCapability) {
        if (contractCapability == null || !SWITCHES.containsKey(contractCapability)
                || !available.test(contractCapability)) {
            return UNAVAILABLE;
        }
        Capability capability = SWITCHES.get(contractCapability);
        return capability == null ? Capability.configurableRefusal(ERROR_REPORTING_KEY) : capability.refusalMessage();
    }
}
