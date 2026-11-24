package com.ultikits.ultitools.command.engine;

/** An annotation model that cannot describe one unambiguous command tree. */
public final class CommandCompilationException extends IllegalArgumentException {
    /** @param message the executor, method, format and reason */
    public CommandCompilationException(String message) { super(message); }
}
