package com.ultikits.ultitools.aop.crosspackage;

import com.ultikits.ultitools.annotations.ExceptionCatch;

/**
 * A package-private method whose name and signature a cross-package subclass re-declares. The two
 * do not override one another, so both reach the scan and both map to the same trampoline name.
 */
@ExceptionCatch(silent = true, defaultValue = "class-level")
public class SameNameBase {

    void shared() { }
}
