package com.ultikits.ultitools.uat.fixtures.scanscopesibling;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * A valid {@code @ConfigEntity} in a SIBLING package that merely shares a raw string prefix
 * with {@code com.ultikits.ultitools.uat.fixtures.scanscope}
 * ("...fixtures.scanscopesibling" starts with the characters "...fixtures.scanscope" but is
 * NOT a subpackage of it -- there is no {@code '.'} boundary between "scanscope" and
 * "sibling"). Guava's {@code ClassPath#getTopLevelClassesRecursive("...fixtures.scanscope")}
 * (config's real runtime scan) does not match this package; a raw string-prefix filter would
 * wrongly include it (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@ConfigEntity("config/sibling.yml")
public class SiblingPackageConfig {
    @ConfigEntry(path = "enabled")
    private boolean enabled = true;
}
