package com.ultikits.ultitools.aop.chainy;

import com.ultikits.ultitools.aop.chainx.B;

/** In another package: it overrides A only transitively, through B. */
public class C extends B {
    @Override public String m() { throw new IllegalStateException("c-boom"); }
}
