package com.ultikits.testfixtures.dependprecheck;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * A module main class that loads and constructs normally; only its scanned component {@link
 * PrecheckProviderService} needs the absent required plugin. Its static initializer records every
 * initialization, which runs before any constructor, resource extraction or class scan.
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.testfixtures.dependprecheck"})
public class PrecheckModule extends UltiToolsPlugin {

    static {
        PrecheckProbe.INITIALIZED.incrementAndGet();
    }

    @Override
    public boolean registerSelf() {
        return true;
    }
}
