package com.ultikits.ultitools.bootstrap;

/** The durable identity of a declaration, not a snapshot of a full Paper registry entry. */
record ContentEntry(String kind, String key) {
    static final String TAG_PREFIX = "tag/";

    ContentEntry {
        if (kind.isBlank() || key.isBlank()) {
            throw new IllegalArgumentException("Content kind and key must not be blank");
        }
    }
}
