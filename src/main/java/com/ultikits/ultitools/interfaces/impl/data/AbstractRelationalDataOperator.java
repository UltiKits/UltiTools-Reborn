package com.ultikits.ultitools.interfaces.impl.data;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import com.google.gson.Gson;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.utils.BasicTypeUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;

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
public abstract class AbstractRelationalDataOperator<T extends BaseDataEntity<String>> implements DataOperator<T> {

    private static final Logger LOGGER = Logger.getLogger(AbstractRelationalDataOperator.class.getName());
    private static final Gson GSON = new Gson();

    protected final Class<T> type;
    protected final DataSource dataSource;
    protected final String tableName;
    protected final QueryRunner queryRunner;
    protected TransactionManager transactionManager;
    /**
     * The entity's own {@code @Column} names, reflected once at construction. The allow-list
     * {@link #validateColumn(String)} checks every WHERE-clause column identifier against
     * before it is concatenated into SQL text (T-02-SQLI-1).
     */
    private final Set<String> knownColumns;

    /**
     * Creates a new data operator.
     *
     * @param dataSource the data source to use
     * @param type the entity class
     */
    protected AbstractRelationalDataOperator(DataSource dataSource, Class<T> type) {
        this.type = type;
        // Wrap for transaction awareness — when TransactionManager has an active
        // transaction, getConnection() returns the transaction's connection instead
        // of a fresh auto-commit one.
        this.dataSource = new TransactionAwareDataSource(dataSource, () -> this.transactionManager);
        this.queryRunner = new QueryRunner(this.dataSource);
        Table tableAnnotation = ReflectionUtil.getAnnotation(type, Table.class);
        if (tableAnnotation == null) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Entity class " + type.getName() + " must have @Table annotation");
        }
        this.tableName = tableAnnotation.value();
        this.knownColumns = buildKnownColumns(type);
        initializeTable();
    }

    /**
     * Reflects the entity's {@code @Column} mappings once, for {@link #validateColumn(String)}'s
     * allow-list. This mirrors the same reflected metadata {@link #buildColumnDefinitions(Class)}
     * and {@link #getColumnMappings()} already read from the entity class — a dedicated pass over
     * it, not a second scan of anything already cached.
     *
     * @param entityType the entity class
     * @return an unmodifiable set of every {@code @Column} SQL name declared on {@code entityType}
     *         (including inherited fields, e.g. {@code AbstractDataEntity}'s {@code id})
     */
    private static Set<String> buildKnownColumns(Class<?> entityType) {
        Set<String> columns = new HashSet<>();
        for (Field field : ReflectionUtil.getFields(entityType)) {
            if (field.isAnnotationPresent(Column.class)) {
                columns.add(field.getAnnotation(Column.class).value());
            }
        }
        return Collections.unmodifiableSet(columns);
    }

    /**
     * Refuses a column identifier that is not among the entity's own reflected {@code @Column}
     * mappings, before it can be concatenated into SQL text (T-02-SQLI-1). Called from
     * {@link #appendConditions} on behalf of all four relational WHERE builders.
     *
     * @param column the column name from a caller-supplied {@link WhereCondition}
     * @throws DataAccessException if {@code column} is not a known column of {@link #type}
     */
    private void validateColumn(String column) {
        if (!knownColumns.contains(column)) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Unknown column '" + column + "' for entity " + type.getName()
                            + " -- it is not among the entity's @Column mappings.");
        }
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
     * Initializes the database table.
     */
    private void initializeTable() {
        try {
            String createTableSql = createTableSqlFromClazz(type);
            queryRunner.update(createTableSql);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to create table: " + tableName, e);
        }
    }

    protected ResultSetHandler<List<T>> getListHandler() {
        // Build mappings from SQL column names to Java field names and boolean detection.
        // Gson matches JSON keys to Java field names, so we must use field names (camelCase)
        // as map keys, not SQL column names (snake_case).
        Map<String, String> columnToFieldName = new LinkedHashMap<>();
        Map<String, Boolean> booleanColumns = new LinkedHashMap<>();
        for (Field f : ReflectionUtil.getFields(type)) {
            if (f.isAnnotationPresent(Column.class)) {
                Column col = f.getAnnotation(Column.class);
                // Locale.ROOT：土耳其语/阿塞拜疆语 locale 下 'I' 会折成无点的 'ı'，
                // 建映射与查映射只要有一边跟着系统 locale 走，列名就对不上。
                String sqlName = col.value().toLowerCase(Locale.ROOT);
                columnToFieldName.put(sqlName, f.getName());
                if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                    booleanColumns.put(sqlName, true);
                }
            }
        }

        return rs -> {
            List<T> list = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    String colName = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                    Object value = rs.getObject(i);
                    // SQLite stores BOOLEAN as INTEGER (0/1); convert for Gson compatibility
                    if (value instanceof Number && booleanColumns.containsKey(colName)) {
                        value = ((Number) value).intValue() != 0;
                    }
                    // Use Java field name as key so Gson can match it during deserialization
                    String fieldName = columnToFieldName.getOrDefault(colName, colName);
                    map.put(fieldName, value);
                }
                String json = GSON.toJson(map);
                list.add(GSON.fromJson(json, type));
            }
            return list;
        };
    }

    /**
     * Maps a {@link Comparison} to its SQL operator fragment (including the placeholder).
     * <p>
     * Shared by all four relational WHERE builders ({@link #exist(WhereCondition...)},
     * {@link #getAll(WhereCondition...)}, {@link #page(int, int, WhereCondition...)},
     * {@link #del(WhereCondition...)}) so a given {@code Comparison} means the same thing on
     * every one of them, and on {@code SimpleJsonDataOperator}'s JSON backend. There is no
     * silent fallback to equality: an unhandled constant throws rather than being misread as
     * {@code EQUAL}, since a silent default is exactly how this defect stayed invisible before.
     *
     * @param comparison the comparison operator
     * @return the SQL fragment, e.g. {@code " > ?"}
     */
    private String sqlOperatorFor(Comparison comparison) {
        switch (comparison) {
            case EQUAL:
                return " = ?";
            case GREATER:
                return " > ?";
            case LESS:
                return " < ?";
            case INCLUDE:
            case STARTSWITH:
            case ENDSWITH:
                return " LIKE ?";
            default:
                throw new IllegalArgumentException("Unhandled comparison: " + comparison);
        }
    }

    /**
     * Wraps a condition's value with the {@code %} wildcards its {@link Comparison} implies,
     * matching {@code SimpleJsonDataOperator#conditionCal}'s existing placement exactly:
     * {@code INCLUDE} wraps both sides, {@code STARTSWITH} appends a trailing wildcard,
     * {@code ENDSWITH} prepends a leading wildcard. {@code EQUAL}/{@code GREATER}/{@code LESS}
     * pass the value through unchanged.
     *
     * @param condition the condition supplying the comparison and the raw value
     * @return the value to bind as the SQL parameter
     */
    private Object likeWrappedValue(WhereCondition condition) {
        Object value = condition.getValue();
        switch (condition.getComparison()) {
            case INCLUDE:
                return "%" + value + "%";
            case STARTSWITH:
                return value + "%";
            case ENDSWITH:
                return "%" + value;
            default:
                return value;
        }
    }

    /**
     * Appends a {@code WHERE} clause built from {@code conditions} to {@code sql} and their
     * bound values to {@code params}, routing every column through {@link #sqlOperatorFor} so
     * the four relational builders cannot diverge in how they honor a {@link Comparison} again.
     * <p>
     * When {@code skipEmpty} is {@code true}, conditions whose {@code WhereCondition#isEmpty()}
     * is {@code true} are skipped — {@link #getAll(WhereCondition...)}'s pre-existing behavior,
     * preserved here rather than changed. {@link #exist(WhereCondition...)},
     * {@link #page(int, int, WhereCondition...)}, and {@link #del(WhereCondition...)} do not
     * skip empty conditions; that asymmetry already existed before this method and is not
     * resolved by this change (see 02-03-SUMMARY.md).
     *
     * @param sql        the SQL being built; must already hold the statement up to (not
     *                   including) the WHERE clause
     * @param params     the parameter list to append bound values to, in SQL order
     * @param conditions the conditions to render; must be non-null and non-empty
     * @param skipEmpty  whether to skip conditions whose {@code isEmpty()} is true
     */
    private void appendConditions(StringBuilder sql, List<Object> params, WhereCondition[] conditions,
                                   boolean skipEmpty) {
        boolean first = true;
        for (WhereCondition condition : conditions) {
            if (skipEmpty && condition.isEmpty()) {
                continue;
            }
            validateColumn(condition.getColumn());
            if (first) {
                sql.append(" WHERE ");
                first = false;
            } else {
                sql.append(" AND ");
            }
            sql.append(condition.getColumn()).append(sqlOperatorFor(condition.getComparison()));
            params.add(likeWrappedValue(condition));
        }
    }

    @Override
    public boolean exist(T object) {
        return exist(WhereCondition.builder().column("id").value(object.getId()).build());
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(tableName);
        List<Object> params = new ArrayList<>();
        if (whereConditions != null && whereConditions.length > 0) {
            appendConditions(sql, params, whereConditions, false);
        }
        try {
            Long count = queryRunner.query(sql.toString(), new ScalarHandler<>(), params.toArray());
            return count != null && count > 0;
        } catch (SQLException e) {
            LOGGER.warning("Failed to check existence: " + e.getMessage());
            return false;
        }
    }

    @Override
    public T getById(Object id) {
        String sql = "SELECT * FROM " + tableName + " WHERE id = ?";
        try {
            List<T> list = queryRunner.query(sql, getListHandler(), id);
            return list.isEmpty() ? null : list.get(0);
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
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName);
        List<Object> params = new ArrayList<>();
        if (whereConditions != null && whereConditions.length > 0) {
            appendConditions(sql, params, whereConditions, true);
        }
        try {
            return queryRunner.query(sql.toString(), getListHandler(), params.toArray());
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to get all entities", e);
        }
    }

    @Override
    public List<T> getLike(String column, String value, LikeType likeType) {
        String sql = "SELECT * FROM " + tableName + " WHERE " + column + " LIKE ?";
        String likeValue = value;
        switch (likeType) {
            case START:
                likeValue = value + "%";
                break;
            case END:
                likeValue = "%" + value;
                break;
            case CONTAINS:
                likeValue = "%" + value + "%";
                break;
        }
        try {
            return queryRunner.query(sql, getListHandler(), likeValue);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to query with LIKE", e);
        }
    }

    @Override
    public List<T> page(int page, int size, WhereCondition... whereConditions) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName);
        List<Object> params = new ArrayList<>();
        if (whereConditions != null && whereConditions.length > 0) {
            appendConditions(sql, params, whereConditions, false);
        }
        sql.append(" LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);
        try {
            return queryRunner.query(sql.toString(), getListHandler(), params.toArray());
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to page entities", e);
        }
    }

    @Override
    public void insert(T obj) {
        // Auto-generate UUID for id if not set
        if (obj.getId() == null) {
            obj.setId(java.util.UUID.randomUUID().toString());
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder values = new StringBuilder(") VALUES (");
        List<Object> params = new ArrayList<>();
        Field[] fields = ReflectionUtil.getFields(obj.getClass());
        boolean first = true;
        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                Column column = field.getAnnotation(Column.class);
                if (!first) {
                    sql.append(", ");
                    values.append(", ");
                }
                sql.append("`").append(column.value()).append("`");
                values.append("?");
                try {
                    Object value = field.get(obj);
                    if (value != null && !BasicTypeUtil.isBasicType(field.getType())) {
                        String jsonString = GSON.toJson(value);
                        if (jsonString.startsWith("\"") && jsonString.endsWith("\"")) {
                            jsonString = jsonString.substring(1, jsonString.length() - 1);
                        }
                        value = jsonString;
                    }
                    params.add(value);
                } catch (IllegalAccessException e) {
                    throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                            "Failed to access entity fields", e);
                }
                first = false;
            }
        }
        sql.append(values).append(")");
        try {
            queryRunner.update(sql.toString(), params.toArray());
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to insert entity", e);
        }
    }

    @Override
    public void del(WhereCondition... whereConditions) {
        // T-02-TAM-1 / SILENT-01 / D-12: refuse before any SQL text is built and before any
        // QueryRunner interaction. The varargs no-arg call produces a zero-length array, not
        // null -- both shapes are refused here. Stateless: every call re-checks, so this is
        // not a one-shot latch and cannot partially execute on a retry.
        if (whereConditions == null || whereConditions.length == 0) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to delete every row of table '" + tableName + "' with no WhereCondition. "
                            + "Pass an explicit condition, or use a dedicated full-table operation "
                            + "if one genuinely exists.");
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(tableName);
        List<Object> params = new ArrayList<>();
        appendConditions(sql, params, whereConditions, false);
        try {
            queryRunner.update(sql.toString(), params.toArray());
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to delete entities", e);
        }
    }

    @Override
    public void delById(Object id) {
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try {
            queryRunner.update(sql, id);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to delete entity by id: " + id, e);
        }
    }

    @Override
    public void update(String column, Object value, Object id) {
        if (value != null && !BasicTypeUtil.isBasicType(value.getClass())) {
            value = GSON.toJson(value);
        }
        String sql = "UPDATE " + tableName + " SET " + column + " = ? WHERE id = ?";
        try {
            queryRunner.update(sql, value, id);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to update column: " + column, e);
        }
    }

    @Override
    public void update(T obj) throws IllegalAccessException {
        StringBuilder sql = new StringBuilder("UPDATE ").append(tableName).append(" SET ");
        List<Object> params = new ArrayList<>();
        Field[] fields = ReflectionUtil.getFields(obj.getClass());
        boolean first = true;
        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                Column column = field.getAnnotation(Column.class);
                if (!first) {
                    sql.append(", ");
                }
                sql.append("`").append(column.value()).append("` = ?");
                Object value = field.get(obj);
                if (value != null && !BasicTypeUtil.isBasicType(field.getType())) {
                    String jsonString = GSON.toJson(value);
                    if (jsonString.startsWith("\"") && jsonString.endsWith("\"")) {
                        jsonString = jsonString.substring(1, jsonString.length() - 1);
                    }
                    value = jsonString;
                }
                params.add(value);
                first = false;
            }
        }
        sql.append(" WHERE id = ?");
        params.add(obj.getId());
        try {
            queryRunner.update(sql.toString(), params.toArray());
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to update entity", e);
        }
    }

    // ===== Transaction support =====

    @Override
    public <R> R transaction(Callable<R> action) throws Exception {
        if (transactionManager == null) {
            return action.call();
        }
        transactionManager.begin();
        try {
            R result = action.call();
            transactionManager.commit();
            return result;
        } catch (Exception e) {
            transactionManager.rollback();
            throw e;
        }
    }

    @Override
    public void transaction(Runnable action) {
        try {
            transaction((Callable<Void>) () -> {
                action.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                    "Transaction failed", e);
        }
    }

    // ===== JDBC batch operations =====

    @Override
    public void insertAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        transaction(() -> {
            // Auto-generate UUID for entities without id
            for (T entity : entities) {
                if (entity.getId() == null) {
                    entity.setId(java.util.UUID.randomUUID().toString());
                }
            }
            List<ColumnMapping> columns = getColumnMappings();
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
            StringBuilder placeholders = new StringBuilder(") VALUES (");
            boolean first = true;
            for (ColumnMapping col : columns) {
                if (!first) {
                    sql.append(", ");
                    placeholders.append(", ");
                }
                sql.append("`").append(col.columnName).append("`");
                placeholders.append("?");
                first = false;
            }
            sql.append(placeholders).append(")");

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                for (T entity : entities) {
                    int idx = 1;
                    for (ColumnMapping col : columns) {
                        Object value = col.field.get(entity);
                        if (value != null && !BasicTypeUtil.isBasicType(col.field.getType())) {
                            String json = GSON.toJson(value);
                            if (json.startsWith("\"") && json.endsWith("\"")) {
                                json = json.substring(1, json.length() - 1);
                            }
                            value = json;
                        }
                        pstmt.setObject(idx++, value);
                    }
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            } catch (SQLException | IllegalAccessException e) {
                throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                        "Batch insert failed", e);
            }
        });
    }

    @Override
    public void updateAll(List<T> entities) throws IllegalAccessException {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        try {
            transaction((Callable<Void>) () -> {
                List<ColumnMapping> columns = getColumnMappings();
                StringBuilder sql = new StringBuilder("UPDATE ").append(tableName).append(" SET ");
                boolean first = true;
                for (ColumnMapping col : columns) {
                    if (!first) {
                        sql.append(", ");
                    }
                    sql.append("`").append(col.columnName).append("` = ?");
                    first = false;
                }
                sql.append(" WHERE id = ?");

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                    for (T entity : entities) {
                        int idx = 1;
                        for (ColumnMapping col : columns) {
                            Object value = col.field.get(entity);
                            if (value != null && !BasicTypeUtil.isBasicType(col.field.getType())) {
                                String json = GSON.toJson(value);
                                if (json.startsWith("\"") && json.endsWith("\"")) {
                                    json = json.substring(1, json.length() - 1);
                                }
                                value = json;
                            }
                            pstmt.setObject(idx++, value);
                        }
                        pstmt.setObject(idx, entity.getId());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                } catch (SQLException | IllegalAccessException e) {
                    throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                            "Batch update failed", e);
                }
                return null;
            });
        } catch (IllegalAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED,
                    "Batch update transaction failed", e);
        }
    }

    // ===== Column mapping helper =====

    private static class ColumnMapping {
        final String columnName;
        final Field field;

        ColumnMapping(String columnName, Field field) {
            this.columnName = columnName;
            this.field = field;
        }
    }

    private List<ColumnMapping> getColumnMappings() {
        List<ColumnMapping> mappings = new ArrayList<>();
        for (Field field : ReflectionUtil.getFields(type)) {
            if (field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                Column col = field.getAnnotation(Column.class);
                mappings.add(new ColumnMapping(col.value(), field));
            }
        }
        return mappings;
    }

    // ===== Table creation (abstract) =====

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
        Field[] fields = ReflectionUtil.getFields(type);
        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                field.setAccessible(true);
                Column column = ReflectionUtil.getAnnotation(field, Column.class);
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
