package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.DataEntity;

public interface DataStore {
    String getStoreType();

    <T extends DataEntity> DataOperator<T> getOperator(IPlugin plugin, Class<T> dataEntity);

    void destroyAllOperators();
}
