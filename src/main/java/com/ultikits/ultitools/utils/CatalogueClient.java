package com.ultikits.ultitools.utils;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.manager.ModuleApiLevels;

/**
 * The module catalogue of protocol v2, read over {@code /v1/catalogue/*} (plan 22-18, D-27).
 *
 * <p>The routes, the response envelope {@code {data: ...}} and the version object follow the
 * backend's catalogue contract as it stood on 2026-10-24 (P3 plan 03-04 and P15's planning record,
 * contract answer 12): a module is addressed by its identifier, a version names its {@code
 * sizeBytes}, its lower-case {@code sha256}, its integer {@code apiLevel} and a {@code download}
 * that is either {@code {kind: "public", url}} or {@code {kind: "license_required"}}. That
 * contract is not frozen yet, so the samples that pin these shapes are marked as pending fixtures
 * ({@code @PendingFixture}) and plan 22-26 swaps in the exported fixtures.
 *
 * <p>Only the versions route takes the API-level filter ({@code apiLevel}, {@code minApiLevel});
 * the other routes reject undeclared parameters, so no request carries them. A relative {@code
 * download.url} is resolved against the configured base URL and an absolute one is accepted only
 * when it stays on that same origin, so a catalogue answer can never send a download elsewhere.
 *
 * @since 6.3.1
 */
final class CatalogueClient {

    /** The route prefix of every catalogue request. */
    static final String MODULES = "/v1/catalogue/modules";
    /** The route naming the newest UltiTools version. */
    static final String FRAMEWORK = "/v1/catalogue/framework";

    private CatalogueClient() {
    }

    /**
     * One version of one module, as the catalogue describes it.
     *
     * @since 6.3.1
     */
    static final class Version {
        private final String version;
        private final boolean latest;
        private final String sha256;
        private final long sizeBytes;
        private final Integer apiLevel;
        private final String downloadKind;
        private final String downloadUrl;

        private Version(String version, boolean latest, String sha256, long sizeBytes, Integer apiLevel,
                        String downloadKind, String downloadUrl) {
            this.version = version;
            this.latest = latest;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.apiLevel = apiLevel;
            this.downloadKind = downloadKind;
            this.downloadUrl = downloadUrl;
        }

        /** @return the version string */
        String version() {
            return version;
        }

        /** @return whether the catalogue marks it as the module's newest version */
        boolean latest() {
            return latest;
        }

        /** @return the {@code sha256} as written, or {@code null} when the catalogue named none */
        String sha256() {
            return sha256;
        }

        /** @return the {@code sizeBytes}, or {@code 0} when the catalogue named none */
        long sizeBytes() {
            return sizeBytes;
        }

        /** @return the integer {@code apiLevel}, or {@code null} when the catalogue named none */
        Integer apiLevel() {
            return apiLevel;
        }

        /** @return the {@code download.kind}, or {@code null} when the catalogue named none */
        String downloadKind() {
            return downloadKind;
        }

        /** @return the {@code download.url} as written, or {@code null} when there is none */
        String downloadUrl() {
            return downloadUrl;
        }

        /** @return whether this version offers a public download */
        boolean downloadable() {
            return "public".equals(downloadKind) && downloadUrl != null && !downloadUrl.trim().isEmpty();
        }
    }

    /**
     * @param identifyString a module's identify-string
     * @return the segment the catalogue addresses it by, or {@code null} when it cannot be one
     */
    static String moduleRef(String identifyString) {
        String normalized = PluginInstallUtils.normalizeIdentifyString(identifyString);
        if (normalized == null || !ContractPrimitives.MODULE_IDENTIFIER.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    /**
     * Reads one module.
     *
     * @param identifyString the module's identify-string
     * @return the module, or {@code null} when the catalogue does not know it or the answer is unusable
     */
    static PluginEntity module(String identifyString) {
        String ref = moduleRef(identifyString);
        if (ref == null) {
            return null;
        }
        CatalogueHttp.Json answer = CatalogueHttp.getJson(MODULES + "/" + ref);
        JsonObject card = dataObject(answer);
        return card == null ? null : toEntity(card);
    }

    /**
     * Reads one page of the catalogue.
     *
     * @param page     the page number, from 1
     * @param pageSize the page size
     * @return the modules of that page; empty for every failure, as the public list contract says
     */
    static List<PluginEntity> list(int page, int pageSize) {
        List<PluginEntity> entities = new ArrayList<>();
        CatalogueHttp.Json answer = CatalogueHttp.getJson(MODULES + "?page=" + page + "&pageSize=" + pageSize);
        JsonArray items = itemsOf(answer);
        if (items == null) {
            return entities;
        }
        for (JsonElement item : items) {
            if (item.isJsonObject()) {
                entities.add(toEntity(item.getAsJsonObject()));
            }
        }
        return entities;
    }

    /**
     * Reads the versions one framework can install, newest first as the catalogue lists them.
     *
     * @param identifyString the module's identify-string
     * @param level          this framework's API level
     * @return the versions, or {@code null} when the module is unknown or the answer is unusable
     */
    static List<Version> versions(String identifyString, int level) {
        String ref = moduleRef(identifyString);
        if (ref == null) {
            return null;
        }
        return versionsOf(ref, level);
    }

    /**
     * Reads one version.
     *
     * @param identifyString the module's identify-string
     * @param version        the version string
     * @return the version, or {@code null} when the catalogue does not offer it
     */
    static Version version(String identifyString, String version) {
        String ref = moduleRef(identifyString);
        if (ref == null || version == null || version.trim().isEmpty()) {
            return null;
        }
        String path = MODULES + "/" + ref + "/versions/" + encodeSegment(version.trim());
        JsonObject object = dataObject(CatalogueHttp.getJson(path));
        return object == null ? null : toVersion(object);
    }

    /**
     * Whether the catalogue knows a module at all.
     *
     * @param identifyString the module's identify-string
     * @return {@code FOUND} with its identifier, {@code NONE} when the catalogue does not know it,
     *         {@code FAILED} when the lookup established nothing
     */
    static VersionLookup moduleKnown(String identifyString) {
        String ref = moduleRef(identifyString);
        if (ref == null) {
            return VersionLookup.none();
        }
        CatalogueHttp.Json answer = CatalogueHttp.getJson(MODULES + "/" + ref);
        if (answer.kind() == CatalogueHttp.Kind.OVERSIZE) {
            return VersionLookup.failed(VersionLookup.TOO_LARGE);
        }
        if (answer.kind() != CatalogueHttp.Kind.OK) {
            return VersionLookup.failed("not reached");
        }
        if (answer.status() == 404) {
            return VersionLookup.none();
        }
        if (answer.status() < 200 || answer.status() > 299) {
            return VersionLookup.failed(VersionLookup.describe(answer.status(), answer.body()));
        }
        JsonObject card = dataObject(answer);
        if (card == null) {
            return VersionLookup.failed(VersionLookup.UNREADABLE);
        }
        String identifier = string(card, "identifier");
        return VersionLookup.found(identifier == null ? ref : identifier);
    }

    /**
     * Looks up a module's newest installable version, for a module the catalogue knows.
     *
     * @param identifyString the module's identify-string
     * @param level          this framework's API level
     * @return {@code FOUND} with the version, {@code NONE} when nothing installable (or nothing at
     *         all) is offered, {@code FAILED} when the lookup established nothing
     */
    static VersionLookup latestVersion(String identifyString, int level) {
        String ref = moduleRef(identifyString);
        if (ref == null) {
            return VersionLookup.none();
        }
        CatalogueHttp.Json answer = CatalogueHttp.getJson(MODULES + "/" + ref + "/versions" + levelQuery(level));
        if (answer.kind() == CatalogueHttp.Kind.OVERSIZE) {
            return VersionLookup.failed(VersionLookup.TOO_LARGE);
        }
        if (answer.kind() != CatalogueHttp.Kind.OK) {
            return VersionLookup.failed("not reached");
        }
        if (answer.status() == 404) {
            return VersionLookup.none();
        }
        if (answer.status() < 200 || answer.status() > 299) {
            return VersionLookup.failed(VersionLookup.describe(answer.status(), answer.body()));
        }
        List<Version> versions = parseVersions(answer);
        if (versions == null) {
            return VersionLookup.failed(VersionLookup.UNREADABLE);
        }
        Version newest = newest(versions);
        return newest == null ? VersionLookup.none() : VersionLookup.found(newest.version());
    }

    /**
     * Reads the version a download URL belongs to, from its own metadata route.
     *
     * @param metadataPath the path of the version's metadata, as {@link #metadataPathOf} returns it
     * @return the version, or {@code null} when it cannot be read
     */
    static Version versionAt(String metadataPath) {
        JsonObject object = dataObject(CatalogueHttp.getJson(metadataPath));
        return object == null ? null : toVersion(object);
    }

    /**
     * Derives a version's metadata path from its download URL.
     *
     * @param downloadUrl the absolute download URL
     * @return the path of the version's metadata route, or {@code null} when the URL is not a
     *         catalogue version download
     */
    static String metadataPathOf(String downloadUrl) {
        String base = CatalogueHttp.baseUrl();
        if (downloadUrl == null || base == null || !downloadUrl.startsWith(base + MODULES)) {
            return null;
        }
        String path = downloadUrl.substring(base.length());
        String suffix = "/download";
        if (path.indexOf('?') >= 0 || !path.endsWith(suffix)) {
            return null;
        }
        return path.substring(0, path.length() - suffix.length());
    }

    /**
     * Looks up the newest UltiTools version.
     *
     * @return {@code FOUND} with the version, or {@code FAILED} when the lookup established nothing
     */
    static VersionLookup frameworkNewest() {
        CatalogueHttp.Json answer = CatalogueHttp.getJson(FRAMEWORK);
        if (answer.kind() == CatalogueHttp.Kind.OVERSIZE) {
            return VersionLookup.failed(VersionLookup.TOO_LARGE);
        }
        if (answer.kind() != CatalogueHttp.Kind.OK) {
            return VersionLookup.failed("not reached");
        }
        if (answer.status() < 200 || answer.status() > 299) {
            return VersionLookup.failed(VersionLookup.describe(answer.status(), answer.body()));
        }
        JsonObject data = dataObject(answer);
        if (data == null) {
            return VersionLookup.failed(VersionLookup.UNREADABLE);
        }
        JsonElement version = data.get("version");
        if (version == null || version.isJsonNull() || !version.isJsonPrimitive()) {
            return VersionLookup.failed(VersionLookup.UNREADABLE);
        }
        String text = version.getAsString().trim();
        return text.isEmpty() ? VersionLookup.failed(VersionLookup.UNREADABLE) : VersionLookup.found(text);
    }

    /**
     * Resolves a {@code download.url} against the configured base URL.
     *
     * @param url the value the catalogue answered, relative or absolute
     * @return the absolute URL, or {@code null} when it would leave the catalogue's origin
     */
    static String absoluteDownloadUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String base = CatalogueHttp.baseUrl();
        if (base == null) {
            return null;
        }
        String value = url.trim();
        if (value.startsWith("/") && !value.startsWith("//")) {
            return base + value;
        }
        return value.startsWith(base + "/") ? value : null;
    }

    /** @return the filter every versions request carries: this framework's level and the floor */
    static String levelQuery(int level) {
        return "?apiLevel=" + level + "&minApiLevel=" + ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL;
    }

    /**
     * @param versions the versions to choose from, in any order
     * @return the one the catalogue marks as newest, the greatest otherwise, or {@code null}
     */
    static Version newest(List<Version> versions) {
        Version newest = null;
        for (Version version : versions) {
            if (version.latest()) {
                return version;
            }
            if (newest == null || VersionComparatorUtil.compare(version.version(), newest.version()) > 0) {
                newest = version;
            }
        }
        return newest;
    }

    // ------------------------------------------------------------------ parsing

    private static List<Version> versionsOf(String ref, int level) {
        CatalogueHttp.Json answer = CatalogueHttp.getJson(MODULES + "/" + ref + "/versions" + levelQuery(level));
        if (answer.kind() != CatalogueHttp.Kind.OK) {
            return null;
        }
        if (answer.status() == 404) {
            return new ArrayList<>();
        }
        if (answer.status() < 200 || answer.status() > 299) {
            return null;
        }
        return parseVersions(answer);
    }

    /** @return the versions an answer carries, or {@code null} when it carries no usable list */
    private static List<Version> parseVersions(CatalogueHttp.Json answer) {
        JsonArray items = itemsOf(answer);
        if (items == null) {
            return null;
        }
        List<Version> versions = new ArrayList<>();
        for (JsonElement item : items) {
            if (item.isJsonObject()) {
                versions.add(toVersion(item.getAsJsonObject()));
            }
        }
        return versions;
    }

    private static JsonObject dataObject(CatalogueHttp.Json answer) {
        if (answer.kind() != CatalogueHttp.Kind.OK || answer.status() < 200 || answer.status() > 299) {
            return null;
        }
        return data(answer.body());
    }

    private static JsonArray itemsOf(CatalogueHttp.Json answer) {
        JsonObject data = dataObject(answer);
        if (data == null) {
            return null;
        }
        JsonElement items = data.get("items");
        return items != null && items.isJsonArray() ? items.getAsJsonArray() : null;
    }

    private static JsonObject data(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement data = root.get("data");
            return data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Version toVersion(JsonObject object) {
        JsonElement sizeBytes = object.get("sizeBytes");
        JsonElement apiLevel = object.get("apiLevel");
        JsonObject download = object.has("download") && object.get("download").isJsonObject()
                ? object.getAsJsonObject("download") : null;
        return new Version(
                string(object, "version"),
                booleanValue(object, "isLatest"),
                string(object, "sha256"),
                sizeBytes == null || sizeBytes.isJsonNull() ? 0 : sizeBytes.getAsLong(),
                apiLevel == null || apiLevel.isJsonNull() ? null : apiLevel.getAsInt(),
                download == null ? null : string(download, "kind"),
                download == null ? null : string(download, "url"));
    }

    /**
     * Maps a module card into the entity the public catalogue methods have always returned. A v2
     * module is identified by a ULID and an identifier, and the entity's numeric {@code id} has no
     * v2 counterpart, so it stays {@code 0}; {@code identifyString} is the field callers use.
     */
    private static PluginEntity toEntity(JsonObject card) {
        PluginEntity entity = new PluginEntity();
        entity.setIdentifyString(string(card, "identifier"));
        entity.setName(string(card, "name"));
        entity.setDescription(string(card, "description"));
        entity.setShortDescription(string(card, "shortDescription"));
        entity.setIcon(string(card, "iconUrl"));
        entity.setDate(instant(string(card, "publishedAt")));
        entity.setModifiedTime(instant(string(card, "updatedAt")));
        return entity;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static Date instant(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(text.trim()));
        } catch (DateTimeParseException notAnInstant) {
            return null;
        }
    }

    private static String encodeSegment(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is required by the Java runtime", impossible);
        }
    }
}
