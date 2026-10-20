package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean annotation to replace Spring's @Bean.
 * <p>
 * {@code name()} and {@code value()} are mutual aliases (D-06): declaring both with different
 * content is a malformed declaration and fails the module's load, naming the declaring method
 * and both declared values; declaring both with identical content is legal. An empty or absent
 * {@code name()}/{@code value()} falls back to the factory method's own name, exactly as before
 * this attribute took effect. When more than one name is declared, the <b>first</b> element is
 * the bean's registered name and the rest are aliases sharing the same fully-assembled
 * instance -- Spring's own {@code @Bean} convention. A declared element that is blank or
 * whitespace-only also fails the module's load, naming the offending method: a name that cannot
 * name anything is not a usable third state between "declared" and "absent".
 * <p>
 * {@code @Target} declares {@link ElementType#METHOD} only. It used to also permit placing this
 * annotation on an annotation type declaration, but no code path in this framework ever acted on
 * a {@code @Bean} placed there -- {@code ComponentScanner.processBeanMethod} and
 * {@code SimpleContainer.processConfigurationClass} both only ever look for {@code @Bean} on
 * methods -- so the wider target let an author place this annotation somewhere nothing would
 * ever read it. Narrowed to methods only in 6.3.0 (issue #348); no semantics for the removed
 * target were implemented, since nothing has ever acted on it and inventing a meaning for it
 * here would be a new declared surface with no consumer.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
    /**
     * Bean name.
     *
     * @return bean name
     */
    String[] name() default {};

    /**
     * Bean value (alias for name).
     *
     * @return bean value
     */
    String[] value() default {};
}
