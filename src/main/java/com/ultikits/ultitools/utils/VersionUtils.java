package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.PluginInstallUtils.getPlugin;
import static com.ultikits.ultitools.utils.PluginInstallUtils.getPluginLatestVersion;

import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;

/**
 * Utility class for version checking and comparison operations.
 * Provides methods to check for plugin updates and compare semantic versions.
 * <br>
 * 版本检查和比较操作的实用工具类。
 * 提供检查插件更新和比较语义版本的方法。
 *
 * @author wisdomme
 * @since 6.0.0
 * @see VersionComparatorUtil
 */
public class VersionUtils {

    /**
     * Get the newest version of UltiTools from the remote API.
     * <br>
     * 从远程API获取UltiTools的最新版本。
     *
     * @return the newest UltiTools version string <br> UltiTools最新版本字符串
     */
    public static String getUltiToolsNewestVersion() {
        try (Response httpResponse = SimpleHttpClient.get("https://api.ultikits.com/plugin/ultitools/newest")) {
            return httpResponse.body();
        }
    }

    /**
     * Check if a plugin has an available update.
     * Compares the current version with the latest version from the remote API.
     * <br>
     * 检查插件是否有可用更新。
     * 将当前版本与远程API的最新版本进行比较。
     *
     * @param pluginIdString the plugin identify string <br> 插件ID
     * @param currentVersion the current version of the plugin <br> 当前版本
     * @return true if an update is available, false otherwise <br> 插件是否有更新
     */
    public static boolean pluginHasUpdate(String pluginIdString, String currentVersion) {
        PluginEntity plugin = getPlugin(pluginIdString);
        if (plugin == null) {
            return false;
        }
        String pluginLatestVersion = getPluginLatestVersion(pluginIdString);
        return VersionComparatorUtil.compare(currentVersion, pluginLatestVersion) < 0;
    }
}
