/**
 * Thread-affine scheduling for UltiTools 7, shaped after Paper's global, region, entity and
 * async schedulers so call sites remain correct if Folia support is declared later.
 * Services are injected per module; all types are non-null by default.
 */
@org.jetbrains.annotations.NotNullByDefault
package com.ultikits.ultitools.scheduling;
