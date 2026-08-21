package com.ultikits.ultitools.aop.crosspackage;

/**
 * A package-private method whose name and signature a cross-package subclass re-declares. The two
 * do not override one another, so both reach the scan and both map to the same trampoline name.
 */
public class SameNameBase {

    void shared() { }
}
