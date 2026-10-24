package com.ultikits.ultitools.utils;

import java.util.regex.Pattern;

final class ContractPrimitives {
    /** packages/contracts/src/protocol/primitives.ts#Ulid */
    static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    /** packages/contracts/src/protocol/envelope.ts#TYPE_NAME_PATTERN */
    static final Pattern TYPE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private ContractPrimitives() { }
}
