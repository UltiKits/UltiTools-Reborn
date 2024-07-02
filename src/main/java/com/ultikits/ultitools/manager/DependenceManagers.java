package com.ultikits.ultitools.manager;

import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.log.LogFactory;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.ContextConfig;
import com.ultikits.ultitools.interfaces.impl.logger.BukkitLogFactory;

import lombok.Getter;
import mc.obliviate.inventory.InventoryAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Dependence managers.
 * <br>
 * 依赖管理器。
 */
public class DependenceManagers {
    @Getter
    private BukkitAudiences adventure;
    @Getter
    private AnnotationConfigApplicationContext context;
    private VersionComparator versionComparator;
    private final ClassLoader classLoader;

    public DependenceManagers(UltiTools plugin, ClassLoader classLoader) {
        this.classLoader = classLoader;
        LogFactory.setCurrentLogFactory(new BukkitLogFactory());
        initAdventure(plugin);
        initSpringContext();
        initInventoryAPI(plugin);
    }

    /**
     * Initialize adventure.
     * <br>
     * 初始化adventure。
     *
     * @param plugin plugin instance <br> 插件实例
     */
    public void initAdventure(UltiTools plugin) {
        adventure = BukkitAudiences.create(plugin);
    }

    /**
     * Initialize spring context.
     * <br>
     * 初始化spring上下文。
     */
    public void initSpringContext() {
        // Spring context initialization
        context = new AnnotationConfigApplicationContext();
        context.setClassLoader(classLoader);
        context.register(ContextConfig.class);
        context.refresh();
        context.registerShutdownHook();
    }

    /**
     * Initialize inventory API.
     * <br>
     * 初始化inventoryAPI。
     *
     * @param plugin plugin instance <br> 插件实例
     */
    public void initInventoryAPI(UltiTools plugin) {
        new InventoryAPI(plugin).init();
    }

    /**
     * Get version comparator.
     * <br>
     * 获取版本比较器。
     *
     * @return version comparator <br> 版本比较器
     */
    public VersionComparator getVersionComparator() {
        if (versionComparator == null) {
            versionComparator = new VersionComparator();
        }
        return versionComparator;
    }

    /**
     * Close adventure.
     * <br>
     * 关闭adventure。
     */
    public void closeAdventure() {
        if (adventure != null) {
            adventure.close();
        }
    }

    /**
     * Close spring context.
     * <br>
     * 关闭spring上下文。
     */
    public void closeSpringContext() {
        if (context != null) {
            context.close();
        }
    }
}
