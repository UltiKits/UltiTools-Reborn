package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.manager.DataScope;

/**
 * Data storage interface.
 * <p>
 * 数据存储接口
 */
public interface DataStore {
    /**
     * Get the type of data storage.
     * <p>
     * 获取此数据存储的类型
     *
     * @return Data storage type <br> 数据存储类型
     */
    String getStoreType();

    /**
     * Get data operation entity class.
     * <p>
     * 获取数据操作实体类
     *
     * @param plugin     Plugin <br> 插件
     * @param dataEntity Data entity class <br> 数据实体类
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return Data operation entity <br> 数据操作实体
     * @deprecated Carries no ownership check of its own (D-14/D-18) -- a {@code DataStore}
     *             implementation that overrides this method directly must call {@link
     *             #checkOwnership(UltiToolsPlugin, Class)} as its own first statement to get the
     *             refusal; the three backends shipped by this framework all do (02-12). Framework-
     *             internal callers ({@code UltiToolsPlugin.getDataOperator}) already check
     *             ownership themselves before calling this. Use {@link #getOperator(DataScope,
     *             Class)}.
     *             <p>
     *             自身不带所有权校验（D-14/D-18）——直接覆写这个方法的 {@code DataStore}
     *             实现必须自己把 {@link #checkOwnership(UltiToolsPlugin, Class)} 作为方法体的
     *             第一条语句调用，才能得到拒绝校验；本框架自带的三个后端都是如此（02-12）。
     *             框架内部调用方（{@code UltiToolsPlugin.getDataOperator}）在调用它之前已经
     *             自行做过所有权校验。请改用 {@link #getOperator(DataScope, Class)}。
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
     * <p>
     * 获取外部插件的数据操作器，数据范围限定在插件自己的数据文件夹中。当 {@code dataFolder}
     * 解析到的已注册外部插件 scope 并不拥有 {@code dataEntity} 时，或 {@code dataFolder}
     * 完全不匹配任何已注册 scope 时，直接拒绝（D-18）——无法解析的文件夹按 fail-closed 处理，
     * 而不是不加校验地放行（D-15）。直接覆写这个方法的 {@code DataStore}（本框架自带的每个
     * 后端都是如此）不会继承这项校验；见 {@code @deprecated} 说明。
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
     *             <p>
     *             通过在方法体第一条语句调用 {@link #checkOwnership(java.io.File, Class)} 强制执行
     *             D-18 的所有权校验——直接覆写这个方法的 {@code DataStore} 实现必须调用同一个
     *             辅助方法才能得到拒绝校验；本框架自带的三个后端都是如此（02-12）。框架内部调用方
     *             （{@code UltiToolsAPI.getDataOperator}）在调用它之前已经自行做过所有权校验。
     *             请改用 {@link #getOperator(DataScope, Class)}。
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
     * <p>
     * {@link #getOperator(java.io.File, Class)} 旧重载的共享所有权校验（D-14/D-18/02-12）。既被
     * 本接口上面的 {@code default} 方法体调用，也被本框架自带的每一个后端在其
     * {@code getOperator(File, Class)} 覆写方法体的第一条语句（先于任何路径解析、缓存查找或连接池
     * 创建）调用——因此被拒绝的调用不会产生连接池、文件或缓存条目这类副作用。
     * <p>
     * 反向查找只会登记 EXTERNAL scope（D-18）——每个内部插件共享的都是框架自己的数据文件夹
     * （{@code DataScope.forPlugin}），单靠文件夹本身永远无法把它归属到某一个所有者，因此任何
     * 携带框架自身核心数据文件夹到达这里的调用，都会像任何其他未注册文件夹一样被拒绝。
     * <strong>02-13 之前，本方法把框架自己的核心数据文件夹排除在反向查找拒绝之外</strong>，理由是
     * {@link #getOperator(DataScope, Class)} 需要让 INTERNAL scope 到达自己的
     * {@code getOperator(File, Class)} 而不被二次拒绝。CR-01（02-REVIEW.md）发现那个豁免仅仅依据
     * {@code dataFolder} 的「值」来判断——而 {@code UltiTools#getDataFolder()} 在已发布 jar 中是
     * {@code public} 的，正是 D-17 自己 javadoc 里点名的、{@link DataScope} 之所以必须无法伪造的
     * 那个事实——而不是依据任何凭证或调用方身份，因此共享同一 JVM 的任何代码都能触达它。在
     * {@code MysqlDataStore} 上这是一个真实的、未经身份验证的读写绕过，直接触及另一个模块的真实表
     * （整个服务器只有一个全局 {@code DataSource}）。02-13 的修复：{@link
     * #getOperator(DataScope, Class)} 不再让 INTERNAL scope 经过本方法——它改为通过一条内部的、
     * 不带校验的构造路径直接解析（见本框架三个自带后端各自的 {@code getOperatorUnchecked}），
     * 这条路径完全不会触及本检查，因此这里也就不再需要任何豁免了。
     *
     * @param dataFolder the external plugin's data folder <br> 外部插件的数据文件夹
     * @param dataEntity data entity class <br> 数据实体类
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataFolder} matches
     *         no registered scope, or matches one that does not own {@code dataEntity} <br>
     *         如果 {@code dataFolder} 匹配不到任何已注册 scope，或匹配到的 scope 并不拥有
     *         {@code dataEntity}
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
     * <p>
     * {@code UltiToolsPlugin} 没有暴露 {@code PluginManager} 为它铸造的 {@link DataScope} 的
     * 访问器——只有注入点 {@link UltiToolsPlugin#setDataScope} 是 public 的——所以这里无法直接
     * 调用 {@code plugin} 自己的 {@code DataScope#owns(Class)}。转而查询与 {@link
     * DataScope#refusalFor(Class)} 自身构造拒绝信息时依赖的同一个
     * {@code PluginManager#findOwningPlugin(Class)} 登记表：每个 scope 拥有的实体集合都会在
     * 铸造时（D-19）记录进这张表，发生冲突时先注册者优先。本项目实测到的、跨 21 个 {@code @Table}
     * 类 / 17 个仓库的零冲突实体分布（见 02-CONTEXT.md）意味着「登记表记录的归属方与请求方自己的
     * 名字一致」和「请求方自己的 scope 拥有该实体」在本代码库迄今产生的每一种情况下都是等价的；
     * 真正发生冲突时需要 {@code UltiToolsPlugin} 自己暴露 {@link DataScope} 才能彻底解决，这超出了
     * 本次改动的范围（02-12-PLAN.md 的文件边界不包含 {@code UltiToolsPlugin.java}）。
     *
     * @param plugin     the requesting plugin <br> 请求方插件
     * @param dataEntity data entity class <br> 数据实体类
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code plugin} does not own
     *         {@code dataEntity} per the registry above <br> 如果按上述登记表 {@code plugin}
     *         并不拥有 {@code dataEntity}
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
     * <p>
     * 获取该存储针对给定 scope 的底层 JDBC {@link javax.sql.DataSource}，无需预先给出实体类
     * （连接池只需要 JDBC URL）。非 JDBC 存储（如 JSON 后端）预期保留此默认实现。
     *
     * @param scope the requesting scope <br> 请求方的 scope
     * @return the JDBC data source for that scope <br> 该 scope 对应的 JDBC 数据源
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
     * <p>
     * 获取 {@code scope} 拥有的实体的数据操作器，不拥有时直接拒绝（D-14）。该凭证由框架签发且
     * 无法伪造（{@code DataScope} 的构造器和静态工厂方法对 {@code manager} 包之外均为包私有），
     * 因此这是受支持的路径：与 {@link #getOperator(UltiToolsPlugin, Class)} 和
     * {@link #getOperator(java.io.File, Class)} 不同，没有只有框架才能签发的 scope 就无法到达
     * 这里——即便调用方直接拿到已发布 jar 中 {@code public} 的 {@code UltiTools#getDataStore()}，
     * 也无法获得一个它不拥有的实体的操作器。
     * <p>
     * 从 02-12 起，两个旧重载也会拒绝未拥有的实体——{@link #checkOwnership(UltiToolsPlugin, Class)}
     * 和 {@link #checkOwnership(java.io.File, Class)} 会作为本框架自带每个后端方法体的第一条语句
     * 运行。本段落划出的界线比过去窄了，但依然真实：本方法的凭证从构造上就无法伪造，因此正确调用
     * 它不可能出错。旧重载则是事后解析调用方——反向文件夹查找，或查询实体所有权登记表——这只在
     * 到达的 {@code DataStore} 实现真的接入了这套解析逻辑时才起保护作用；一个假想的第四个实现，
     * 如果直接覆写 {@link #getOperator(UltiToolsPlugin, Class)} 却不调用
     * {@link #checkOwnership(UltiToolsPlugin, Class)}，就会为那一个重载重新打开绕过的口子——
     * 除了约定（以及这段 javadoc）之外，没有任何机制能拦住它。本方法不存在这种缺口：没有凭证可以
     * 跳过校验，因为根本不存在一种获取凭证的方式，而这种方式在铸造时没有被校验过。
     * <p>
     * <strong>02-13（CR-01）：</strong>上面这个检查过去会把每一个 scope（无论内部还是外部）都
     * 委托给 {@link #getOperator(java.io.File, Class)} 处理——对 INTERNAL scope 而言，这意味着
     * 会走到 {@link #checkOwnership(java.io.File, Class)} 现已删除的核心文件夹豁免逻辑。现在它改为
     * 委托给 {@link #getOperatorUnchecked(DataScope, Class)}——一条自身不带所有权校验的内部构造
     * 路径，只有在本方法自己的 {@code scope.owns(...)} 检查已经通过之后才能到达——因此上面说的
     * 「无法伪造」这个保证，不再依赖另一个方法的豁免逻辑是否与它保持一致。
     *
     * @param scope      the requesting scope <br> 请求方的 scope
     * @param dataEntity data entity class <br> 数据实体类
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return data operator for that entity <br> 该实体的数据操作器
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code scope} does not own
     *         {@code dataEntity} <br> 如果 {@code scope} 不拥有 {@code dataEntity}
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
     * <p>
     * 仅供 {@link #getOperator(DataScope, Class)} 内部使用的、不带校验的构造路径，只有在它自己的
     * {@code scope.owns(...)} 检查已经通过之后才会被调用（CR-01，02-13）。构造操作器本身——路径/
     * 身份解析、连接池缓存、操作器缓存、{@code transactionManagerFor(...)} 装配——与旧重载对
     * {@code scope} 所解析出的内部/外部两种形态一直以来的做法完全相同；变化的只是
     * {@link #getOperator(DataScope, Class)} 不再通过公开的、曾经对所有权豁免的
     * {@link #getOperator(java.io.File, Class)} 重载来到达这段构造逻辑。
     * <p>
     * 这个成员在技术上是 {@code public} 的——Java 不允许接口成员拥有更窄的可见性——但它并不构成
     * 绕过口子：{@code manager} 包之外没有任何代码能够构造出一个 {@link DataScope} 传给它（与
     * {@link #getOperator(DataScope, Class)} 自身依赖的不可伪造性完全相同），因此任何能够调用这个
     * 方法的调用方，手上本就已经握着一个同样能合法传给受校验入口的 scope。它不属于
     * {@link DataStore} 正式对外开放的扩展契约那一部分；默认实现直接抛出，与
     * {@link #getDataSource(DataScope)} 已经使用的「后端默认不支持」姿态一致。本框架自带的三个
     * 后端各自都覆写了它。
     *
     * @param scope      the requesting scope, already verified to own {@code dataEntity} <br>
     *                   请求方的 scope，已确认拥有 {@code dataEntity}
     * @param dataEntity data entity class <br> 数据实体类
     * @param <T>        Must inherit {@link BaseDataEntity}
     * @return data operator for that entity <br> 该实体的数据操作器
     * @since 6.3.0
     */
    default <T extends BaseDataEntity<String>> DataOperator<T> getOperatorUnchecked(DataScope scope, Class<T> dataEntity) {
        throw new UnsupportedOperationException(
                "This DataStore (" + getStoreType() + ") does not support DataScope-based operator resolution");
    }

    /**
     * Save all possible caches and destroy all data operation classes.
     * <p>
     * 保存所有可能的缓存并销毁所有的数据操作类
     */
    void destroyAllOperators();
}
