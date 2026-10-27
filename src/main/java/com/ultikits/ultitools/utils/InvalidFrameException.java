package com.ultikits.ultitools.utils;

final class InvalidFrameException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final String replyTo;

    InvalidFrameException(String code, String replyToOrNull, String issue) {
        super(issue);
        this.code = code;
        this.replyTo = replyToOrNull;
    }

    String code() { return code; }
    String replyTo() { return replyTo; }
}
