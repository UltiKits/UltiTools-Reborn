package com.ultikits.ultitools.aop.crosspackage;

import com.ultikits.ultitools.annotations.ExceptionCatch;

/**
 * The method-level counterpart of {@link PackagePrivateBase}: the author named an inaccessible
 * method explicitly, so the load must fail with that method's name rather than be skipped.
 */
public class AnnotatedPackagePrivateBase {

    @ExceptionCatch(silent = true, defaultValue = "named")
    String annotatedPackagePrivate() { return "package-private"; }
}
