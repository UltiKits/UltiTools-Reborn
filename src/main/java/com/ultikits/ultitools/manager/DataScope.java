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
 * <p>
 * 框架签发的身份凭证，标识调用 {@link com.ultikits.ultitools.interfaces.DataStore} 方法的一方。
 * 与裸露的 {@link File} 不同，{@code DataScope} 无法在本包之外被构造——构造器和静态工厂方法均为
 * 包私有，只能经由 {@code PluginManager}（内部模块）或 {@code DataStoreManager} /
 * 外部插件注册路径（外部插件）获得。这使得 {@code UltiTools#getDataStore()}
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
     * {@code ownedEntities} is the result of {@code PluginManager}'s D-19 scan of the plugin's own
     * JAR (classes carrying {@code @Table}), unioned with anything declared via
     * {@code @UltiToolsModule#additionalEntities()}.
     * <p>
     * 为内部 {@link UltiToolsPlugin} 模块铸造一个 scope。{@code ownedEntities} 是
     * {@code PluginManager} 对插件自身 JAR 做 D-19 扫描（携带 {@code @Table} 的类）的结果，
     * 并与通过 {@code @UltiToolsModule#additionalEntities()} 声明的实体取并集。
     *
     * @param plugin        the internal plugin module <br> 内部插件模块
     * @param ownedEntities the entity classes this plugin owns <br> 该插件拥有的实体类
     * @return a scope identifying that plugin <br> 标识该插件的 scope
     */
    static DataScope forPlugin(UltiToolsPlugin plugin, Set<Class<?>> ownedEntities) {
        return new DataScope(plugin.getPluginName(), UltiTools.getInstance().getDataFolder(),
                ownedEntities);
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
     * <p>
     * 为本 scope 中请求 {@code dataEntity}（本 scope 并不拥有）的调用方构建拒绝信息（D-14、
     * D-15）。框架内部使用；所有 {@code getOperator} 重载都会走这唯一的来源，因此无论调用方
     * 走到哪一个重载，拒绝结果都一致。当已知某个已加载模块的 scope 拥有该实体时，消息会点名该
     * 模块——「确认属于另一个模块」；否则消息只说明该实体未向请求方注册且未知归属——
     * 「未向你注册」（D-15）。消息只携带实体的完全限定名、请求方插件名和正确路径（拥有者模块
     * 暴露的服务，或 EventBus）——绝不包含表名、列名、行数或文件路径，因此拒绝信息不能被用来
     * 枚举另一个模块的 schema。
     *
     * @param dataEntity the entity class this scope does not own <br> 本 scope 不拥有的实体类
     * @return the exception to throw <br> 应抛出的异常
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
