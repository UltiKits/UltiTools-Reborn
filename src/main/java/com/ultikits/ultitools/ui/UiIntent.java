package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;

/**
 * Something to show to one player, completing with a value of type {@code R}. Immutable.
 * The set of permitted subtypes may grow in a 7.x minor release. Code outside the framework
 * must not switch over it exhaustively without a {@code default} branch.
 * @param <R> the answer type
 */
public sealed interface UiIntent<R> permits Notice, Confirm, Form, Choice, Grid {
    /** Title shown by every backend. */
    Text title();
}
