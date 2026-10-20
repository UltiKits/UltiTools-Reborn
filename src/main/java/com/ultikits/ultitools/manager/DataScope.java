package com.ultikits.ultitools.manager;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * A framework-issued credential identifying the caller of a {@link com.ultikits.ultitools.interfaces.DataStore}
 * method.
 * <p>
 * Unlike a raw {@link File}, a {@code DataScope} cannot be constructed outside this package: both
 * the constructor and the static factories are package-private, so the only way to obtain one is
 * through {@code PluginManager} (internal modules) or {@code DataStoreManager} / the external
 * plugin registration path (external plugins). {@code UltiTools#getDataStore()}
 * being {@code public} in the published jar is therefore no longer a bypass: a caller outside the
 * framework has no way to mint the token {@code DataStore.getOperator(DataScope, Class)} requires.
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class DataScope {

    private final String pluginName;
    private final File dataFolder;
    private final Set<Class<?>> ownedEntities;

    DataScope(String pluginName, File dataFolder, Set<Class<?>> ownedEntities) {
        this.pluginName = pluginName;
        this.dataFolder = dataFolder;
        this.ownedEntities = Collections.unmodifiableSet(new HashSet<>(ownedEntities));
    }

    /**
     * Mints a scope for an internal {@link UltiToolsPlugin} module.
     * <p>
     * {@code ownedEntities} is the result of {@code PluginManager}'s D-19 scan of the plugin's own
     * JAR (classes carrying {@code @Table}), unioned with anything declared via
     * {@code @UltiToolsModule#additionalEntities()}.
     *
     * @param plugin        the internal plugin module
     * @param ownedEntities the entity classes this plugin owns
     * @return a scope identifying that plugin
     */
    static DataScope forPlugin(UltiToolsPlugin plugin, Set<Class<?>> ownedEntities) {
        return new DataScope(plugin.getPluginName(), UltiTools.getInstance().getDataFolder(),
                ownedEntities);
    }

    /**
     * Mints a scope for an external Bukkit plugin borrowing the framework via
     * {@code UltiToolsAPI.connect(...)}.
     *
     * @param pluginName    the external plugin's name
     * @param dataFolder    the external plugin's own data folder
     * @param ownedEntities the entity classes this plugin owns
     * @return a scope identifying that external plugin
     */
    static DataScope forExternal(String pluginName, File dataFolder, Set<Class<?>> ownedEntities) {
        return new DataScope(pluginName, dataFolder, ownedEntities);
    }

    /**
     * Whether the given entity class belongs to this scope.
     *
     * @param entityClass the entity class to check
     * @return true if this scope owns the entity
     */
    public boolean owns(Class<?> entityClass) {
        return ownedEntities.contains(entityClass);
    }

    /**
     * Builds the refusal for a caller in this scope requesting {@code dataEntity}, which this
     * scope does not own (D-14, D-15). Framework-internal; the single source every
     * {@code getOperator} overload routes through so the refusal is identical regardless of which
     * one a caller reaches. When another loaded module's scope is known to own the entity, the
     * message names it -- "confirmed to belong to another module"; otherwise it says the entity is
     * not registered to the requester and no owner is known -- "not registered to you" (D-15). The
     * message carries only the entity's fully-qualified name, the requesting plugin's name, and the
     * route (the owning module's exposed service or the EventBus) -- never a table name, column
     * name, row count, or file path, so a refusal cannot be used to enumerate another module's
     * schema.
     *
     * @param dataEntity the entity class this scope does not own
     * @return the exception to throw
     */
    public DataAccessException refusalFor(Class<?> dataEntity) {
        String owner = null;
        if (UltiTools.getInstance() != null && UltiTools.getInstance().getPluginManager() != null) {
            owner = UltiTools.getInstance().getPluginManager().findOwningPlugin(dataEntity);
        }
        String message;
        if (owner != null) {
            message = "Entity " + dataEntity.getName() + " belongs to module '" + owner + "', not '"
                    + pluginName + "' -- use " + owner + "'s exposed service or the EventBus instead.";
        } else {
            message = "Entity " + dataEntity.getName() + " is not registered to '" + pluginName
                    + "' -- use the owning module's exposed service or the EventBus instead.";
        }
        return new DataAccessException(ErrorCode.ENTITY_NOT_OWNED, message);
    }

    public String getPluginName() {
        return pluginName;
    }

    public File getDataFolder() {
        return dataFolder;
    }

    public Set<Class<?>> getOwnedEntities() {
        return ownedEntities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataScope)) {
            return false;
        }
        DataScope that = (DataScope) o;
        return Objects.equals(pluginName, that.pluginName) && Objects.equals(dataFolder, that.dataFolder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginName, dataFolder);
    }
}
