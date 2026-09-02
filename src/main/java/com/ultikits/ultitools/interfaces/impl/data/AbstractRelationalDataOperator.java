package com.ultikits.ultitools.interfaces.impl.data;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
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
    /**
     * Default Gson has no bundled adapter for {@code java.time.LocalDateTime}: its reflective
     * fallback tries to reach {@code LocalDateTime}'s private fields, which JDK 9+'s module
     * system refuses without {@code --add-opens java.base/java.time}, throwing
     * {@code JsonIOException} at the first non-null {@code @Column}-mapped {@code LocalDateTime}
     * value (02-08 -- {@code AuditableDataEntity#createdAt}/{@code updatedAt} were always
     * {@code null} before this plan, so the per-field {@link #insert} / {@link #update} JSON
     * fallback below never reached this type until the lifecycle hooks started populating it).
     * Serializes to/from {@link LocalDateTime#toString()}'s ISO-8601 form, which
     * {@link LocalDateTime#parse(CharSequence)} reads back exactly.
     */
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (src, type, context) ->
                    src == null ? JsonNull.INSTANCE : new JsonPrimitive(src.toString()))
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                    json.isJsonNull() ? null : LocalDateTime.parse(json.getAsString()))
            .create();

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
        // D-10: TimeoutAwareQueryRunner reads the active transaction's remaining-time budget
        // fresh on every statement via currentTimeoutDeadlineNanos() -- this operator outlives
        // any single transaction, and transactionManager itself can be swapped out.
        this.queryRunner = new TimeoutAwareQueryRunner(this.dataSource, this::currentTimeoutDeadlineNanos);
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
     *         (including inherited fields, e.g. {@code BaseDataEntity}'s {@code id})
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
     * Supplies {@link #queryRunner}'s per-statement query timeout deadline (D-10): the active
     * transaction's remaining-time budget, or {@code null} when none is configured (no active
     * transaction, or {@code @Transactional(timeout=0)}, the default). Also used directly by
     * {@link #applyStatementTimeout} for the two {@code PreparedStatement} call sites that
     * bypass {@link #queryRunner} entirely. A method reference rather than a field capture, so
     * every call reads {@link #transactionManager}'s *current* value -- this operator outlives
     * any single transaction, and {@link #setTransactionManager} can swap the manager out.
     *
     * @return the active transaction's timeout deadline, or {@code null} if none is configured
     */
    private Long currentTimeoutDeadlineNanos() {
        return transactionManager != null ? transactionManager.getTimeoutDeadlineNanos() : null;
    }

    /**
     * Applies the same per-statement query timeout {@link #queryRunner} (a {@link
     * TimeoutAwareQueryRunner}) would, to a {@link PreparedStatement} built directly against
     * {@link #dataSource} -- the two call sites in {@link #insertAll} and {@link #updateAll}
     * that bypass {@code QueryRunner} entirely. A timeout wired only into {@link #queryRunner}'s
     * path would leave these two batch operations silently ignoring
     * {@code @Transactional(timeout=)}, exactly the gap 02-RESEARCH.md warns about.
     *
     * @param statement the statement to apply the timeout to, already prepared
     * @throws SQLException if the driver rejects the timeout value
     */
    private void applyStatementTimeout(PreparedStatement statement) throws SQLException {
        Long deadlineNanos = currentTimeoutDeadlineNanos();
        if (deadlineNanos != null) {
            statement.setQueryTimeout(TimeoutAwareQueryRunner.remainingSecondsFloored(deadlineNanos));
        }
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

    /**
     * The read handler used by {@link #getAll(WhereCondition...)}, {@link #getLike}, and
     * {@link #page}: fires {@code onLoad()} once per row it materializes. {@link #getById} and
     * {@link #delById} deliberately do *not* go through this handler -- see
     * {@link #getRawListHandler()}.
     */
    protected ResultSetHandler<List<T>> getListHandler() {
        ResultSetHandler<List<T>> raw = getRawListHandler();
        return rs -> {
            List<T> list = raw.handle(rs);
            for (T entity : list) {
                entity.onLoad();
            }
            return list;
        };
    }

    /**
     * The same row-materialization logic as {@link #getListHandler()}, without firing
     * {@code onLoad()}. Used internally by {@link #getById} (which fires {@code onLoad()} itself,
     * exactly once, on the single entity it returns) and {@link #delById} (which needs the
     * entity to fire {@code onDelete()} on, but deleting is not loading -- see 02-08-PLAN.md's
     * delete-hook-path split).
     */
    private ResultSetHandler<List<T>> getRawListHandler() {
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
     * Fetches a single row by id without firing {@code onLoad()}. Shared by {@link #getById}
     * (which fires {@code onLoad()} on the result) and {@link #delById} (which fires
     * {@code onDelete()} instead -- deleting is not loading).
     */
    private T fetchRawById(Object id) {
        String sql = "SELECT * FROM " + tableName + " WHERE id = ?";
        try {
            List<T> list = queryRunner.query(sql, getRawListHandler(), id);
            return list.isEmpty() ? null : list.get(0);
        } catch (SQLException e) {
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED,
                    "Failed to get entity by id: " + id, e);
        }
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
        T entity = fetchRawById(id);
        if (entity != null) {
            entity.onLoad();
        }
        return entity;
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
        // Fires before the fields below are read for the SQL parameters, so whatever onCreate()
        // writes (e.g. AuditableDataEntity's createdAt/createdBy) is what actually gets persisted.
        obj.onCreate();
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

    /**
     * Deletes by predicate, without loading matched rows into entities first. Consequently this
     * overload does <strong>not</strong> fire {@code onDelete()} -- there is no entity to fire it
     * on without fabricating one, and this class does not fabricate entities to satisfy a hook.
     * {@link #delById(Object)} fetches the entity first and does fire {@code onDelete()} on it
     * (02-08-PLAN.md's delete-hook-path split).
     */
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

    /**
     * Deletes by id, fetching the entity first so {@code onDelete()} can fire on it -- unlike
     * {@link #del(WhereCondition...)}, which deletes by predicate without materializing rows and
     * therefore does not fire the hook. If no row matches {@code id}, nothing is fetched and
     * {@code onDelete()} does not fire.
     */
    @Override
    public void delById(Object id) {
        T entity = fetchRawById(id);
        if (entity != null) {
            entity.onDelete();
        }
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
        // Fires before the fields below are read for the SQL parameters, so whatever onUpdate()
        // writes (e.g. AuditableDataEntity's updatedAt/updatedBy) is what actually gets
        // persisted. onUpdate() does not touch createdAt/createdBy, so an entity carrying its
        // original creation values in memory persists them unchanged here.
        obj.onUpdate();
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
            // Auto-generate UUID for entities without id, then fire onCreate() once per entity
            // in list order -- before the second loop below reads fields for the batch
            // PreparedStatement, exactly mirroring insert()'s single-entity ordering. This loop
            // builds its own statement rather than delegating to insert(), so a hook wired only
            // into insert() would silently skip this path.
            for (T entity : entities) {
                if (entity.getId() == null) {
                    entity.setId(java.util.UUID.randomUUID().toString());
                }
                entity.onCreate();
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
                // D-10: this statement bypasses queryRunner entirely, so it needs the same
                // per-statement timeout applied explicitly -- see applyStatementTimeout's javadoc.
                applyStatementTimeout(pstmt);
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
                    // D-10: this statement bypasses queryRunner entirely -- same reasoning as
                    // insertAll's identical call above.
                    applyStatementTimeout(pstmt);
                    for (T entity : entities) {
                        // Fires once per entity, in list order, before this entity's fields are
                        // read below -- this loop builds its own statement rather than
                        // delegating to update(T), so a hook wired only into update(T) would
                        // silently skip this path.
                        entity.onUpdate();
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
