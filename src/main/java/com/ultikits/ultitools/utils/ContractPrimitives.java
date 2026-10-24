package com.ultikits.ultitools.utils;

import java.util.regex.Pattern;

final class ContractPrimitives {
    /** packages/contracts/src/protocol/primitives.ts#Ulid */
    static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    /** packages/contracts/src/protocol/envelope.ts#TYPE_NAME_PATTERN */
    static final Pattern TYPE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private static final String SEMVER_CORE = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)";
    private static final String MODULE_ID_SOURCE = "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*";
    private static final String SEGMENT = "(?:[^./\\\\:\\u0000-\\u001F][^/\\\\:\\u0000-\\u001F]*|\\.[^./\\\\:\\u0000-\\u001F][^/\\\\:\\u0000-\\u001F]*|\\.\\.[^/\\\\:\\u0000-\\u001F]+)";
    /** packages/contracts/src/protocol/primitives.ts#Semver */
    static final Pattern SEMVER = Pattern.compile("^" + SEMVER_CORE + "(-[0-9A-Za-z.-]+)?$");
    /** packages/contracts/src/protocol/primitives.ts#McUuid */
    static final Pattern MC_UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** packages/contracts/src/protocol/primitives.ts#McName */
    static final Pattern MC_NAME = Pattern.compile("^[^\\u0000- \\u007F]{1,32}$");
    /** packages/contracts/src/protocol/primitives.ts#Sha256Hex */
    static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    /** packages/contracts/src/protocol/primitives.ts#ServerPath */
    static final Pattern SERVER_PATH = Pattern.compile("^" + SEGMENT + "(?:/" + SEGMENT + ")*$");
    /** packages/contracts/src/protocol/primitives.ts#ModuleIdentifier */
    static final Pattern MODULE_IDENTIFIER = Pattern.compile("^" + MODULE_ID_SOURCE + "$");
    /** packages/contracts/src/protocol/primitives.ts#Capability */
    static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9-]*(:[a-z0-9][a-z0-9.-]*)?(@" + SEMVER_CORE + ")?$");
    /** packages/contracts/src/agent-rest/credential.ts#ServerCredential */
    static final Pattern SERVER_CREDENTIAL = Pattern.compile("^ucs_[A-Za-z0-9_-]{43}$");
    private static final Pattern MODULE_CAPABILITY = Pattern.compile("^modules:(" + MODULE_ID_SOURCE + ")@" + SEMVER_CORE + "$");

    private ContractPrimitives() { }

    static boolean isModuleCapability(String name) {
        if (name == null) { return false; }
        java.util.regex.Matcher match = MODULE_CAPABILITY.matcher(name);
        return match.matches() && match.group(1).length() <= 64;
    }
}
