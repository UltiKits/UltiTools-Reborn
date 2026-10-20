package com.ultikits.ultitools.aop.crosspackage;

import com.ultikits.ultitools.annotations.ExceptionCatch;

/**
 * The method-level counterpart of {@link PackagePrivateBase}. The annotation names a method the
 * proxy cannot reach from the bean's package, so it is ignored with a warning and the module
 * loads - the behaviour the earlier version of this fixture's javadoc described as a load
 * failure, before that was changed to match what Spring does.
 */
public class AnnotatedPackagePrivateBase {

    @ExceptionCatch(silent = true, defaultValue = "named")
    String annotatedPackagePrivate() { return "package-private"; }
}
