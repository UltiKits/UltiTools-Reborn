package com.ultikits.ultitools.interfaces.impl.data.mysql;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import cn.hutool.db.sql.Condition;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.SneakyThrows;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MysqlDataOperator<T extends AbstractDataEntity> implements DataOperator<T> {
    private final Class<T> type;
    private final DataSource dataSource;
    private final String tableName;

    public MysqlDataOperator(DataSource dataSource, Class<T> type) {
        this.type = type;
        this.dataSource = dataSource;
        Table tableAnnotation = AnnotationUtil.getAnnotation(type, Table.class);
        this.tableName = tableAnnotation.value();
        try {
            String tableSqlFromClazz = createTableSqlFromClazz(type);
            Db.use(dataSource).execute(tableSqlFromClazz);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean exist(T object) {
        return exist(WhereCondition.builder().column("id").value(object.getId()).build());
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        Entity entity = creatQueryEntity(whereConditions);
        try {
            return Db.use(dataSource).find(entity).size() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public T getById(Object id) {
        try {
            Entity entity = Db.use(dataSource).get(
                    Entity.create(tableName).set("id", id)
            );
            if (entity == null) {
                return null;
            }
            return entity.toBean(type);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<T> getAll() {
        return getAll(WhereCondition.empty());
    }

    @Override
    public List<T> getAll(WhereCondition... whereConditions) {
        Entity entity = creatQueryEntity(whereConditions);
        List<T> collection = new ArrayList<>();
        try {
            List<Entity> entities = Db.use(dataSource).find(entity);
            for (Entity res : entities) {
                JSONObject jsonObject = new JSONObject();
                Set<String> fieldNames = res.getFieldNames();
                for (String field : fieldNames) {
                    jsonObject.put(field, res.get(field));
                }
                collection.add(jsonObject.toJavaObject(type));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return collection;
    }


    @Override
    public List<T> getLike(String column, String value, Condition.LikeType likeType) {
        List<T> collection = new ArrayList<>();
        try {
            List<Entity> like = Db.use(dataSource).findLike(tableName, column, value, likeType);
            for (Entity entity : like) {
                collection.add(entity.toBean(type));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return collection;
    }

    @Override
    public List<T> page(int page, int size, WhereCondition... whereConditions) {
        Entity entity = creatQueryEntity(whereConditions);
        List<T> collection = new ArrayList<>();
        try {
            List<Entity> entities = Db.use(dataSource).page(entity, page, size);
            for (Entity res : entities) {
                JSONObject jsonObject = new JSONObject();
                Set<String> fieldNames = res.getFieldNames();
                for (String field : fieldNames) {
                    jsonObject.put(field, res.get(field));
                }
                collection.add(jsonObject.toJavaObject(type));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return collection;
    }

    @SneakyThrows
    @Override
    public void insert(T obj) {
        Entity entity = copyEntity(obj);
        try {
            Db.use(dataSource).insert(entity);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void del(WhereCondition... whereConditions) {
        Entity entity = creatQueryEntity(whereConditions);
        try {
            Db.use(dataSource).del(entity);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void delById(Object id) {
        try {
            Db.use(dataSource).del(
                    Entity.create(tableName).set("id", id)
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void update(String column, Object value, Object id) {
        if (!ClassUtil.isBasicType(value.getClass())) {
            value = JSON.toJSONString(value);
        }
        try {
            Db.use(dataSource).update(
                    Entity.create(tableName).set(column, value),
                    Entity.create(tableName).set("id", id)
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void update(T obj) throws IllegalAccessException {
        Entity entity = copyEntity(obj);
        try {
            Db.use(dataSource).update(
                    entity,
                    Entity.create(tableName).set("id", obj.getId())
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Entity copyEntity(T obj) throws IllegalAccessException {
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

    private String createTableSqlFromClazz(Class<T> type) {
        Table table = AnnotationUtil.getAnnotation(type, Table.class);
        String tableName = table.value();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("`(");
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
        stringBuilder.append("PRIMARY KEY (`id`))ENGINE=InnoDB DEFAULT CHARSET=utf8");
        return stringBuilder.toString();
    }

    private Entity creatQueryEntity(WhereCondition[] whereConditions) {
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
}
