package com.ultikits.ultitools.interfaces;

import cn.hutool.db.sql.Condition;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.entities.WhereCondition;

import java.util.Collection;
import java.util.List;

public interface DataOperator<T extends AbstractDataEntity> {

    /**
     * 查询记录数据是否存在。
     *
     * @param object 数据记录实体
     * @return 是否存在此记录
     */
    boolean exist(T object);

    /**
     * 使用条件查询记录是否存在
     *
     * @param whereConditions 条件参数
     * @return 是否存在符合的记录
     */
    boolean exist(WhereCondition... whereConditions);

    /**
     * 使用ID获取记录
     *
     * @param id 记录ID
     * @return 查询到的数据记录
     */
    T getById(Object id);

    /**
     * 查询所有符合条件的记录
     *
     * @param whereConditions 条件参数
     * @return 符合条件的数据集
     */
    Collection<T> getAll(WhereCondition... whereConditions);

    /**
     * 模糊查询
     *
     * @param column   需要匹配的字段
     * @param value    需要匹配的值
     * @param likeType 模糊查询模式 {@link Condition.LikeType}
     * @return 符合条件的数据列表
     */
    List<T> getLike(String column, String value, Condition.LikeType likeType);

    /**
     * 新增记录
     *
     * @param obj 数据记录
     */
    void insert(T obj);

    /**
     * 按照条件删除记录
     *
     * @param whereConditions 删除条件参数
     */
    void del(WhereCondition... whereConditions);

    /**
     * 按照ID删除记录
     *
     * @param id 记录ID
     */
    void delById(Object id);

    /**
     * 更新某个记录的某个值
     *
     * @param column 需要更新的字段
     * @param value  需要更新的值
     * @param id     需要更新的记录ID
     */
    void update(String column, Object value, Object id);

    /**
     * 使用实体更新记录。不会更新传入实体没有的字段。
     *
     * @param obj 数据记录
     */
    void update(T obj) throws IllegalAccessException;
}
