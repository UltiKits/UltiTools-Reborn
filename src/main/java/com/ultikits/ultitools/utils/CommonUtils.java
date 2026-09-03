package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.UUID;

/**
 * Common utility class providing general-purpose helper methods.
 * This class contains utility methods used throughout the UltiTools plugin.
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class CommonUtils {

    /**
     * get UltiTools UUID
     * <p>
     * Reads the current UUID without writing to the credential store when one already exists --
     * only the first-ever call (or a rare concurrent race against another first-ever call) needs
     * to write. WR-01 (08-REVIEW.md): the previous unconditional {@link CredentialStore#update}
     * call rewrote the live-credential file on every invocation, including the common
     * already-exists case, which needlessly widened the write window on the single-owner store.
     *
     * @return UUID
     * @throws IOException if an I/O error occurs
     */
    public static String getUltiToolsUUID() throws IOException {
        CredentialStore.ReadResult current = CredentialStore.read();
        if (current.isParsed()) {
            Object existingUuid = current.data().get("uuid");
            if (existingUuid != null) {
                return existingUuid.toString();
            }
        }
        // Absent, or a first-ever/racing generation: fall through to update(), which re-reads
        // under the store lock and only then decides whether a uuid still needs generating. This
        // also preserves update()'s parse-failure handling (isParseFailure() throws) for a torn
        // credential file, matching the pre-fix behavior for that case.
        String[] uuidHolder = new String[1];
        CredentialStore.update(existing -> {
            Object existingUuid = existing.get("uuid");
            if (existingUuid != null) {
                uuidHolder[0] = existingUuid.toString();
            } else {
                String generated = UUID.randomUUID().toString().replace("-", "");
                existing.put("uuid", generated);
                uuidHolder[0] = generated;
            }
            return existing;
        });
        return uuidHolder[0];
    }
}
