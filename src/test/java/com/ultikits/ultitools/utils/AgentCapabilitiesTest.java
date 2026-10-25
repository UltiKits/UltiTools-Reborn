package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ultikits.ultitools.entities.Capability;

/**
 * The hello capability list and the per-request switch check of plan 22-21 (S-65, S-66): the operator's switches
 * decide what is declared, {@code power} and {@code link} are always declared, {@code backups} never, module names
 * are appended sorted, and the whole list never exceeds the contract's 64 names.
 */
@DisplayName("AgentCapabilities: hello list from the operator's switches, per-request re-check")
class AgentCapabilitiesTest {

    private final Set<Capability> on = EnumSet.noneOf(Capability.class);
    private final AtomicBoolean errors = new AtomicBoolean(true);
    private final List<String> modules = new ArrayList<>();
    private final AgentCapabilities capabilities = new AgentCapabilities(on::contains, errors::get, () -> modules);

    private void shippedDefaults() {
        on.clear();
        for (Capability capability : Capability.values()) {
            if (capability != Capability.NONE && capability.getDefaultEnabled()) {
                on.add(capability);
            }
        }
    }

    @Test
    @DisplayName("the shipped defaults declare exactly the nine default names")
    void shippedDefaultsDeclareTheDefaultNames() {
        shippedDefaults();
        assertThat(capabilities.helloCapabilities()).containsExactly("telemetry", "logs", "events:players",
                "events:commands", "errors", "files:read", "config:read", "power", "link");
        assertThat(capabilities.helloCapabilities()).doesNotContain("commands", "players:actions", "files:write",
                "files:delete", "config:write", "properties:read", "properties:write", "backups");
    }

    static Stream<Arguments> switches() {
        Map<Capability, List<String>> table = new LinkedHashMap<>();
        table.put(Capability.MONITORING, Collections.singletonList("telemetry"));
        table.put(Capability.LOGS, Collections.singletonList("logs"));
        table.put(Capability.PLAYER_EVENTS, Arrays.asList("events:players", "events:commands"));
        table.put(Capability.COMMANDS, Collections.singletonList("commands"));
        table.put(Capability.PLAYER_ACTIONS, Collections.singletonList("players:actions"));
        table.put(Capability.FILE_READ, Arrays.asList("files:read", "config:read"));
        table.put(Capability.FILE_WRITE, Arrays.asList("files:write", "config:write"));
        table.put(Capability.FILE_DELETE, Collections.singletonList("files:delete"));
        table.put(Capability.SERVER_PROPERTIES, Arrays.asList("properties:read", "properties:write"));
        List<Arguments> out = new ArrayList<>();
        for (Map.Entry<Capability, List<String>> row : table.entrySet()) {
            out.add(Arguments.of(row.getKey(), row.getValue()));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} adds {1}")
    @MethodSource("switches")
    @DisplayName("each switch adds exactly its contract names")
    void eachSwitchAddsExactlyItsNames(Capability capability, List<String> names) {
        errors.set(false);
        List<String> before = capabilities.helloCapabilities();
        assertThat(before).containsExactly("power", "link");
        on.add(capability);
        List<String> after = new ArrayList<>(capabilities.helloCapabilities());
        after.removeAll(before);
        assertThat(after).containsExactlyElementsOf(names);
        for (String name : names) {
            assertThat(capabilities.enabled(name)).isTrue();
        }
        on.remove(capability);
        for (String name : names) {
            assertThat(capabilities.enabled(name)).isFalse();
            assertThat(capabilities.refusal(name)).contains(capability.getConfigPath());
        }
    }

    @Test
    @DisplayName("error reporting declares errors from its own configuration key")
    void errorReportingDeclaresErrors() {
        errors.set(false);
        assertThat(capabilities.helloCapabilities()).doesNotContain("errors");
        assertThat(capabilities.enabled("errors")).isFalse();
        assertThat(capabilities.refusal("errors")).contains(AgentCapabilities.ERROR_REPORTING_KEY);
        errors.set(true);
        assertThat(capabilities.helloCapabilities()).contains("errors");
        assertThat(capabilities.enabled("errors")).isTrue();
    }

    @Test
    @DisplayName("power, link, modules and the module sentinel are always allowed; backups never")
    void alwaysAndNever() {
        assertThat(capabilities.enabled("power")).isTrue();
        assertThat(capabilities.enabled("link")).isTrue();
        assertThat(capabilities.enabled(AgentProtocol.MODULE_CAPABILITY_SENTINEL)).isTrue();
        assertThat(capabilities.enabled("modules:ultimail@1.2.0")).isTrue();
        assertThat(capabilities.enabled("backups")).isFalse();
        assertThat(capabilities.enabled("unknown")).isFalse();
        assertThat(capabilities.helloCapabilities()).doesNotContain("backups");
    }

    @Test
    @DisplayName("module names are appended sorted and the list stops at 64")
    void modulesSortedAndCapped() {
        shippedDefaults();
        modules.add("modules:zeta@1.0.0");
        modules.add("modules:alpha@2.0.0");
        List<String> names = capabilities.helloCapabilities();
        assertThat(names.subList(names.size() - 2, names.size()))
                .containsExactly("modules:alpha@2.0.0", "modules:zeta@1.0.0");
        modules.clear();
        for (int i = 0; i < 80; i++) {
            modules.add(String.format("modules:m%02d@1.0.0", i));
        }
        names = capabilities.helloCapabilities();
        assertThat(names).hasSize(64);
        assertThat(names).contains("power", "link", "modules:m00@1.0.0").doesNotContain("modules:m79@1.0.0");
    }
}
