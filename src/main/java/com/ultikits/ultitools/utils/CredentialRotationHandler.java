package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Persists a rotated server credential and only then acknowledges it.
 *
 * <p>The gateway delivers {@code credential.rotate} with a new {@code ucs_} credential. Persisting
 * first keeps the promise that a failed write leaves the old credential in place and produces no
 * acknowledgement, so the gateway retries; a redelivery is persisted and acknowledged again. The
 * current connection is not dropped: the next handshake reads the new credential from the store
 * (D-10). The credential is never logged, and no diagnostic here carries it.</p>
 */
final class CredentialRotationHandler implements CloudRequestHandler {
    private static final Logger LOGGER = Logger.getLogger(CredentialRotationHandler.class.getName());

    /**
     * Writes a rotated credential durably.
     */
    interface CredentialPersister {
        /**
         * Stores the new credential, replacing the old one atomically.
         *
         * @param credential the new {@code ucs_} credential
         * @param credentialId the gateway's id for that credential
         * @return true only when the new credential is durably stored
         */
        boolean persistRotated(String credential, String credentialId);
    }

    private final CredentialPersister persister;

    /**
     * Creates the handler over a persister.
     * @param persister the durable credential store port
     */
    CredentialRotationHandler(CredentialPersister persister) {
        this.persister = Objects.requireNonNull(persister, "persister");
    }

    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        reader.keys(new String[] {"credential", "credentialId", "graceSeconds"});
        String credential = reader.string("credential", 47, 47, ContractPrimitives.SERVER_CREDENTIAL);
        String credentialId = reader.string("credentialId", 26, 26, ContractPrimitives.ULID);
        reader.integer("graceSeconds", 60, 604800);

        boolean persisted;
        try {
            persisted = persister.persistRotated(credential, credentialId);
        } catch (RuntimeException ex) {
            LOGGER.warning("UltiCloud: could not persist the rotated server credential for rotation "
                    + request.id() + "; the old credential stays in place and no acknowledgement is sent.");
            return;
        }
        if (!persisted) {
            LOGGER.warning("UltiCloud: could not persist the rotated server credential for rotation "
                    + request.id() + "; the old credential stays in place and no acknowledgement is sent.");
            return;
        }
        channel.reply(request.id(), "credential.rotate.ack", new JsonObject());
    }
}
