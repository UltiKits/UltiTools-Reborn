package com.ultikits.ultitools.annotations;

/** Frozen declaration-only fixture for the archived 6.x ledger, not a runtime API. */
public @interface UltiToolsModule {
    /**
     * @deprecated Deprecated since 6.5.0. Since 6.3.0, languages come from the {@code lang/*} files the module JAR ships
     * ({@code .json}, {@code .yml}, or {@code .yaml}), not this alias. Remove the attribute
     * and ship the language files instead. This attribute may be removed as early as 6.6.0.
     */
    @Deprecated(since = "6.5.0", forRemoval = true)
    String[] i18n() default {};
}
