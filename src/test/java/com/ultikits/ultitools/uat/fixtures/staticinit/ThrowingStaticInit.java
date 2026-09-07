package com.ultikits.ultitools.uat.fixtures.staticinit;

/**
 * Lives alone in this sub-package so a directory scan rooted here enumerates exactly this one
 * class (Phase 10, T-10-01). Its static initializer throws unconditionally; a passing
 * {@code ModuleClassIndexTest} proves the extractor's {@code Class.forName(name, false, loader)}
 * never runs it.
 *
 * @since 6.3.0
 */
public final class ThrowingStaticInit {

    static {
        if (Boolean.TRUE) {
            throw new ExceptionInInitializerError("ThrowingStaticInit must never initialize during extraction");
        }
    }

    private ThrowingStaticInit() {
    }
}
