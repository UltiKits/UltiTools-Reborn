package com.ultikits.ultitools.manager;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * A framework-issued credential identifying the caller of a {@link com.ultikits.ultitools.interfaces.DataStore}
 * method.
 * <p>
 * Unlike a raw {@link File}, a {@code DataScope} cannot be constructed outside this package: both
 * the constructor and the static factories are package-private, so the only way to obtain one is
 * through {@code PluginManager} (internal modules) or {@code DataStoreManager} / the external
 * plugin registration path (external plugins). {@link com.ultikits.ultitools.UltiTools#getDataStore()}
 * being {@code public} in the published jar is therefore no longer a bypass: a caller outside the
 * framework has no way to mint the token {@code DataStore.getOperator(DataScope, Class)} requires.
 * <p>
 * 框架签发的身份凭证，标识调用 {@link com.ultikits.ultitools.interfaces.DataStore} 方法的一方。
 * 与裸露的 {@link File} 不同，{@code DataScope} 无法在本包之外被构造——构造器和静态工厂方法均为
 * 包私有，只能经由 {@code PluginManager}（内部模块）或 {@code DataStoreManager} /
 * 外部插件注册路径（外部插件）获得。这使得 {@link com.ultikits.ultitools.UltiTools#getDataStore()}
 * 在已发布 jar 中是 {@code public} 这一事实不再构成绕过：框架之外的调用方没有任何办法铸造
 * {@code DataStore.getOperator(DataScope, Class)} 所需要的令牌。
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
     * The entity set is left empty here — populating it from the plugin's own JAR is D-19's work
     * in a later plan, not this one.
     * <p>
     * 为内部 {@link UltiToolsPlugin} 模块铸造一个 scope。实体集合在此留空——从插件自身 JAR
     * 中扫描填充是 D-19 在后续计划里的工作，不属于本计划。
     *
     * @param plugin the internal plugin module <br> 内部插件模块
     * @return a scope identifying that plugin <br> 标识该插件的 scope
     */
    static DataScope forPlugin(UltiToolsPlugin plugin) {
        return new DataScope(plugin.getPluginName(), UltiTools.getInstance().getDataFolder(),
                Collections.emptySet());
    }

    /**
     * Mints a scope for an external Bukkit plugin borrowing the framework via
     * {@code UltiToolsAPI.connect(...)}.
     * <p>
     * 为通过 {@code UltiToolsAPI.connect(...)} 借用框架的外部 Bukkit 插件铸造一个 scope。
     *
     * @param pluginName    the external plugin's name <br> 外部插件的名称
     * @param dataFolder    the external plugin's own data folder <br> 外部插件自己的数据文件夹
     * @param ownedEntities the entity classes this plugin owns <br> 该插件拥有的实体类
     * @return a scope identifying that external plugin <br> 标识该外部插件的 scope
     */
    static DataScope forExternal(String pluginName, File dataFolder, Set<Class<?>> ownedEntities) {
        return new DataScope(pluginName, dataFolder, ownedEntities);
    }

    /**
     * Whether the given entity class belongs to this scope.
     * <p>
     * 给定的实体类是否属于该 scope。
     *
     * @param entityClass the entity class to check <br> 待检查的实体类
     * @return true if this scope owns the entity <br> 该 scope 是否拥有此实体
     */
    public boolean owns(Class<?> entityClass) {
        return ownedEntities.contains(entityClass);
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
