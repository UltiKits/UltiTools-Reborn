package com.ultikits.testfixtures.beanname;

import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;

/**
 * {@code @Bean(name=)}/{@code @Bean(value=)} effect fixtures for
 * {@code com.ultikits.ultitools.context.DeclaredAttributeEffectTest$BeanNameAndValue} (03-09,
 * Task 1). Every method here is reached only via reflection against
 * {@code ComponentScanner.processBeanMethod} -- never through a package scan -- but the class
 * still lives outside {@code com.ultikits.ultitools.context} on purpose; see this package's
 * {@code package-info} for why co-locating it there would be unsafe once the malformed-{@code
 * @Bean} hard-fail this plan implements starts propagating.
 */
@Configuration
public class BeanNameFixtures {

    @Bean
    public Object defaultName() {
        return new Object();
    }

    @Bean(name = "customName")
    public Object withCustomName() {
        return new Object();
    }

    @Bean(value = "customValueName")
    public Object withCustomValue() {
        return new Object();
    }

    @Bean(name = {"primary", "alias1", "alias2"})
    public PostConstructCounter withAliases() {
        return new PostConstructCounter();
    }

    @Bean(name = "a", value = "b")
    public Object conflictingNameValue() {
        return new Object();
    }

    @Bean(name = "same", value = "same")
    public Object identicalNameValue() {
        return new Object();
    }

    @Bean(name = {})
    public Object emptyNameArray() {
        return new Object();
    }

    @Bean(value = {})
    public Object emptyValueArray() {
        return new Object();
    }

    @Bean(name = "")
    public Object blankName() {
        return new Object();
    }

    @Bean(name = {"ok", "  "})
    public Object blankElementInArray() {
        return new Object();
    }

    // NFC (precomposed) name -- U+00E9 LATIN SMALL LETTER E WITH ACUTE, written as an explicit
    // backslash-u escape (not a literal accented character in the source file) so no
    // editor/tool text pipeline can silently re-normalize it before javac ever sees it.
    @Bean(name = "café")
    public String nfcName() {
        return "nfc-instance";
    }

    // NFD (decomposed) name -- U+0065 LATIN SMALL LETTER E followed by U+0301 COMBINING ACUTE
    // ACCENT, also an explicit backslash-u escape pair. Visually identical to nfcName()'s
    // declared name once rendered, but a different sequence of UTF-16 code units -- exactly
    // the case String.equals (no normalization) must treat as two distinct bean names.
    @Bean(name = "café")
    public String nfdName() {
        return "nfd-instance";
    }
}
