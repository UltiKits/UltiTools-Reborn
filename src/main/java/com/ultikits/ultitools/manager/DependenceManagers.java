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
import org.jetbrains.annotations.ApiStatus;

/**
 * Dependence managers.
 */
@ApiStatus.Internal
public class DependenceManagers {
    @Getter
    private BukkitAudiences adventure;
    @Getter
    private SimpleContainer context;

    public DependenceManagers(UltiTools plugin, ClassLoader classLoader) {
        this.context = new SimpleContainer();
        this.context.setClassLoader(classLoader);
        // Register the main plugin so modules can resolve Plugin/JavaPlugin dependencies
        context.registerSingleton("ultiTools", plugin);
        initAdventure(plugin);
        initInventoryAPI(plugin);
        initCoreServices();
    }

    /**
     * Initialize adventure.
     *
     * @param plugin plugin instance
     */
    public void initAdventure(UltiTools plugin) {
        adventure = BukkitAudiences.create(plugin);
    }

    /**
     * Initialize inventory API.
     *
     * @param plugin plugin instance
     */
    public void initInventoryAPI(UltiTools plugin) {
        new InventoryAPI(plugin).init();
    }

    /**
     * Initialize core services provided by UltiTools-API.
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
     *
     * @return version comparator
     */
    public java.util.Comparator<String> getVersionComparator() {
        return VersionComparatorUtil.COMPARATOR;
    }

    /**
     * Close adventure.
     */
    public void closeAdventure() {
        if (adventure != null) {
            adventure.close();
        }
    }

    /**
     * Close context.
     */
    public void closeContext() {
        if (context != null) {
            context.close();
        }
    }
}
