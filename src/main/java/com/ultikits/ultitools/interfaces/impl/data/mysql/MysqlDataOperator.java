package com.ultikits.ultitools.interfaces.impl.data.mysql;

import javax.sql.DataSource;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.impl.data.AbstractRelationalDataOperator;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * MySQL implementation of the data operator.
 * <p>
 * Extends the abstract relational operator, providing MySQL-specific
 * table creation with InnoDB engine and UTF-8 charset.
 *
 * @param <T> the entity type
 * @author wisdomme
 * @since 6.0.0
 */
public class MysqlDataOperator<T extends AbstractDataEntity> extends AbstractRelationalDataOperator<T> {

    /**
     * Creates a new MySQL data operator.
     *
     * @param dataSource the MySQL data source
     * @param type the entity class
     */
    public MysqlDataOperator(DataSource dataSource, Class<T> type) {
        super(dataSource, type);
    }

    @Override
    protected String createTableSqlFromClazz(Class<T> type) {
        Table table = ReflectionUtil.getAnnotation(type, Table.class);
        String tableName = table.value();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("`(");
        stringBuilder.append(buildColumnDefinitions(type));
        stringBuilder.append("PRIMARY KEY (`id`))ENGINE=InnoDB DEFAULT CHARSET=utf8");
        return stringBuilder.toString();
    }
}
