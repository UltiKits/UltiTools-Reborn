package com.ultikits.ultitools.entities;

import lombok.Data;

/**
 * Holds update information for a single plugin or the framework.
 * <br>
 * 保存单个插件或框架的更新信息。
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
