package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ultikits.ultitools.entities.TokenEntity;

/**
 * The only thing that writes the UltiCloud credential to disk.
 * <p>
 * Package-private, with a package-private constructor -- a {@link CloudSession} is the only thing
 * in this codebase that constructs one (D-18). This is deliberately a thin wrapper, not a second
 * implementation of file I/O: every operation below delegates to {@link CredentialStore}, this
 * package's actual single owner of {@code credentials.json} on disk (its own structural test,
 * {@code CredentialStoreTest#onlyCredentialStoreTouchesTheCredentialFileDirectly}, asserts that no
 * class other than {@code CredentialStore} opens a reader or writer on that file -- delegating
 * rather than reimplementing keeps this class outside that scan, and correctly so: this class owns
 * <b>who may call</b> the write, not <b>how</b> the write happens).
 *
 * @since 6.3.0
 */
final class TokenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Package-private -- only a {@link CloudSession} may construct one. */
    TokenStore() {
    }

    /**
     * Persists {@code token}, replacing whatever {@code cloud_token} entry currently exists in the
     * credential document. Every other field in the document (server UUID, etc.) is left untouched
     * -- {@link CredentialStore#update} reads the existing document first.
     *
     * @param token the token to persist
     * @throws IOException if the underlying write fails
     */
    void save(TokenEntity token) throws IOException {
        Map<String, Object> tokenMap = new LinkedHashMap<>();
        tokenMap.put("access_token", token.getAccess_token());
        tokenMap.put("refresh_token", token.getRefresh_token());
        tokenMap.put("token_type", token.getToken_type());
        tokenMap.put("expires_in", token.getExpires_in());
        tokenMap.put("scope", token.getScope());
        tokenMap.put("jti", token.getJti());

        CredentialStore.update(existing -> {
            existing.put("cloud_token", tokenMap);
            return existing;
        });
    }

    /** Removes the persisted {@code cloud_token} entry, if any. */
    void clear() throws IOException {
        CredentialStore.update(existing -> {
            existing.remove("cloud_token");
            return existing;
        });
    }

    /**
     * Reads the raw credential document. Exposed rather than a parsed {@link TokenEntity} because
     * the caller ({@link CloudSession#loadFromDisk()}) needs to distinguish "absent" from "torn"
     * and log accordingly -- collapsing that here would lose the distinction
     * {@link CredentialStore.ReadResult} exists to preserve.
     *
     * @return the raw read result
     */
    CredentialStore.ReadResult readRaw() {
        return CredentialStore.read();
    }

    /**
     * Parses the {@code cloud_token} entry out of an already-read document, or {@code null} if
     * absent, unparseable, or missing an access token.
     *
     * @param data a parsed credential document, e.g. from {@link #readRaw()}
     * @return the parsed token, or {@code null}
     */
    TokenEntity parseToken(Map<String, Object> data) {
        Object saved = data.get("cloud_token");
        if (saved == null) {
            return null;
        }
        // Gson deserializes nested maps as LinkedTreeMap, so re-serialize and parse (mirrors the
        // pre-6.3.0 CloudAuthManager.loadSavedToken()'s own approach).
        String tokenJson = GSON.toJson(saved);
        TokenEntity token = GSON.fromJson(tokenJson, TokenEntity.class);
        if (token == null || token.getAccess_token() == null || token.getAccess_token().isEmpty()) {
            return null;
        }
        return token;
    }
}
