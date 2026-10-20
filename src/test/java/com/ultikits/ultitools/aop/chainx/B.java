package com.ultikits.ultitools.aop.chainx;

/** Widens the root to public, which is what carries the chain across the package boundary. */
public class B extends A {
    @Override public String m() { return "b"; }
}
