package com.ultikits.ultitools.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ContractFixtures {
    public static final String ROOT = "ulticloud-contract/v2/2.0.0";

    private ContractFixtures() { }

    public static String text(String relativePath) {
        return resource(ROOT + "/" + relativePath);
    }

    public static String resource(String path) {
        try (InputStream input = ContractFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing contract resource: " + path);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read contract resource: " + path, ex);
        }
    }

    public static JsonObject object(String relativePath) {
        return JsonParser.parseString(text(relativePath)).getAsJsonObject();
    }

    public static List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : object("MANIFEST.json").getAsJsonObject("files").entrySet()) {
            paths.add(entry.getKey());
        }
        return paths;
    }

    public static List<Fixture> validFrames(String type) {
        return fixtures("fixtures/agent/" + type + "/valid/", true, "frame");
    }

    public static List<Fixture> invalidFrames(String type) {
        return fixtures("fixtures/agent/" + type + "/invalid/", false, "frame");
    }

    public static List<Fixture> restBodies(String operation, String part, boolean valid) {
        return fixtures("fixtures/agent-rest/" + operation + "/" + part + "/"
                + (valid ? "valid/" : "invalid/"), valid, "body");
    }

    public static JsonArray legacyUuidCases() {
        return object("fixtures/legacy-uuid-canonicalization.json").getAsJsonArray("cases");
    }

    private static List<Fixture> fixtures(String prefix, boolean valid, String key) {
        List<Fixture> fixtures = new ArrayList<>();
        for (String path : paths()) {
            if (path.startsWith(prefix) && path.endsWith(".json")) {
                JsonObject json = object(path);
                fixtures.add(new Fixture(path, valid ? "valid" : json.get("expect").getAsString(),
                        valid ? "" : json.get("why").getAsString(), valid ? json : json.getAsJsonObject(key)));
            }
        }
        return fixtures;
    }

    public static final class Fixture {
        private final String path;
        private final String expect;
        private final String why;
        private final JsonObject frame;

        private Fixture(String path, String expect, String why, JsonObject frame) {
            this.path = path;
            this.expect = expect;
            this.why = why;
            this.frame = frame.deepCopy();
        }

        public String path() { return path; }
        public String expect() { return expect; }
        public String why() { return why; }
        public JsonObject frame() { return frame.deepCopy(); }
        public JsonObject body() { return frame(); }
        @Override public String toString() { return path; }
    }
}
