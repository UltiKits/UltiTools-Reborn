package com.ultikits.ultitools.interfaces;

/**
 * Base service interface.
 */
public interface BaseService {
    /**
     * Get service name, this name will appear in the console or in the game.
     *
     * @return Service name
     */
    String getName();

    /**
     * Get service resource folder name, not only the name under the server folder /plugins/UltiTools/config,
     * but also the folder path under the plugin project resources folder.
     *
     * @return service resource folder name
     */
    default String getResourceFolderName() {
        return this.getName();
    }

    /**
     * @return Author name
     */
    String getAuthor();

    /**
     * Version number, used to compare the version number of the registered service, currently not in use.
     *
     * @return service version
     */
    int getVersion();
}
