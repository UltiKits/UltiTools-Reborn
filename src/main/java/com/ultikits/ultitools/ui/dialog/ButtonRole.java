package com.ultikits.ultitools.ui.dialog;

import org.jetbrains.annotations.ApiStatus;

/** A button's semantic action, independent of its rendered label. */
@ApiStatus.Internal
public sealed interface ButtonRole {
    /** Acknowledges a notice. */
    @ApiStatus.Internal
    record Ack() implements ButtonRole {}
    /** Answers a confirmation affirmatively. */
    @ApiStatus.Internal
    record Yes() implements ButtonRole {}
    /** Answers a confirmation negatively. */
    @ApiStatus.Internal
    record No() implements ButtonRole {}
    /** Submits a form. */
    @ApiStatus.Internal
    record Submit() implements ButtonRole {}
    /** Ends without an answer. */
    @ApiStatus.Internal
    record Cancel() implements ButtonRole {}
    /** Selects an option by display position. @param index zero-based option position */
    @ApiStatus.Internal
    record Pick(int index) implements ButtonRole {}
}
