package com.ultikits.ultitools.utils;

import java.util.regex.Pattern;
import org.jetbrains.annotations.ApiStatus;

/**
 * Frozen protocol patterns shared by framework validators; internal, not for module use.
 * Patterns are immutable and safe to share; create a matcher for each validation.
 * Each constant names its authoritative contract source below.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class ContractPrimitives {
    /** packages/contracts/src/protocol/primitives.ts#Ulid */
    public static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    /** packages/contracts/src/protocol/envelope.ts#TYPE_NAME_PATTERN */
    public static final Pattern TYPE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private static final String SEMVER_CORE = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)";
    private static final String MODULE_ID_SOURCE = "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*";
    private static final String SEGMENT = "(?:[^./\\\\:\\u0000-\\u001F][^/\\\\:\\u0000-\\u001F]*|\\.[^./\\\\:\\u0000-\\u001F][^/\\\\:\\u0000-\\u001F]*|\\.\\.[^/\\\\:\\u0000-\\u001F]+)";
    /** packages/contracts/src/protocol/primitives.ts#Semver */
    public static final Pattern SEMVER = Pattern.compile("^" + SEMVER_CORE + "(-[0-9A-Za-z.-]+)?$");
    /** packages/contracts/src/protocol/primitives.ts#McUuid */
    public static final Pattern MC_UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** packages/contracts/src/protocol/primitives.ts#McName */
    public static final Pattern MC_NAME = Pattern.compile("^[^\\u0000- \\u007F]{1,32}$");
    /** packages/contracts/src/protocol/primitives.ts#Sha256Hex */
    public static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    /** packages/contracts/src/protocol/primitives.ts#ServerPath */
    public static final Pattern SERVER_PATH = Pattern.compile("^" + SEGMENT + "(?:/" + SEGMENT + ")*$");
    /** packages/contracts/src/protocol/primitives.ts#ModuleIdentifier */
    public static final Pattern MODULE_IDENTIFIER = Pattern.compile("^" + MODULE_ID_SOURCE + "$");
    /** packages/contracts/src/protocol/primitives.ts#Capability */
    public static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9-]*(:[a-z0-9][a-z0-9.-]*)?(@" + SEMVER_CORE + ")?$");
    /** packages/contracts/src/agent-rest/credential.ts#ServerCredential */
    public static final Pattern SERVER_CREDENTIAL = Pattern.compile("^ucs_[A-Za-z0-9_-]{43}$");
    private static final Pattern MODULE_CAPABILITY = Pattern.compile("^modules:(" + MODULE_ID_SOURCE + ")@" + SEMVER_CORE + "$");

    private ContractPrimitives() { }

    /**
     * Checks a complete modules:&lt;identifier&gt;@&lt;X.Y.Z&gt; capability and its 64-character id bound.
     * @param name the capability name, or null
     * @return true only for a valid versioned module capability
     */
    public static boolean isModuleCapability(String name) {
        if (name == null) { return false; }
        java.util.regex.Matcher match = MODULE_CAPABILITY.matcher(name);
        return match.matches() && match.group(1).length() <= 64;
    }
}
