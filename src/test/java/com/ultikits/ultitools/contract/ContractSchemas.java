package com.ultikits.ultitools.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.JDKRegularExpressionFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

public final class ContractSchemas {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(false)
                            .regularExpressionFactory(JDKRegularExpressionFactory.getInstance()).build()));
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private ContractSchemas() { }

    public static void assertFrameValid(JsonObject frame) {
        assertValid("schemas/agent/" + frame.get("type").getAsString() + ".schema.json", frame);
    }

    public static void assertRestBodyValid(String schemaFile, JsonObject body) {
        assertValid(restPath(schemaFile), body);
    }

    public static boolean isValid(String schemaFile, JsonObject body) {
        return schema(schemaFile).validate(node(body.toString())).isEmpty();
    }

    public static boolean isValid(JsonObject frame) {
        return isValid("schemas/agent/" + frame.get("type").getAsString() + ".schema.json", frame);
    }

    private static String restPath(String file) {
        return file.startsWith("schemas/") ? file : "schemas/agent-rest/" + file;
    }

    private static void assertValid(String file, JsonObject body) {
        assertThat(schema(file).validate(node(body.toString()))).as(file).isEmpty();
    }

    private static Schema schema(String path) {
        return SCHEMAS.computeIfAbsent(path, key -> REGISTRY.getSchema(node(ContractFixtures.text(key))));
    }

    private static JsonNode node(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid contract JSON", ex);
        }
    }
}
