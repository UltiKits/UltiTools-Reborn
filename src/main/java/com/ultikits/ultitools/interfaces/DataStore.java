package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.manager.DataScope;

/**
 * Data storage interface.
 * <p>
 * 数据存储接口
 */
public interface DataStore {
    /**
     * Get the type of data storage.
     * <p>
     * 获取此数据存储的类型
     *
     * @return Data storage type <br> 数据存储类型
     */
    String getStoreType();

    /**
     * Get data operation entity class.
     * <p>
     * 获取数据操作实体类
     *
     * @param plugin     Plugin <br> 插件
     * @param dataEntity Data entity class <br> 数据实体类
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return Data operation entity <br> 数据操作实体
     */
    <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity);

    /**
     * Get data operator for an external plugin, scoped to the plugin's own data folder.
     * <p>
     * 获取外部插件的数据操作器，数据范围限定在插件自己的数据文件夹中。
     *
     * @param dataFolder the external plugin's data folder (e.g., plugins/MyPlugin/)
     * @param dataEntity data entity class
     * @param <T> entity type
     * @return data operator
     * @since 6.2.2
     */
    default <T extends BaseDataEntity<String>> DataOperator<T> getOperator(java.io.File dataFolder, Class<T> dataEntity) {
        throw new UnsupportedOperationException("This DataStore does not support external plugin data storage");
    }

    /**
     * Get the JDBC {@link javax.sql.DataSource} backing this store for the given scope, needing
     * no entity class up front (a connection pool needs only a JDBC URL).
     * <p>
     * 获取该存储针对给定 scope 的底层 JDBC {@link javax.sql.DataSource}，无需预先给出实体类
     * （连接池只需要 JDBC URL）。非 JDBC 存储（如 JSON 后端）预期保留此默认实现。
     *
     * @param scope the requesting scope <br> 请求方的 scope
     * @return the JDBC data source for that scope <br> 该 scope 对应的 JDBC 数据源
     * @since 6.3.0
     */
    default javax.sql.DataSource getDataSource(DataScope scope) {
        throw new UnsupportedOperationException("This DataStore (" + getStoreType() + ") does not expose a JDBC DataSource");
    }

    /**
     * Save all possible caches and destroy all data operation classes.
     * <p>
     * 保存所有可能的缓存并销毁所有的数据操作类
     */
    void destroyAllOperators();
}
