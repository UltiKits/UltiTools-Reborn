package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.item.impl.ItemServices;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.ui.dialog.PaperDialogPresenter;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkSchedulerBean;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.scheduling.internal.PaperBackend;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import com.ultikits.ultitools.services.EconomyProvider;
import com.ultikits.ultitools.services.EmailService;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.services.GameMailService;
import com.ultikits.ultitools.services.impl.DefaultEmailService;
import com.ultikits.ultitools.services.impl.InMemeryTeleportService;
import com.ultikits.ultitools.services.impl.InMemoryNotificationService;
import com.ultikits.ultitools.services.impl.NoOpGameMailService;
import com.ultikits.ultitools.services.impl.VaultEconomyProvider;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import com.ultikits.ultitools.utils.EconomyUtils;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import lombok.Getter;
import mc.obliviate.inventory.InventoryAPI;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

/**
 * Dependence managers.
 */
@ApiStatus.Internal
public class DependenceManagers {
    @Getter
    private SimpleContainer context;
    @Getter
    private UiManager uiManager;

    public DependenceManagers(UltiTools plugin, ClassLoader classLoader) {
        this.context = new SimpleContainer();
        this.context.setClassLoader(classLoader);
        // Register the main plugin so modules can resolve Plugin/JavaPlugin dependencies
        context.registerSingleton("ultiTools", plugin);
        initScheduling(plugin);
        initInventoryAPI(plugin);
        initCoreServices(plugin);
        try {
            initUi(plugin);
        } catch (Throwable failure) {
            try {
                closeContext();
            } catch (Throwable cleanup) {
                if (!failure.equals(cleanup)) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /** Installs the framework owner before any core service can schedule work. */
    private void initScheduling(UltiTools plugin) {
        PaperSchedulerEngine engine = new PaperSchedulerEngine(plugin, plugin.getLogger(), PaperBackend.live(plugin));
        Scheduler scheduler = engine.newOwner("UltiTools");
        context.registerSingleton("frameworkScheduler", new FrameworkSchedulerBean(engine, scheduler));
        context.registerSingleton(Scheduler.class.getName(), scheduler);
        context.registerType(Scheduler.class, scheduler);
        FrameworkScheduling.install(scheduler);
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
     *
     * The framework scheduler defers the economy start-up report until the first server tick.
     */
    private void initCoreServices(UltiTools plugin) {
        // Register TeleportService
        InMemeryTeleportService teleportService = new InMemeryTeleportService();
        context.registerSingleton("inMemeryTeleportService", teleportService);
        context.registerSingleton(TeleportService.class.getName(), teleportService);

        // Register NotificationService: built on first lookup, because its Messages and Scheduler are
        // registered by the text and scheduling services, whose registration order here is not fixed.
        context.registerTypeSupplier(NotificationService.class,
                () -> new InMemoryNotificationService(context.getBean(Messages.class), context.getBean(Scheduler.class)));

        LocaleResolver localeResolver = ConfiguredLocaleResolver.forConfig(plugin::getConfig, plugin.getLogger());
        TextEngine textEngine = new TextEngine(localeResolver, plugin.getLogger(),
                UltiTools.class.getProtectionDomain().getCodeSource().getLocation(),
                plugin.getDataFolder().toPath().resolve("messages"));
        context.registerSingleton("textEngine", textEngine);
        context.registerSingleton(TextEngine.class.getName(), textEngine);
        context.registerSingleton("localeResolver", localeResolver);
        context.registerSingleton(LocaleResolver.class.getName(), localeResolver);
        Messages messages = textEngine.messagesFor(MessageKey.FRAMEWORK_NAMESPACE);
        context.registerSingleton("frameworkMessages", messages);
        context.registerSingleton(Messages.class.getName(), messages);

        // Register EmailService
        DefaultEmailService emailService = new DefaultEmailService();
        context.registerSingleton("defaultEmailService", emailService);
        context.registerSingleton(EmailService.class.getName(), emailService);

        // Register GameMailService's no-op fallback (#393). It was the one pluggable service of
        // the four that never reached a container, so `@Autowired GameMailService` had nothing to
        // resolve against -- a module could not even reach the isAvailable() check the fallback
        // exists to answer. Registered here rather than left to @Service: framework-owned classes
        // are not component-scanned, which is exactly why the annotation alone was inert.
        NoOpGameMailService gameMailService = new NoOpGameMailService();
        context.registerSingleton("noOpGameMailService", gameMailService);
        context.registerSingleton(GameMailService.class.getName(), gameMailService);

        // Register EconomyProvider (D-08/D-09, #451): the internal, @ApiStatus.Internal seam
        // EconomyUtils delegates to, following the same bean-name-plus-interface-name shape as
        // every service above. EconomyUtils.setProvider shares this exact instance so the
        // framework's façade and any future container-resolved EconomyProvider consumer see the
        // same state.
        VaultEconomyProvider economyProvider = new VaultEconomyProvider();
        context.registerSingleton("vaultEconomyProvider", economyProvider);
        context.registerSingleton(EconomyProvider.class.getName(), economyProvider);
        EconomyUtils.setProvider(economyProvider);
        // [Rule 1 fix, Codex P2, PR #463]: plugin.yml only softdepends on Vault itself, not on any
        // economy-providing plugin (e.g. EssentialsX) -- Vault is guaranteed to enable before
        // UltiTools, but an economy plugin has no declared ordering relative to UltiTools at all,
        // and commonly enables after it. Logging the one-time start-up line synchronously here,
        // inside this constructor (itself called early from UltiTools#onEnable()), would
        // permanently report NO_PROVIDER_REGISTERED for that ordinary case, even though a
        // provider registers moments later in the same server start-up. Bukkit runs every
        // plugin's entire onEnable() to completion before the first server tick, so deferring by
        // one tick -- the scheduler-based pattern UltiTools#scheduleStartupMessages() already
        // uses for its own one-time start-up lines -- guarantees every plugin that will enable
        // during this start-up already has, before this runs.
        FrameworkScheduling.scheduler().global().run(EconomyUtils::logStartupState);
        ItemServices.register(context);
    }

    /** Installs listeners eagerly: scheduling, text and item services are all registered at this point. */
    private void initUi(UltiTools plugin) {
        // Unlike lazy core services, these listeners must exist before any module can open a screen.
        uiManager = UiManager.create(context.getBean(Scheduler.class), context.getBean(Messages.class),
                context.getBean(ItemRenderer.class), plugin.getLogger(), Thread.currentThread(), new PaperDialogPresenter());
        uiManager.registerListeners(plugin);
        context.registerSingleton(UiManager.class.getName(), uiManager);
        context.registerType(UiManager.class, uiManager);
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
     * Close context.
     */
    public void closeContext() {
        Throwable uiFailure = null;
        try {
            if (context != null) {
                CommandRuntime.awaitAsyncCommands(context.getSingletonValues());
            }
            if (uiManager != null) { uiManager.close(); }
        } catch (Throwable failure) {
            uiFailure = failure;
            throw failure;
        } finally {
            closeRootContext(uiFailure);
        }
    }

    private void closeRootContext(Throwable uiFailure) {
        try {
            if (context != null) { context.close(); }
        } catch (Throwable failure) {
            if (uiFailure == null) { throw failure; }
            if (!uiFailure.equals(failure)) { uiFailure.addSuppressed(failure); }
        }
    }
}
