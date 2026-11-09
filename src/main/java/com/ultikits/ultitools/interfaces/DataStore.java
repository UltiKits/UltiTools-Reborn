package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.manager.DataScope;

/**
 * Data storage interface.
 */
public interface DataStore {
    /**
     * Get the type of data storage.
     *
     * @return Data storage type
     */
    String getStoreType();

    /**
     * Ownership check for a data folder: refuses unless {@code dataFolder} belongs to a registered
     * external plugin whose scope owns {@code dataEntity}. As of 6.4.0 only the shipped stores' own
     * legacy {@code getOperator(File, Class)} methods call it.
     *
     * @param dataFolder the external plugin's data folder
     * @param dataEntity data entity class
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataFolder} matches
     *         no registered scope, or matches one that does not own {@code dataEntity}
     * @since 6.3.0
     * @deprecated It served only the {@code getOperator(File, Class)} overload removed in 6.4.0;
     *             {@link #getOperator(DataScope, Class)} checks ownership itself. Use
     *             {@link #getOperator(DataScope, Class)} instead.
     * @removeIn 6.5.0
     */
    @Deprecated(since = "6.4.0", forRemoval = true)
    default void checkOwnership(java.io.File dataFolder, Class<?> dataEntity) {
        com.ultikits.ultitools.UltiTools ultiTools = com.ultikits.ultitools.UltiTools.getInstance();
        DataScope scope = ultiTools != null && ultiTools.getPluginManager() != null
                ? ultiTools.getPluginManager().findScopeForDataFolder(dataFolder)
                : null;
        if (scope == null) {
            throw new com.ultikits.ultitools.exceptions.DataAccessException(
                    com.ultikits.ultitools.exceptions.ErrorCode.ENTITY_NOT_OWNED,
                    "Data folder is not a registered external plugin -- call UltiToolsAPI.connect(...) "
                            + "before requesting a data operator.");
        }
        if (!scope.owns(dataEntity)) {
            throw scope.refusalFor(dataEntity);
        }
    }

    /**
     * Ownership check for a plugin: refuses unless the framework's entity registry records
     * {@code plugin} as the owner of {@code dataEntity}. As of 6.4.0 only the shipped stores' own
     * legacy {@code getOperator(UltiToolsPlugin, Class)} methods call it.
     *
     * @param plugin     the requesting plugin
     * @param dataEntity data entity class
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code plugin} does not own
     *         {@code dataEntity}
     * @since 6.3.0
     * @deprecated It served only the {@code getOperator(UltiToolsPlugin, Class)} overload removed in
     *             6.4.0; {@link #getOperator(DataScope, Class)} checks ownership itself. Use
     *             {@link #getOperator(DataScope, Class)} instead.
     * @removeIn 6.5.0
     */
    @Deprecated(since = "6.4.0", forRemoval = true)
    default void checkOwnership(UltiToolsPlugin plugin, Class<?> dataEntity) {
        com.ultikits.ultitools.UltiTools ultiTools = com.ultikits.ultitools.UltiTools.getInstance();
        String owner = ultiTools != null && ultiTools.getPluginManager() != null
                ? ultiTools.getPluginManager().findOwningPlugin(dataEntity)
                : null;
        String requester = plugin.getPluginName();
        if (!java.util.Objects.equals(requester, owner)) {
            String message = owner != null
                    ? "Entity " + dataEntity.getName() + " belongs to module '" + owner + "', not '"
                            + requester + "' -- use " + owner + "'s exposed service or the EventBus instead."
                    : "Entity " + dataEntity.getName() + " is not registered to '" + requester
                            + "' -- use the owning module's exposed service or the EventBus instead.";
            throw new com.ultikits.ultitools.exceptions.DataAccessException(
                    com.ultikits.ultitools.exceptions.ErrorCode.ENTITY_NOT_OWNED, message);
        }
    }

    /**
     * Get the JDBC {@link javax.sql.DataSource} backing this store for the given scope, needing
     * no entity class up front (a connection pool needs only a JDBC URL).
     *
     * @param scope the requesting scope
     * @return the JDBC data source for that scope
     * @since 6.3.0
     */
    default javax.sql.DataSource getDataSource(DataScope scope) {
        throw new UnsupportedOperationException("This DataStore (" + getStoreType() + ") does not expose a JDBC DataSource");
    }

    /**
     * Get a data operator for the entity {@code scope} owns, refusing outright when it does not
     * (D-14). The credential is framework-minted and unforgeable ({@code DataScope}'s constructor
     * and static factories are package-private to {@code manager}), so this is the only operator
     * entry point: a caller reaching {@code UltiTools#getDataStore()} directly -- {@code public}
     * in the published jar -- still cannot obtain an operator for an entity it does not own.
     * <p>
     * Module code does not call this directly; it uses {@code UltiToolsPlugin#getDataOperator(Class)}
     * (internal modules) or {@code UltiToolsAPI.getDataOperator(JavaPlugin, Class)} (external
     * plugins), both of which pass the scope the framework minted for them. The two legacy
     * overloads taking an {@code UltiToolsPlugin} or a {@link java.io.File}, deprecated in 6.3.0,
     * were removed in 6.4.0.
     * <p>
     * Delegates to {@link #getOperatorUnchecked(DataScope, Class)}, an internal construction path
     * with no ownership check of its own, reachable only after this method's own {@code
     * scope.owns(...)} check has already passed (02-13, CR-01).
     *
     * @param scope      the requesting scope
     * @param dataEntity data entity class
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return data operator for that entity
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code scope} does not own
     *         {@code dataEntity}
     * @since 6.3.0
     */
    default <T extends BaseDataEntity<String>> DataOperator<T> getOperator(DataScope scope, Class<T> dataEntity) {
        if (!scope.owns(dataEntity)) {
            throw scope.refusalFor(dataEntity);
        }
        return getOperatorUnchecked(scope, dataEntity);
    }

    /**
     * Internal, unchecked construction path used ONLY by {@link #getOperator(DataScope, Class)},
     * called after its own {@code scope.owns(...)} check has already passed (CR-01, 02-13).
     * It builds the actual operator -- path/identity resolution, connection-pool cache, operator
     * cache, {@code transactionManagerFor(...)} wiring -- for the internal/external shape {@code
     * scope} resolves to.
     * <p>
     * This member is technically {@code public} -- Java gives interface members no narrower
     * visibility -- but it is not reachable as a bypass: nothing outside the {@code manager}
     * package can construct a {@link DataScope} to pass to it (the same unforgeability {@link
     * #getOperator(DataScope, Class)} itself relies on), so a caller able to invoke this method at
     * all already holds a scope it could just as validly pass to the checked entry point instead.
     * It is not part of the officially-extensible {@link DataStore} contract in the same sense the
     * other members are; the default throws, matching the same "backend does not support this by
     * default" posture {@link #getDataSource(DataScope)} already uses. The three backends this
     * framework ships each override it.
     *
     * @param scope      the requesting scope, already verified to own {@code dataEntity}
     * @param dataEntity data entity class
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return data operator for that entity
     * @since 6.3.0
     */
    default <T extends BaseDataEntity<String>> DataOperator<T> getOperatorUnchecked(DataScope scope, Class<T> dataEntity) {
        throw new UnsupportedOperationException(
                "This DataStore (" + getStoreType() + ") does not support DataScope-based operator resolution");
    }

    /**
     * Save all possible caches and destroy all data operation classes.
     */
    void destroyAllOperators();
}
