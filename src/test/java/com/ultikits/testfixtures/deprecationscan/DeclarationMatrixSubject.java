package com.ultikits.testfixtures.deprecationscan;

/** Compiled declarations used as data by the build-only scanner tests. */
public class DeclarationMatrixSubject {

    @Deprecated(since = "6.2.0")
    public void annotationOnly() {
    }

    /** Ordinary documentation without a deprecated block tag. */
    @Deprecated(since = "6.2.1")
    public void ordinaryDoc() {
    }

    /**
     * @deprecated Use the replacement field.
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.2")
    public String initializedField = "(";

    /**
     * @deprecated Use {@link java.lang.String} instead.<br>Secondary prose.
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.3", forRemoval = true)
    public void documented() {
    }
}
