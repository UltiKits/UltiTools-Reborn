package com.ultikits.ultitools.utils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.manager.ModuleApiLevels;

/**
 * The module catalogue of protocol v2, read over {@code /v1/catalogue/*} (plans 22-18 and 22-26,
 * D-27, D-28).
 *
 * <p>The routes, the response envelope {@code {data: ...}} and the version object follow the frozen
 * catalogue REST contract {@value #CONTRACT_VERSION} of UltiCloud (UltiKits/ulticloud {@code main}
 * commit {@code 682735c9}, {@code packages/contracts/generated/rest-v1/catalogue/1.0.0/}), whose
 * schemas and fixtures the tests read byte for byte. A module is addressed by its identifier; a
 * version names its {@code sizeBytes}, its lower-case {@code sha256}, its {@code apiLevel} (an
 * integer, or {@code null} when unknown) and a {@code download} whose {@code kind} is {@code
 * public} (with an absolute {@code https:} URL), {@code license_required} or {@code withdrawn}.
 * Every kind but {@code public} is not downloadable.
 *
 * <p>A version object that breaks the contract in a field the install relies on -- a {@code sha256}
 * that is not 64 lower-case hex characters, a {@code sizeBytes} below 1, a missing or out-of-range
 * {@code apiLevel}, a missing {@code isLatest} or {@code version}, a public download without a URL --
 * is unusable: the lookup answers nothing, as for an unreadable body, and no download is requested.
 * Keys the client does not read, including keys a later contract may add, are ignored.
 *
 * <p>Only the versions route takes the API-level filter ({@code apiLevel}, {@code minApiLevel});
 * the other routes reject undeclared parameters, so no request carries them. A {@code
 * download.url} is accepted only when it is absolute and stays on the configured base URL's origin
 * (in production {@code https://api.ultikits.com}); a relative path, which the contract never
 * produces, is refused, so a catalogue answer can never send a download elsewhere.
 *
 * @since 6.3.1
 */
final class CatalogueClient {

    /** The route prefix of every catalogue request. */
    static final String MODULES = "/v1/catalogue/modules";
    /** The route naming the newest UltiTools version. */
    static final String FRAMEWORK = "/v1/catalogue/framework";
    /** The catalogue REST contract version this client implements; the vendored lock must name the same. */
    static final String CONTRACT_VERSION = "1.0.0";

    /** The contract's {@code sha256}: 64 lower-case hex characters. */
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** The contract's URL rule for icons: an absolute {@code https:} URL without spaces or control characters. */
    private static final Pattern HTTPS_URL = Pattern.compile(
            "^https://[^\\x00-\\x20\\x7F-\\x9F/?#]+([/?#][^\\x00-\\x20\\x7F-\\x9F]*)?$");
    /** The longest URL the contract allows. */
    private static final int MAX_URL_LENGTH = 2048;
    /** The longest module identifier the contract allows. */
    private static final int MAX_IDENTIFIER_LENGTH = 64;
    /** The largest {@code sizeBytes} the contract allows (2^53 - 1). */
    private static final long MAX_SIZE_BYTES = 9007199254740991L;
    /** The contract's {@code apiLevel} range. */
    private static final int MIN_API_LEVEL = 1;
    private static final int MAX_API_LEVEL = 99999;

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

        /** @return the {@code sha256}: 64 lower-case hex characters, as the contract requires */
        String sha256() {
            return sha256;
        }

        /** @return the {@code sizeBytes}: at least 1, as the contract requires */
        long sizeBytes() {
            return sizeBytes;
        }

        /** @return the integer {@code apiLevel}, or {@code null} when the catalogue named none */
        Integer apiLevel() {
            return apiLevel;
        }

        /** @return the {@code download.kind}: {@code public}, {@code license_required}, {@code withdrawn} or a later one */
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
     * The contract's {@code :moduleRef} for a module: its identifier (lower-case letter first,
     * segments separated by a single {@code .} or {@code -}, 1 to 64 characters). The framework never
     * addresses a module by its ULID.
     *
     * @param identifyString a module's identify-string
     * @return the segment the catalogue addresses it by, or {@code null} when it cannot be one
     */
    static String moduleRef(String identifyString) {
        String normalized = PluginInstallUtils.normalizeIdentifyString(identifyString);
        if (normalized == null || normalized.length() > MAX_IDENTIFIER_LENGTH
                || !ContractPrimitives.MODULE_IDENTIFIER.matcher(normalized).matches()) {
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
     * Checks a {@code download.url} against the configured base URL. The contract makes every URL
     * absolute ({@code https:} in production) and tells callers to use it as an opaque address, so
     * the URL is used as written, and only when it lies under the base URL: another host, another
     * scheme, a {@code user@host} form or a relative path is refused.
     *
     * @param url the value the catalogue answered
     * @return the URL, or {@code null} when it is relative or would leave the catalogue's origin
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
        return value.length() <= MAX_URL_LENGTH && value.startsWith(base + "/") ? value : null;
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

    /**
     * @return the versions an answer carries, or {@code null} when it carries no usable list: one
     *         version that breaks the contract makes the whole answer unusable, so the newest version
     *         is never chosen from a list the catalogue got wrong
     */
    private static List<Version> parseVersions(CatalogueHttp.Json answer) {
        JsonArray items = itemsOf(answer);
        if (items == null) {
            return null;
        }
        List<Version> versions = new ArrayList<>();
        for (JsonElement item : items) {
            Version version = item.isJsonObject() ? toVersion(item.getAsJsonObject()) : null;
            if (version == null) {
                return null;
            }
            versions.add(version);
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

    /**
     * Reads one version object, or refuses it: {@code null} when a field the install relies on breaks
     * the contract (see the class comment). A value of the wrong JSON type is a refusal, never an
     * exception.
     */
    private static Version toVersion(JsonObject object) {
        String version = string(object, "version");
        JsonElement latest = object.get("isLatest");
        String sha256 = string(object, "sha256");
        BigDecimal sizeBytes = integer(object.get("sizeBytes"));
        if (version == null || version.trim().isEmpty() || latest == null || !latest.isJsonPrimitive()
                || !latest.getAsJsonPrimitive().isBoolean() || sha256 == null || !SHA256.matcher(sha256).matches()
                || sizeBytes == null || sizeBytes.signum() <= 0
                || sizeBytes.compareTo(BigDecimal.valueOf(MAX_SIZE_BYTES)) > 0 || !object.has("apiLevel")) {
            return null;
        }
        JsonElement apiLevelValue = object.get("apiLevel");
        Integer apiLevel = null;
        if (!apiLevelValue.isJsonNull()) {
            BigDecimal level = integer(apiLevelValue);
            if (level == null || level.compareTo(BigDecimal.valueOf(MIN_API_LEVEL)) < 0
                    || level.compareTo(BigDecimal.valueOf(MAX_API_LEVEL)) > 0) {
                return null;
            }
            apiLevel = level.intValue();
        }
        JsonElement downloadValue = object.get("download");
        if (downloadValue == null || !downloadValue.isJsonObject()) {
            return null;
        }
        JsonObject download = downloadValue.getAsJsonObject();
        String kind = string(download, "kind");
        String url = string(download, "url");
        if (kind == null || ("public".equals(kind) && url == null)) {
            return null;
        }
        return new Version(version, latest.getAsBoolean(), sha256, sizeBytes.longValue(), apiLevel, kind,
                "public".equals(kind) ? url : null);
    }

    /** @return the JSON number as an integer, or {@code null} when it is absent, not a number or not whole */
    private static BigDecimal integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0 ? number : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
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
        entity.setIcon(httpsUrl(string(card, "iconUrl")));
        entity.setDate(instant(string(card, "publishedAt")));
        entity.setModifiedTime(instant(string(card, "updatedAt")));
        return entity;
    }

    /** @return the URL when it is an absolute {@code https:} URL the contract allows, otherwise {@code null} */
    private static String httpsUrl(String url) {
        return url != null && url.length() <= MAX_URL_LENGTH && HTTPS_URL.matcher(url).matches() ? url : null;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
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
