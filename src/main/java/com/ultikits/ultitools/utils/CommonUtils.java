package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.UUID;

import com.ultikits.ultitools.exceptions.DataAccessException;

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
     * <p>
     * A credential file that exists but is empty, whitespace-only or not valid JSON is never
     * replaced by a fresh identity (#573): this method fails with an {@link IOException} that names
     * the file and leaves it untouched. Only a missing file starts a new UUID. Every failure of the
     * credential store is reported as this method's declared {@link IOException}, never as an
     * unchecked exception, so a caller's {@code catch (IOException e)} covers it.
     *
     * @return UUID
     * @throws IOException if the credential file cannot be read or written, or exists but cannot be
     *                     read as a credential document
     */
    public static String getUltiToolsUUID() throws IOException {
        try {
            return readOrCreateUuid();
        } catch (DataAccessException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static String readOrCreateUuid() throws IOException {
        CredentialStore.ReadResult current = CredentialStore.read();
        if (current.isParseFailure()) {
            throw new IOException(current.failureMessage());
        }
        if (current.isParsed()) {
            Object existingUuid = current.data().get("uuid");
            if (existingUuid != null) {
                return existingUuid.toString();
            }
        }
        // Absent, or a first-ever/racing generation: fall through to update(), which re-reads
        // under the store lock and only then decides whether a uuid still needs generating; if the
        // file became unreadable in between, update() refuses rather than overwriting it.
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
