package com.ultikits.ultitools.item;

import io.papermc.paper.datacomponent.DataComponentType;
import java.util.Objects;

/**
 * One pass-through change to an item's data components, applied without localization.
 * The set of permitted subtypes may grow in a 7.x minor release. Code outside the framework
 * must not switch over it exhaustively without a {@code default} branch.
 */
public sealed interface DataPatch permits DataPatch.Assign, DataPatch.Flag, DataPatch.Remove {
    /** Returns the component type to change. */
    DataComponentType type();

    /**
     * Assigns a component value unchanged.
     * @param <T> component value type
     * @param type component type
     * @param value component value
     */
    record Assign<T>(DataComponentType.Valued<T> type, T value) implements DataPatch {
        /** Rejects null type or value. */
        public Assign {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Sets a flag component. @param type flag type */
    record Flag(DataComponentType.NonValued type) implements DataPatch {
        /** Rejects a null type. */
        public Flag {
            Objects.requireNonNull(type, "type");
        }
    }

    /** Removes a prototype component. @param type component type */
    record Remove(DataComponentType type) implements DataPatch {
        /** Rejects a null type. */
        public Remove {
            Objects.requireNonNull(type, "type");
        }
    }
}
