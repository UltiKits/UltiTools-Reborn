package com.ultikits.testfixtures.crosspackagededup.pkgb;

import com.ultikits.testfixtures.crosspackagededup.pkga.PkgPrivateInitBase;
import com.ultikits.ultitools.annotations.PostConstruct;

/**
 * See {@link PkgPrivateInitBase}'s javadoc for the scenario this pair reproduces: this class lives
 * in a different package than its superclass and declares its own package-private {@code init()},
 * which per JLS 8.4.8.1 does not override the parent's.
 */
public class PkgPrivateInitChild extends PkgPrivateInitBase {

    @PostConstruct
    void init() {
        PkgPrivateInitBase.initCount++;
    }
}
