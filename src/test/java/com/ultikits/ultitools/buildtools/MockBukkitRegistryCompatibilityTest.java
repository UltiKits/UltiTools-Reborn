package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.registry.RegistryKey;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.registry.RegistryAccessMock;

/**
 * Covers MockBukkit 4.123.0's missing WorldClock/Timeline class mappings on Paper 26.3.
 * The test-only resource retains its other mappings; remove it when upstream ships both entries.
 */
class MockBukkitRegistryCompatibilityTest {
    @Test
    void registryClassMappingCoversEveryPaperKey() throws Exception {
        try (InputStream input = RegistryAccessMock.class.getResourceAsStream(
                "/registries/registry_key_class_relation.json")) {
            assertThat(input).isNotNull();
            JsonObject mapping = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Field field : RegistryKey.class.getFields()) {
                if (field.getType() != RegistryKey.class) {
                    continue;
                }
                RegistryKey<?> key = (RegistryKey<?>) field.get(null);
                Type valueType = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                if (valueType instanceof ParameterizedType) {
                    valueType = ((ParameterizedType) valueType).getRawType();
                }
                String name = key.key().asString();
                assertThat(mapping.has(name)).as("mapping for Paper registry %s", name).isTrue();
                assertThat(mapping.get(name).getAsString()).isEqualTo(((Class<?>) valueType).getName());
            }
        }
    }

    @Test
    void mockBukkitStartsWithThePinnedPaperRegistrySet() {
        ServerMock server = MockBukkit.mock();
        try {
            assertThat(Bukkit.getServer()).isSameAs(server);
            assertThat(server.getRegistry(org.bukkit.Fluid.class)).isNotNull();
        } finally {
            MockBukkit.unmock();
        }
    }
}
