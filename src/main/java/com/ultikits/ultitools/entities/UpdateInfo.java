package com.ultikits.ultitools.entities;

import lombok.Data;

/**
 * Holds update information for a single plugin or the framework.
 *
 * @since 6.2.0
 */
@Data
public class UpdateInfo {
    private String pluginName;
    private String identifyString;
    private String currentVersion;
    private String latestVersion;
}
