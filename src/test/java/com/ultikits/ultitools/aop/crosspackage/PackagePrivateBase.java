package com.ultikits.ultitools.aop.crosspackage;

import com.ultikits.ultitools.annotations.ExceptionCatch;

/**
 * Lives in a package of its own so that a bean in {@code com.ultikits.ultitools.aop} inherits a
 * package-private method it cannot legally override. An inheritance-based proxy is generated in
 * the bean's package, so neither the override nor the {@code super} call is possible.
 */
@ExceptionCatch(silent = true, defaultValue = "class-level")
public class PackagePrivateBase {

    String packagePrivateHelper() { return "package-private"; }

    public String ordinary() { throw new IllegalStateException("ordinary-boom"); }
}
