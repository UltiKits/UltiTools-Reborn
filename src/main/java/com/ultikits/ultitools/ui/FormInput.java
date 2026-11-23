package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;

/**
 * One input of a {@link Form}. Also the typed handle used to read the submitted value:
 * {@code response.get(nameInput)}.
 * The set of permitted subtypes may grow in a 7.x minor release. Code outside the framework
 * must not switch over it exhaustively without a {@code default} branch.
 * @param <T> the submitted value type
 */
public sealed interface FormInput<T> permits TextInput, ToggleInput, ChoiceInput, SliderInput {
    /** Unique within its form; {@code [a-z0-9_]+}, at most 32 characters. */
    String key();
    /** The deferred label. */
    Text label();
}
