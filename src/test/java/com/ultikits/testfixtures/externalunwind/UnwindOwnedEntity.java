package com.ultikits.testfixtures.externalunwind;

import com.ultikits.ultitools.annotations.Table;

/**
 * An entity an external plugin declares through {@code additionalEntities}, so its data scope owns
 * something whose ownership record a refused second registration must not remove (#537, gate 1).
 */
@Table("unwind_owned_entity")
public class UnwindOwnedEntity {
}
