package com.ultikits.testfixtures.registersingletonordering;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;

/**
 * A module main class carrying an {@code @Autowired} field pointing at a {@code @Service} in its
 * own scanned package -- the scenario {@code PluginManager.initializePlugin} must resolve
 * correctly by registering the plugin instance itself only AFTER component scanning (03-08, Task
 * 2, T-03-27). No {@code @UltiToolsModule} annotation is declared: {@code getPluginScanPackages}
 * falls back to this class's own package when neither {@code @UltiToolsModule} nor
 * {@code @ComponentScan} is present, which is exactly this package.
 * <br>
 * 一个在自身模块主类上携带 {@code @Autowired} 字段、指向自己扫描包内某个 {@code @Service}
 * 的 fixture——这正是 {@code PluginManager.initializePlugin} 必须正确处理的场景：只有在组件扫描
 * 之后才注册插件实例自身，才能让该字段解析成功（03-08，Task 2，T-03-27）。此类未声明
 * {@code @UltiToolsModule}：当既没有 {@code @UltiToolsModule} 也没有 {@code @ComponentScan} 时，
 * {@code getPluginScanPackages} 会回退到该类自身所在的包——正是本包。
 */
public class OrderingFixtureModule extends UltiToolsPlugin {

    @Autowired
    private OrderingFixtureService service;

    @Override
    public boolean registerSelf() {
        return true;
    }

    /**
     * @return the resolved {@code @Autowired} service, or {@code null} if it was never populated
     */
    public OrderingFixtureService getService() {
        return service;
    }
}
