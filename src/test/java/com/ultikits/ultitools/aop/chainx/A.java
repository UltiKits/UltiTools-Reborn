package com.ultikits.ultitools.aop.chainx;

import com.ultikits.ultitools.annotations.ExceptionCatch;

/** Package-private annotated root of a transitive override chain that crosses a package. */
public class A {
    @ExceptionCatch(silent = true, defaultValue = "from-A")
    String m() { return "a"; }
}
