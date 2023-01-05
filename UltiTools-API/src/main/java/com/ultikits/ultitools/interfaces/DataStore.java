package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.DataEntity;

public interface DataStore {
    /**
     * 获取此数据存储的类型
     *
     * @return 此数据存储的类型
     */
    String getStoreType();

    /**
     * @param plugin     插件模块
     * @param dataEntity 数据实体类
     * @param <T>        必须继承{@link DataEntity}
     * @return 数据操作实体类
     */
    <T extends DataEntity> DataOperator<T> getOperator(IPlugin plugin, Class<T> dataEntity);

    /**
     * 保存所有可能的缓存并销毁所有的数据操作类
     */
    void destroyAllOperators();
}
