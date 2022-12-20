package com.ultikits.ultitools.interfaces;

import cn.hutool.db.sql.Condition;
import com.ultikits.ultitools.abstracts.DataEntity;
import com.ultikits.ultitools.entities.WhereCondition;

import java.util.Collection;
import java.util.List;

public interface DataOperator<T extends DataEntity> {

    boolean exist(T object);

    boolean exist(WhereCondition... whereConditions);

    T getById(Object id);

    Collection<T> getAll();

    Collection<T> getAll(WhereCondition... whereConditions);

    List<T> getLike(String column, String value, Condition.LikeType likeType);

    void insert(T obj);

    void del(WhereCondition... whereCondition);

    void delById(Object id);

    void update(String column, Object value, Object id);

    void update(T obj);
}
