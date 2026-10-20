package com.ultikits.testfixtures.malformedalias.scanner;

import com.ultikits.ultitools.annotations.AliasFor;
import com.ultikits.ultitools.annotations.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only fixture for {@code ComponentScannerMalformedAliasPropagationTest}: an
 * {@code @AliasFor} whose {@code annotation()} names {@link Component}, a type that is not
 * meta-present anywhere on this annotation's own declaration -- Spring's Implementation
 * Requirement 1, the same malformed shape as
 * {@code MergedAnnotationResolverTest#shouldRejectAliasForWhoseTargetIsNotMetaPresent} (03-01).
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one malformed
 * declaration, and the parent {@code malformedalias} package's {@code package-info} for why any
 * of this lives outside {@code com.ultikits.ultitools} at all.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MalformedComposedAnnotation {
    @AliasFor(annotation = Component.class, attribute = "value")
    String value() default "";
}
