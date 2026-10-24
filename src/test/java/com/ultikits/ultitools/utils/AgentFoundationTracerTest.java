package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.websocket.CloudLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFoundationTracerTest {
    @Test
    void commandFixtureTravelsThroughRouterGateAndFraming() {
        String text = ContractFixtures.text("fixtures/agent/command.execute/valid/say.json");
        JsonObject request = JsonParser.parseString(text).getAsJsonObject();
        List<JsonObject> sent = new ArrayList<>();
        FramedChannel channel = new FramedChannel(frame -> {
            JsonObject json = JsonParser.parseString(frame).getAsJsonObject();
            ContractSchemas.assertFrameValid(json);
            sent.add(json);
            return true;
        }, () -> true, CloudLimits::defaults, Ulid::next, () -> 1000L);
        DedupeLedger ledger = mock(DedupeLedger.class);
        when(ledger.firstSeen(request.get("id").getAsString())).thenReturn(true);
        List<String> execution = new ArrayList<>();
        AgentMessageRouter router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("command.execute", (frame, output) -> AckFirstGate.handle(frame, output, ledger, () -> {
            assertThat(sent).hasSize(1);
            execution.add(frame.id());
        }));
        router.dispatch(text, channel);
        assertThat(sent).hasSize(1);
        JsonObject ack = sent.get(0);
        assertThat(ack.get("type").getAsString()).isEqualTo("command.ack");
        assertThat(ack.get("replyTo")).isEqualTo(request.get("id"));
        assertThat(ack.getAsJsonObject("payload").size()).isZero();
        assertThat(ack.get("id")).isNotEqualTo(request.get("id"));
        assertThat(ack.get("ts").getAsLong()).isEqualTo(1000L);
        assertThat(execution).containsExactly(request.get("id").getAsString());
    }

    @Test
    void registryMatchesEveryFrozenAgentSchemaAndMetadata() {
        Set<String> schemaTypes = ContractFixtures.paths().stream()
                .filter(path -> path.startsWith("schemas/agent/"))
                .map(path -> path.substring("schemas/agent/".length(), path.length() - ".schema.json".length()))
                .collect(Collectors.toSet());
        assertThat(AgentMessageTypes.types()).hasSize(60).containsExactlyInAnyOrderElementsOf(schemaTypes);
        int rows = 0;
        for (String line : ContractFixtures.text("PROTOCOL.md").split("\n")) {
            if (!line.contains("[schema](schemas/agent/")) {
                continue;
            }
            String[] cells = line.split("\\|", -1);
            String type = cells[1].trim().replace("`", "");
            AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
            assertThat(spec.direction().name()).isEqualToIgnoringCase(cells[2].trim());
            assertThat(spec.replyTo().name()).isEqualToIgnoringCase(cells[4].trim());
            assertThat(spec.ackFirst()).isEqualTo(!cells[6].trim().isEmpty());
            assertThat(spec.reliable()).isEqualTo(!cells[7].trim().isEmpty());
            String capability = cells[8].trim().replace("`", "");
            assertThat(spec.capability()).isEqualTo("module.request".equals(type) ? "fromPayload:module"
                    : "—".equals(capability) ? null : capability);
            rows++;
        }
        assertThat(rows).isEqualTo(60);
    }
}
