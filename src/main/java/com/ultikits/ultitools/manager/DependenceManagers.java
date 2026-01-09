package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.services.EmailService;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.services.impl.DefaultEmailService;
import com.ultikits.ultitools.services.impl.InMemeryTeleportService;
import com.ultikits.ultitools.services.impl.InMemoryNotificationService;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import lombok.Getter;
import mc.obliviate.inventory.InventoryAPI;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;

/**
 * Dependence managers.
 * <br>
 * 依赖管理器。
 */
public class DependenceManagers {
    @Getter
    private BukkitAudiences adventure;
    @Getter
    private SimpleContainer context;

    public DependenceManagers(UltiTools plugin, ClassLoader classLoader) {
        this.context = new SimpleContainer();
        this.context.setClassLoader(classLoader);
        initAdventure(plugin);
        initInventoryAPI(plugin);
        initCoreServices();
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
     * Initialize core services provided by UltiTools-API.
     * <br>
     * 初始化UltiTools-API提供的核心服务。
     */
    private void initCoreServices() {
        // Register TeleportService
        InMemeryTeleportService teleportService = new InMemeryTeleportService();
        context.registerSingleton("inMemeryTeleportService", teleportService);
        context.registerSingleton(TeleportService.class.getName(), teleportService);

        // Register NotificationService
        InMemoryNotificationService notificationService = new InMemoryNotificationService();
        context.registerSingleton("inMemoryNotificationService", notificationService);
        context.registerSingleton(NotificationService.class.getName(), notificationService);

        // Register EmailService
        DefaultEmailService emailService = new DefaultEmailService();
        context.registerSingleton("defaultEmailService", emailService);
        context.registerSingleton(EmailService.class.getName(), emailService);
    }

    /**
     * Get version comparator.
     * <br>
     * 获取版本比较器。
     *
     * @return version comparator / 版本比较器
     */
    public java.util.Comparator<String> getVersionComparator() {
        return VersionComparatorUtil.COMPARATOR;
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
     * Close context.
     * <br>
     * 关闭容器上下文。
     */
    public void closeContext() {
        if (context != null) {
            context.close();
        }
    }
}
