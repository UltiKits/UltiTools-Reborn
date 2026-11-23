package com.ultikits.ultitools.text;

import java.util.Locale;
import java.util.Optional;

class FixedLocaleResolverContractTest extends AbstractLocaleResolverContractTest {
    @Override
    protected LocaleResolver createResolver(Locale fallback, Optional<Locale> forced) {
        return new FixedLocaleResolver(fallback, forced);
    }
}
