package com.ultikits.ultitools.text.internal;

import com.ultikits.ultitools.text.AbstractLocaleResolverContractTest;
import com.ultikits.ultitools.text.LocaleResolver;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

class ConfiguredLocaleResolverContractTest extends AbstractLocaleResolverContractTest {
    @Override
    protected LocaleResolver createResolver(Locale fallback, Optional<Locale> forced) {
        return new ConfiguredLocaleResolver(() -> forced.map(Locale::toLanguageTag).orElse("auto"),
                fallback::toLanguageTag, Logger.getAnonymousLogger());
    }
}
