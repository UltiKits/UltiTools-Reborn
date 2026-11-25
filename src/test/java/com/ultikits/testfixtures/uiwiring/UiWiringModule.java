package com.ultikits.testfixtures.uiwiring;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.ui.UiService;
import java.util.ArrayList;
import java.util.List;

/** Real module entry point, loaded from a test-owned JAR rather than a nested class. */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.uiwiring")
public class UiWiringModule extends UltiToolsPlugin {
    public static UiWiringModule constructed;
    public static String failurePoint;
    public final List<String> events = new ArrayList<>();
    public Runnable hookObserver = () -> { };
    public Runnable destroyObserver = () -> { };
    @Autowired public UiService ui;

    public UiWiringModule() {
        remember(this);
    }

    private static void remember(UiWiringModule module) { constructed = module; }

    @PostConstruct
    public void assembled() {
        events.add("injected:" + ui.owner());
        if ("assembly".equals(failurePoint)) {
            throw new IllegalStateException("UI fixture assembly failure");
        }
    }

    @Scheduled(period = 20)
    public void repeating() { /* Scheduling ownership, not timing, is under test. */ }

    @Override
    public boolean registerSelf() {
        if ("activation".equals(failurePoint)) {
            throw new IllegalStateException("UI fixture activation failure");
        }
        return !"refused".equals(failurePoint);
    }

    @Override
    public void onUnregister() {
        events.add("hook");
        hookObserver.run();
    }

    @PreDestroy
    public void destroyed() {
        events.add("destroy");
        destroyObserver.run();
    }
}
