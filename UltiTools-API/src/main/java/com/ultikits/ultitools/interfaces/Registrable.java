package com.ultikits.ultitools.interfaces;

public interface Registrable {
    /**
     * 获取服务的名称，此名称将会出现在控制台或者游戏中
     *
     * @return 服务的名称
     */
    String getName();

    /**
     * 获取服务资源文件夹的名字，不仅是存在服务器文件夹/plugins/UltiTools/config下的名字
     * 而且还是存在插件项目resources文件夹下的文件夹路径
     *
     * @return 服务资源文件夹的名字
     */
    default String getResourceFolderName(){
        return this.getName();
    }

    /**
     * @return 作者名称
     */
    String getAuthor();

    /**
     * 用于对比注册服务的版本号，目前没啥卵用嘿嘿嘿
     *
     * @return 服务版本号
     */
    int getVersion();
}
