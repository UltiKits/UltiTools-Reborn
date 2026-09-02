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
     *
     * @return UUID
     * @throws IOException if an I/O error occurs
     */
    public static String getUltiToolsUUID() throws IOException {
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
