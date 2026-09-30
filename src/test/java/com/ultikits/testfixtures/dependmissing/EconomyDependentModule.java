package com.ultikits.testfixtures.dependmissing;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * A module main class with a constructor whose parameter type belongs to the absent required
 * plugin. {@code Class#getDeclaredConstructor()} resolves every declared constructor's parameter
 * types, so looking up the no-argument constructor already throws {@link NoClassDefFoundError}
 * naming {@link AbsentEconomyApi} -- before any module code runs, exactly as a real module whose
 * main class references Vault fails on a server without Vault.
 */
public class EconomyDependentModule extends UltiToolsPlugin {

    public EconomyDependentModule() {
        super();
    }

    @SuppressWarnings("PMD.UnusedFormalParameter") // the parameter's type, not its value, is the fixture
    public EconomyDependentModule(AbsentEconomyApi economy) {
        this();
    }

    @Override
    public boolean registerSelf() {
        return true;
    }
}
