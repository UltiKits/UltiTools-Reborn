package com.ultikits.ultitools.interfaces;

import cn.hutool.db.sql.Condition;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.entities.WhereCondition;

import java.util.List;

/**
 * Data operation interface.
 * <p>
 * 数据操作接口
 *
 * @param <T> 数据类型，继承自AbstractDataEntity
 */
public interface DataOperator<T extends AbstractDataEntity> {

    /**
     * Check if the data record exists.
     * <p>
     * 查询记录数据是否存在。
     *
     * @param object Data record entity <br> 数据记录实体
     * @return Whether the record exists <br> 记录是否存在
     */
    boolean exist(T object);

    /**
     * Check if the data record exists.
     * <p>
     * 使用条件查询记录是否存在
     *
     * @param whereConditions Conditions <br> 条件参数
     * @return Whether the record exists <br> 记录是否存在
     */
    boolean exist(WhereCondition... whereConditions);

    /**
     * Get data record by ID.
     * <p>
     * 使用ID获取记录
     *
     * @param id Record ID <br> 记录ID
     * @return Data record <br> 数据记录
     */
    T getById(Object id);

    /**
     * Get all data record.
     * <p>
     * 查询所有记录
     *
     * @return Data record list <br> 数据记录列表
     */
    List<T> getAll();

    /**
     * Get all data record by conditions.
     * <p>
     * 查询所有符合条件的记录
     *
     * @param whereConditions Conditions <br> 条件参数
     * @return Data record list <br> 数据记录列表
     */
    List<T> getAll(WhereCondition... whereConditions);

    /**
     * Fuzzy Query
     * <p>
     * 模糊查询
     *
     * @param column   Column name <br> 列名
     * @param value    Query value <br> 查询值
     * @param likeType Like type <br> 模糊查询类型 <br>{@link Condition.LikeType}
     * @return Data record list <br> 数据记录列表
     */
    List<T> getLike(String column, String value, Condition.LikeType likeType);

    /**
     * Get data record by page.
     * <p>
     * 分页查询
     *
     * @param page            Page number <br> 页码
     * @param size            Page size <br> 页大小
     * @param whereConditions Conditions <br> 条件参数
     * @return Data record list <br> 数据记录列表
     */
    List<T> page(int page, int size, WhereCondition... whereConditions);

    /**
     * Insert data record.
     * <p>
     * 新增记录
     *
     * @param obj Data record <br> 数据记录
     */
    void insert(T obj);

    /**
     * Delete data record by conditions.
     * <p>
     * 按照条件删除记录
     *
     * @param whereConditions Conditions <br> 条件参数
     */
    void del(WhereCondition... whereConditions);

    /**
     * Delete data record by ID.
     * <p>
     * 按照ID删除记录
     *
     * @param id Record ID <br> 记录ID
     */
    void delById(Object id);

    /**
     * Update one field of one record.
     * <p>
     * 更新某个记录的某个值
     *
     * @param column Column name <br> 列名
     * @param value  New value <br> 新值
     * @param id     Record ID <br> 记录ID
     */
    void update(String column, Object value, Object id);

    /**
     * Update data record by object. It will not update the fields that the incoming entity does not have.
     * <p>
     * 使用实体更新记录。不会更新传入实体没有的字段。
     *
     * @param obj Data record <br> 数据记录
     * @throws IllegalAccessException Please refer{@link IllegalAccessException}
     */
    void update(T obj) throws IllegalAccessException;
}
