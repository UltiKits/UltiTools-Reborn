package com.ultikits.ultitools.contract;

import org.junit.jupiter.api.Tag;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks hand-written samples awaiting the catalogue REST contract export (待夹具).
 * Frozen protocol fixtures must be used directly, without this marker.
 * Plan 22-26 replaces these samples and removes the marker once the real fixtures are available.
 */
@Tag("pending-fixture")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PendingFixture { }
