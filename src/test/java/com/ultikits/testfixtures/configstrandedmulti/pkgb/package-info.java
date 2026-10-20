/**
 * One {@code @ConfigEntity} class whose {@code count} field always violates its own
 * {@code @Range} constraint - the package whose refusal must roll back the sibling
 * {@link com.ultikits.testfixtures.configstrandedmulti.pkga} package's already-registered entry
 * too (CR-01), not just this package's own.
 */
package com.ultikits.testfixtures.configstrandedmulti.pkgb;
