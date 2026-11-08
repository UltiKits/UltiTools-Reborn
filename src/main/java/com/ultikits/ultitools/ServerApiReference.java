package com.ultikits.ultitools;

import java.util.Objects;

/** An immutable JVM reference consumed by a rule and, later, by the linkage audit. */
final class ServerApiReference {
    enum Kind {
        TYPE, SUPERCLASS, INTERFACE, METHOD, FIELD, METHOD_HANDLE, FIELD_HANDLE
    }

    private final Kind kind;
    private final String owner;
    private final String name;
    private final String descriptor;
    private final int opcode;
    private final boolean interfaceOwner;

    /**
     * Type references have null member name/descriptor and opcode -1. Member references carry
     * the instruction opcode, or the handle tag for a METHOD_HANDLE/FIELD_HANDLE reference.
     */
    ServerApiReference(Kind kind, String owner, String name, String descriptor, int opcode, boolean interfaceOwner) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = name;
        this.descriptor = descriptor;
        this.opcode = opcode;
        this.interfaceOwner = interfaceOwner;
    }

    Kind getKind() {
        return kind;
    }

    String getOwner() {
        return owner;
    }

    String getName() {
        return name;
    }

    String getDescriptor() {
        return descriptor;
    }

    int getOpcode() {
        return opcode;
    }

    boolean isInterfaceOwner() {
        return interfaceOwner;
    }
}
