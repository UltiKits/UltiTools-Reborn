package com.ultikits.ultitools.interfaces.impl.data;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.TransactionManager;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import cn.hutool.db.sql.Condition;

/**
 * Abstract base class for relational database data operators.
 * <p>
 * This class extracts common logic from MySQL and SQLite implementations,
 * providing a unified implementation for CRUD operations. Only the
 * database-specific SQL dialect differences need to be overridden.
 *
 * @param <T> the entity type
 * @author wisdomme
 * @since 6.2.0
 */
public abstract class AbstractRelationalDataOperator<T extends AbstractDataEntity> implements DataOperator<T> {

    private static final Logger LOGGER = Logger.getLogger(AbstractRelationalDataOperator.class.getName());

    protected final Class<T> type;
    protected final DataSource dataSource;
    protected final String tableName;
    protected TransactionManager transactionManager;

    /**
     * Creates a new data operator.
     *
     * @param dataSource the data source to use
     * @param type the entity class
     */
    protected AbstractRelationalDataOperator(DataSource dataSource, Class<T> type) {
        this.type = type;
        this.dataSource = dataSource;
        Table tableAnnotation = AnnotationUtil.getAnnotation(type, Table.class);
        if (tableAnnotation == null) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Entity class " + type.getName() + " must have @Table annotation");
        }
        this.tableName = tableAnnotation.value();
        initializeTable();
    }

    /**
     * Sets the transaction manager for transaction-aware operations.
     *
     * @param transactionManager the transaction manager
     */
    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * Gets the Db instance, using transaction connection if available.
     * <p>
     * Note: When using transactions, the TransactionManager provides connections
     * from the same DataSource but with proper transaction context.
     */
    protected Db getDb() {
        // Hutool's Db.use() works with DataSource, not raw Connection
        // Transaction awareness is handled by DataSourceTransactionManager
        // which wraps the DataSource connections with transaction context
        return Db.use(dataSource);
    }

    /**
     * Initializes the database table.
     */
    private void initializeTable() {
        try {
            String createTableSql = createTableSqlFromClazz(type);
            Db.use(dataSource).execute(createTableSql);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to create table: " + tableName, e);
        }
    }

    @Override
    public boolean exist(T object) {
        return exist(WhereCondition.builder().column("id").value(object.getId()).build());
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        Entity entity = createQueryEntity(whereConditions);
        try {
            return getDb().find(entity).size() > 0;
        } catch (SQLException e) {
            LOGGER.warning("Failed to check existence: " + e.getMessage());
            return false;
        }
    }

    @Override
    public T getById(Object id) {
        try {
            Entity entity = getDb().get(
                    Entity.create(tableName).set("id", id)
            );
            if (entity == null) {
                return null;
            }
            return entity.toBean(type);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to get entity by id: " + id, e);
        }
    }

    @Override
    public List<T> getAll() {
        return getAll(WhereCondition.empty());
    }

    @Override
    public List<T> getAll(WhereCondition... whereConditions) {
        Entity entity = createQueryEntity(whereConditions);
        List<T> collection = new ArrayList<>();
        try {
            List<Entity> entities = getDb().find(entity);
            for (Entity res : entities) {
                collection.add(entityToBean(res));
            }
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to get all entities", e);
        }
        return collection;
    }

    @Override
    public List<T> getLike(String column, String value, Condition.LikeType likeType) {
        List<T> collection = new ArrayList<>();
        try {
            List<Entity> like = getDb().findLike(tableName, column, value, likeType);
            for (Entity entity : like) {
                collection.add(entity.toBean(type));
            }
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to query with LIKE", e);
        }
        return collection;
    }

    @Override
    public List<T> page(int page, int size, WhereCondition... whereConditions) {
        Entity entity = createQueryEntity(whereConditions);
        List<T> collection = new ArrayList<>();
        try {
            List<Entity> entities = getDb().page(entity, page, size);
            for (Entity res : entities) {
                collection.add(entityToBean(res));
            }
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to page entities", e);
        }
        return collection;
    }

    @Override
    public void insert(T obj) {
        try {
            Entity entity = copyEntity(obj);
            getDb().insert(entity);
        } catch (IllegalAccessException e) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Failed to access entity fields", e);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to insert entity", e);
        }
    }

    @Override
    public void del(WhereCondition... whereConditions) {
        Entity entity = createQueryEntity(whereConditions);
        try {
            getDb().del(entity);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to delete entities", e);
        }
    }

    @Override
    public void delById(Object id) {
        try {
            getDb().del(
                    Entity.create(tableName).set("id", id)
            );
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to delete entity by id: " + id, e);
        }
    }

    @Override
    public void update(String column, Object value, Object id) {
        if (!ClassUtil.isBasicType(value.getClass())) {
            value = JSON.toJSONString(value);
        }
        try {
            getDb().update(
                    Entity.create(tableName).set(column, value),
                    Entity.create(tableName).set("id", id)
            );
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to update column: " + column, e);
        }
    }

    @Override
    public void update(T obj) throws IllegalAccessException {
        try {
            Entity entity = copyEntity(obj);
            getDb().update(
                    entity,
                    Entity.create(tableName).set("id", obj.getId())
            );
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to update entity", e);
        }
    }

    /**
     * Converts a database entity to a Java bean.
     */
    protected T entityToBean(Entity res) {
        JSONObject jsonObject = new JSONObject();
        Set<String> fieldNames = res.getFieldNames();
        for (String field : fieldNames) {
            jsonObject.put(field, res.get(field));
        }
        return jsonObject.toJavaObject(type);
    }

    /**
     * Copies entity fields to a database entity.
     */
    protected Entity copyEntity(T obj) throws IllegalAccessException {
        Entity entity = Entity.create(tableName);
        Field[] fields = ReflectUtil.getFields(obj.getClass());
        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                Column column = field.getAnnotation(Column.class);
                Object value = field.get(obj);
                if (!ClassUtil.isBasicType(field.getType())) {
                    String jsonString = JSON.toJSONString(field.get(obj));
                    if (jsonString.startsWith("\"") && jsonString.endsWith("\"")) {
                        jsonString = jsonString.substring(1);
                        jsonString = jsonString.substring(0, jsonString.lastIndexOf("\""));
                    }
                    value = jsonString;
                }
                if (entity.get(column.value()) == null) {
                    entity.set(column.value(), value);
                }
            }
        }
        return entity;
    }

    /**
     * Creates a query entity from where conditions.
     */
    protected Entity createQueryEntity(WhereCondition[] whereConditions) {
        Entity entity = Entity.create(tableName);
        for (WhereCondition whereCondition : whereConditions) {
            if (whereCondition.isEmpty()) {
                return entity;
            }
            if (ClassUtil.isBasicType(whereCondition.getValue().getClass())) {
                entity.set(whereCondition.getColumn(), whereCondition.getValue());
            } else {
                entity.set(whereCondition.getColumn(), whereCondition.getValue().toString());
            }
        }
        return entity;
    }

    /**
     * Creates the SQL statement to create the table.
     * <p>
     * Override this method to customize table creation for different databases.
     *
     * @param type the entity class
     * @return the CREATE TABLE SQL statement
     */
    protected abstract String createTableSqlFromClazz(Class<T> type);

    /**
     * Builds the column definitions portion of CREATE TABLE.
     */
    protected String buildColumnDefinitions(Class<T> type) {
        StringBuilder stringBuilder = new StringBuilder();
        Field[] fields = ReflectUtil.getFields(type);
        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                Column column = AnnotationUtil.getAnnotation(field, Column.class);
                StringBuilder partSql = new StringBuilder();
                partSql.append("`").append(column.value()).append("` ").append(column.type()).append(",");
                String sql = partSql.toString();
                if (stringBuilder.indexOf(sql) < 0) {
                    stringBuilder.append(sql);
                }
            }
        }
        return stringBuilder.toString();
    }
}
