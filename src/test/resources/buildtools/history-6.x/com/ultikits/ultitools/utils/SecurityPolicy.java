package com.ultikits.ultitools.utils;

/** Frozen declaration-only fixture for the archived 6.x ledger, not a runtime API. */
public class SecurityPolicy {
    /**
     * @deprecated Since 6.3.0 (GEN-07) this method is an unconditional no-op -- the three
     * name-based classload filter layers it enforced never protected anything (they evaluated
     * already-trusted, already-loaded classes, not arbitrary untrusted input), and the
     * trusted-package whitelist alone would have refused nearly every third-party module class.
     * The signature is retained because it is {@code public static} on a published class and
     * cannot be removed without breaking a downstream caller's compile. See the package-private
     * {@code ClassloadFilterAudit} for what these removed layers would have refused, now recorded
     * as telemetry.
     */
    @Deprecated(since = "6.3.0")
    public static boolean isSafeClassName(String className) {
        return true;
    }

    /**
     * @deprecated Since 6.3.0 (GEN-07), for the same reason as isSafeClassName(String) --
     * its only in-tree caller (the seven-argument {@code PluginManager.register}/with-args
     * reflective-construction path) was deleted by GEN-04, which is what dissolved this method's
     * former "shared-field, can't safely delete the whitelist" objection.
     */
    @Deprecated(since = "6.3.0")
    public static boolean isSafeParameterType(Class<?> clazz) {
        return true;
    }

    /**
     * @deprecated Since 6.3.0 (GEN-07): this method no longer mutates
     * {@code ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES} or anything else. The signature is
     * retained because it is {@code public static} on a published class.
     */
    @Deprecated(since = "6.3.0")
    public static void addTrustedPackage(String packagePrefix) {
        // Declaration metadata only; never invoked.
    }

    /**
     * @deprecated Since 6.3.0 (GEN-07): this method no longer mutates
     * {@code ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES} or anything else. The signature is
     * retained because it is {@code public static} on a published class.
     */
    @Deprecated(since = "6.3.0")
    public static void addDangerousClass(String className) {
        // Declaration metadata only; never invoked.
    }
}
