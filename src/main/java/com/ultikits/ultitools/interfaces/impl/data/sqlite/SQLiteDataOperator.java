package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import javax.sql.DataSource;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.impl.data.AbstractRelationalDataOperator;

import cn.hutool.core.annotation.AnnotationUtil;

/**
 * SQLite implementation of the data operator.
 * <p>
 * Extends the abstract relational operator, providing SQLite-specific
 * table creation without engine specification.
 *
 * @param <T> the entity type
 * @author wisdomme
 * @since 6.0.0
 */
public class SQLiteDataOperator<T extends AbstractDataEntity> extends AbstractRelationalDataOperator<T> {

    /**
     * Creates a new SQLite data operator.
     *
     * @param dataSource the SQLite data source
     * @param type the entity class
     */
    public SQLiteDataOperator(DataSource dataSource, Class<T> type) {
        super(dataSource, type);
    }

    @Override
    protected String createTableSqlFromClazz(Class<T> type) {
        Table table = AnnotationUtil.getAnnotation(type, Table.class);
        String tableName = table.value();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("`(");
        stringBuilder.append(buildColumnDefinitions(type));
        stringBuilder.append("PRIMARY KEY (`id`))");
        return stringBuilder.toString();
    }
}
