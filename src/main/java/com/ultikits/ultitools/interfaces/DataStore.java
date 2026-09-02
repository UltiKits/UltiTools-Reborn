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
     * Get data operation entity class.
     *
     * @param plugin     Plugin
     * @param dataEntity Data entity class
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return Data operation entity
     * @deprecated Carries no ownership check of its own (D-14/D-18) -- a {@code DataStore}
     *             implementation that overrides this method directly must call {@link
     *             #checkOwnership(UltiToolsPlugin, Class)} as its own first statement to get the
     *             refusal; the three backends shipped by this framework all do (02-12). Framework-
     *             internal callers ({@code UltiToolsPlugin.getDataOperator}) already check
     *             ownership themselves before calling this. Use {@link #getOperator(DataScope,
     *             Class)}.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity);

    /**
     * Get data operator for an external plugin, scoped to the plugin's own data folder. Refuses
     * outright (D-18) when {@code dataFolder} resolves to a registered external plugin's scope
     * that does not own {@code dataEntity}, or when {@code dataFolder} matches no registered
     * scope at all -- an unresolvable folder fails closed rather than proceeding unchecked (D-15).
     * A {@code DataStore} that overrides this method directly (as every backend shipped by this
     * framework does) does not inherit this check; see the {@code @deprecated} note.
     *
     * @param dataFolder the external plugin's data folder (e.g., plugins/MyPlugin/)
     * @param dataEntity data entity class
     * @param <T> entity type
     * @return data operator
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataFolder} matches
     *         no registered scope, or matches one that does not own {@code dataEntity}
     * @since 6.2.2
     * @deprecated Enforces D-18's ownership check via {@link #checkOwnership(java.io.File, Class)}
     *             as its own first statement -- a {@code DataStore} implementation that overrides
     *             this method directly must call the same helper to get the refusal; the three
     *             backends shipped by this framework all do (02-12). Framework-internal callers
     *             ({@code UltiToolsAPI.getDataOperator}) already check ownership themselves before
     *             calling this. Use {@link #getOperator(DataScope, Class)}.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    default <T extends BaseDataEntity<String>> DataOperator<T> getOperator(java.io.File dataFolder, Class<T> dataEntity) {
        checkOwnership(dataFolder, dataEntity);
        throw new UnsupportedOperationException("This DataStore does not support external plugin data storage");
    }

    /**
     * Shared ownership check for the {@link #getOperator(java.io.File, Class)} legacy overload
     * (D-14/D-18/02-12). Called both by this interface's own {@code default} body above and, as
     * the first statement (before any path resolution, cache lookup, or connection-pool creation),
     * by the {@code getOperator(File, Class)} override of every backend this framework ships --
     * so a refused call has not created a pool, a file, or a cache entry as a side effect.
     * <p>
     * The reverse lookup only ever registers EXTERNAL scopes (D-18) -- every internal plugin
     * shares the framework's own data folder ({@code DataScope.forPlugin}), so that one folder can
     * never be attributed to a single owner by folder alone, and any call reaching here with the
     * framework's own core data folder is refused exactly like any other unregistered folder.
     * <strong>Before 02-13 this method exempted the framework's own core data folder from the
     * reverse-lookup refusal</strong>, on the reasoning that {@link #getOperator(DataScope, Class)}
     * needed to reach an INTERNAL scope's own {@code getOperator(File, Class)} without being
     * refused a second time. CR-01 (02-REVIEW.md) found that exemption was keyed purely on the
     * <em>value</em> of {@code dataFolder} -- and {@code UltiTools#getDataFolder()} is {@code
     * public} in the published jar, the exact fact D-17's own javadoc names as the reason {@link
     * DataScope} had to be unforgeable -- not on any credential or caller identity, so any code
     * sharing the JVM could reach it. On {@code MysqlDataStore} this was a genuine,
     * unauthenticated read/write bypass onto another module's real table (there is exactly one
     * global {@code DataSource} for the whole server). 02-13's fix: {@link
     * #getOperator(DataScope, Class)} no longer routes an INTERNAL scope through this method at
     * all -- it resolves directly via an internal, unchecked construction path (see {@code
     * getOperatorUnchecked} on the framework's three shipped backends) that never touches this
     * check, so no exemption is needed here anymore.
     *
     * @param dataFolder the external plugin's data folder
     * @param dataEntity data entity class
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataFolder} matches
     *         no registered scope, or matches one that does not own {@code dataEntity}
     * @since 6.3.0
     */
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
     * Shared ownership check for the {@link #getOperator(UltiToolsPlugin, Class)} legacy overload
     * (D-14/D-18/02-12), the counterpart to {@link #checkOwnership(java.io.File, Class)} above for
     * the overload that already carries a {@link UltiToolsPlugin} identity directly rather than a
     * {@link java.io.File} to reverse-resolve. Called by every backend this framework ships as the
     * first statement of their own {@code getOperator(UltiToolsPlugin, Class)} override, before
     * any path resolution, cache lookup, or connection-pool creation.
     * <p>
     * {@code UltiToolsPlugin} exposes no accessor for the {@link DataScope} {@code PluginManager}
     * mints for it -- only {@link UltiToolsPlugin#setDataScope}, the injection point, is public --
     * so this cannot call {@code plugin}'s own {@code DataScope#owns(Class)} directly. It instead
     * consults the same {@code PluginManager#findOwningPlugin(Class)} registry {@link
     * DataScope#refusalFor(Class)} itself already treats as authoritative for message-building:
     * every scope's owned-entity set is recorded there at minting time (D-19), first-registration-
     * wins on a collision. This project's measured, zero-collision entity population across 21
     * {@code @Table} classes / 17 repositories (02-CONTEXT.md) means "the registry's recorded
     * owner equals the requester's own name" and "the requester's own scope owns the entity" agree
     * in every case this codebase has ever produced; a genuine collision would need {@link
     * UltiToolsPlugin} to expose its own {@link DataScope} to close fully, which is out of this
     * change's scope (02-12-PLAN.md's file boundary excludes {@code UltiToolsPlugin.java}).
     *
     * @param plugin     the requesting plugin
     * @param dataEntity data entity class
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code plugin} does not own
     *         {@code dataEntity} per the registry above
     * @since 6.3.0
     */
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
     * and static factories are package-private to {@code manager}), so this is the supported path:
     * unlike {@link #getOperator(UltiToolsPlugin, Class)} and {@link #getOperator(java.io.File,
     * Class)}, it cannot be reached without a scope only the framework can issue, so a caller
     * reaching {@code UltiTools#getDataStore()} directly -- {@code public} in the published jar --
     * still cannot obtain an operator for an entity it does not own.
     * <p>
     * As of 02-12, the two legacy overloads also refuse an unowned entity -- {@link
     * #checkOwnership(UltiToolsPlugin, Class)} and {@link #checkOwnership(java.io.File, Class)} run
     * as the first statement of every backend this framework ships. The distinction this
     * paragraph draws is narrower than it once was, but still real: this method's credential is
     * unforgeable by construction, so calling it correctly is impossible to get wrong. The legacy
     * overloads instead resolve the caller after the fact -- a reverse folder lookup, or an
     * entity-ownership registry consult -- which only protects a caller when the {@code DataStore}
     * implementation it reaches has actually wired that resolution in; a hypothetical fourth
     * implementation that overrides {@link #getOperator(UltiToolsPlugin, Class)} directly without
     * calling {@link #checkOwnership(UltiToolsPlugin, Class)} would reopen the bypass for that one
     * overload, with nothing but convention (and this javadoc) stopping it. No such gap exists for
     * this method: there is no credential to skip checking, because there is no way to obtain one
     * that was not already checked at minting time.
     * <p>
     * <strong>02-13 (CR-01):</strong> the check above used to delegate to {@link
     * #getOperator(java.io.File, Class)} for every scope, internal or external -- which for an
     * INTERNAL scope meant reaching {@link #checkOwnership(java.io.File, Class)}'s now-removed
     * core-folder exemption. It now delegates to {@link #getOperatorUnchecked(DataScope, Class)}
     * instead, an internal construction path with no ownership check of its own, reachable only
     * after this method's own {@code scope.owns(...)} check has already passed -- so the
     * unforgeability guarantee above is no longer contingent on a second method's exemption logic
     * agreeing with it.
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
     * Building the actual operator -- path/identity resolution, connection-pool cache, operator
     * cache, {@code transactionManagerFor(...)} wiring -- is exactly what the legacy overloads
     * have always done for the internal/external shape {@code scope} resolves to; what changed is
     * that {@link #getOperator(DataScope, Class)} no longer reaches that construction logic via
     * the public, previously ownership-exempt {@link #getOperator(java.io.File, Class)} overload.
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
