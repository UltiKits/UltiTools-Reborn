package com.ultikits.ultitools.ui.dialog;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable, already-localized dialog description without Paper registry objects.
 * @param title screen title
 * @param body body lines in display order
 * @param inputs form controls in display order
 * @param buttons primary actions in display order
 * @param exit optional exit action
 * @param kind arrangement of the actions
 * @param escapeCloses whether Escape may dismiss the screen
 */
@ApiStatus.Internal
public record DialogModel(Component title, List<Component> body, List<Input> inputs,
                          List<Button> buttons, @Nullable Button exit, Kind kind, boolean escapeCloses) {
    /** Copies all collections before publication. */
    public DialogModel {
        body = List.copyOf(body);
        inputs = List.copyOf(inputs);
        buttons = List.copyOf(buttons);
    }

    /** Position-only wire key, independent of module keys and Paper's reserved callback id. */
    static String inputKey(int index) {
        return "i" + index;
    }

    /** Paper-independent action layout. */
    @ApiStatus.Internal
    public enum Kind { NOTICE, CONFIRMATION, MULTI_ACTION }

    /**
     * One rendered action.
     * @param label button label
     * @param tooltip optional hover text
     * @param role semantic action
     */
    @ApiStatus.Internal
    public record Button(Component label, @Nullable Component tooltip, ButtonRole role) {}

    /** A form control; positional wire keys are mapped back to the original handles on submission. */
    @ApiStatus.Internal
    public sealed interface Input permits Text, Toggle, Single, Range {
        /** Positional wire key, not the module's form input key. */
        String key();
        /** Already-localized control label. */
        Component label();
    }

    /**
     * Text control.
     * @param key input key
     * @param label rendered label
     * @param initial initial text
     * @param maxLength maximum UTF-16 length
     * @param maxLines maximum line count
     */
    @ApiStatus.Internal
    public record Text(String key, Component label, String initial, int maxLength, int maxLines) implements Input {}

    /**
     * Boolean control.
     * @param key input key
     * @param label rendered label
     * @param initial initial checked state
     */
    @ApiStatus.Internal
    public record Toggle(String key, Component label, boolean initial) implements Input {}

    /**
     * Single-option control.
     * @param key input key
     * @param label rendered label
     * @param options ordered options with positional wire identifiers
     */
    @ApiStatus.Internal
    public record Single(String key, Component label, List<Option> options) implements Input {
        /** Copies options before publication. */
        public Single { options = List.copyOf(options); }
    }

    /**
     * One option, identified independently of the domain value's string representation.
     * @param id positional wire identifier
     * @param display rendered label
     * @param initial whether initially selected
     */
    @ApiStatus.Internal
    public record Option(String id, Component display, boolean initial) {}

    /**
     * Single-precision numeric control.
     * @param key input key
     * @param label rendered label
     * @param start inclusive minimum
     * @param end inclusive maximum
     * @param step positive increment
     * @param initial initial value
     */
    @ApiStatus.Internal
    public record Range(String key, Component label, float start, float end, float step, float initial) implements Input {}
}
