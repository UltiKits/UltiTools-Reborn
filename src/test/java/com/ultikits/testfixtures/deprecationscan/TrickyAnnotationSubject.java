package com.ultikits.testfixtures.deprecationscan;

/**
 * Fixture for {@code JavadocDeprecationScannerTest}: a real, compiled class whose annotations and
 * bodies carry the structural characters -- a parenthesis inside an annotation string argument, a
 * semicolon inside a method body string -- that the scanner must not mistake for structure.
 * <p>
 * It has to be a real class rather than a temp-file source: the scanner resolves every member it
 * parses reflectively, so a class that is not on the test classpath fails before any assertion
 * about parsing can run.
 */
public class TrickyAnnotationSubject {

    /**
     * A semicolon inside a field initialiser's string, sitting before the declaration's own
     * terminator. Characterisation input -- the truncation it causes is harmless here because the
     * kind and name are already parsed by then.
     *
     * @deprecated legacy, kept only as scanner input
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.1")
    public String semicolonInStringBeforeTerminator = "a;b";

    /**
     * An UNBALANCED parenthesis inside the {@code since} value. A balanced pair inside a string is
     * harmless -- the two adjustments cancel -- so it is specifically the unbalanced case that
     * pushes the close-paren search past the real close paren and on into the rest of the file.
     *
     * @deprecated legacy, kept only as scanner input
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.0 (unclosed", forRemoval = true)
    public void unbalancedParenInSinceValue() {
        // no body
    }

    /**
     * A bare {@code @Deprecated} with no argument list at all.
     *
     * @deprecated legacy, kept only as scanner input
     * @removeIn 7.0.0
     */
    @Deprecated
    public void bareAnnotation() {
        // no body
    }
}
