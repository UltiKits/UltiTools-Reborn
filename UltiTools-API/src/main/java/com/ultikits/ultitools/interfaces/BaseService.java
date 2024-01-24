package com.ultikits.ultitools.interfaces;

/**
 * Base service interface.
 * <p>
 * 基础服务接口。
 */
public interface BaseService {
    /**
     * Get service name, this name will appear in the console or in the game.
     * <p>
     * 获取服务的名称，此名称将会出现在控制台或者游戏中
     *
     * @return Service name <br> 服务的名称
     */
    String getName();

    /**
     * Get service resource folder name, not only the name under the server folder /plugins/UltiTools/config,
     * but also the folder path under the plugin project resources folder.
     * <p>
     * 获取服务资源文件夹的名字，不仅是存在服务器文件夹/plugins/UltiTools/config下的名字
     * 而且还是存在插件项目resources文件夹下的文件夹路径
     *
     * @return service resource folder name <br> 服务资源文件夹的名字
     */
    default String getResourceFolderName() {
        return this.getName();
    }

    /**
     * @return Author name <br> 作者名称
     */
    String getAuthor();

    /**
     * Version number, used to compare the version number of the registered service, currently not in use.
     * <p>
     * 用于对比注册服务的版本号，目前没啥卵用嘿嘿嘿
     *
     * @return service version <br> 服务版本号
     */
    int getVersion();
}
