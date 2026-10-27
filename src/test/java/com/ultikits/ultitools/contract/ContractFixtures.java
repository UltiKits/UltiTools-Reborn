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
    /** The frozen catalogue REST contract 1.0.0 (UltiKits/ulticloud main 682735c9), vendored byte for byte. */
    public static final String CATALOGUE_ROOT = "ulticloud-contract/rest/catalogue/1.0.0";

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

    /**
     * @param relativePath a path inside the catalogue contract, as its MANIFEST names it
     * @return the file's text
     */
    public static String catalogueText(String relativePath) {
        return resource(CATALOGUE_ROOT + "/" + relativePath);
    }

    /** @return every file the catalogue contract's MANIFEST names */
    public static List<String> cataloguePaths() {
        JsonObject manifest = JsonParser.parseString(catalogueText("MANIFEST.json")).getAsJsonObject();
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : manifest.getAsJsonObject("files").entrySet()) {
            paths.add(entry.getKey());
        }
        return paths;
    }

    /**
     * The catalogue fixtures of one operation and part, as CATALOGUE.md lays them out: a valid
     * fixture is the value itself, an invalid one wraps it as {@code {expect, why, body}}.
     *
     * @param operation the operation, e.g. {@code versionGet}
     * @param part      {@code query}, {@code response}, {@code error} or {@code headers}
     * @param valid     whether to read the valid or the invalid directory
     * @return the fixtures, in MANIFEST order
     */
    public static List<Fixture> catalogueFixtures(String operation, String part, boolean valid) {
        String prefix = "fixtures/" + operation + "/" + part + "/" + (valid ? "valid/" : "invalid/");
        List<Fixture> fixtures = new ArrayList<>();
        for (String path : cataloguePaths()) {
            if (path.startsWith(prefix) && path.endsWith(".json")) {
                JsonObject json = JsonParser.parseString(catalogueText(path)).getAsJsonObject();
                fixtures.add(new Fixture(path, valid ? "valid" : json.get("expect").getAsString(),
                        valid ? "" : json.get("why").getAsString(), valid ? json : json.getAsJsonObject("body")));
            }
        }
        return fixtures;
    }

    /**
     * @param path a catalogue fixture path, e.g. {@code fixtures/versionGet/response/valid/public-download.json}
     * @return the fixture's body: the file itself for a valid one, its {@code body} for an invalid one
     */
    public static JsonObject catalogueBody(String path) {
        JsonObject json = JsonParser.parseString(catalogueText(path)).getAsJsonObject();
        return path.contains("/invalid/") ? json.getAsJsonObject("body") : json;
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
