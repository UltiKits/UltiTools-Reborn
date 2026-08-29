package com.ultikits.testfixtures.registrationparity;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.PostConstruct;

/**
 * A module main class carrying, in one place, every capability
 * {@code PluginManagerRegistrationParityTest} exercises (WIRE-05/WIRE-06):
 * <ul>
 *   <li>{@code @ContextEntry(ParityContextBean.class)} -- difference #1</li>
 *   <li>an {@code @Autowired} field pointing at {@link ParityFixtureService}, its own scanned
 *       {@code @Service} -- proves the shared component scan still runs on both entry points</li>
 *   <li>a {@code private static instance} field -- difference #3
 *       ({@code setPluginStaticInstance})</li>
 *   <li>a {@code @PostConstruct} method that records whether {@code getContext()} was non-null
 *       when it ran -- difference #5 (the {@code setContext}-before-{@code refresh()}
 *       decision)</li>
 * </ul>
 * No {@code @UltiToolsModule} annotation is declared: {@code getPluginScanPackages} falls back to
 * this class's own package when neither {@code @UltiToolsModule} nor {@code @ComponentScan} is
 * present, which is exactly this package.
 * <br>
 * 一个模块主类，把 {@code PluginManagerRegistrationParityTest}（WIRE-05/WIRE-06）要验证的每一项
 * 能力都合并在一处：{@code @ContextEntry}（差异 #1）、一个指向自身扫描到的 {@code @Service} 的
 * {@code @Autowired} 字段（证明共享组件扫描在两个入口点上都仍然运行）、一个
 * {@code private static} instance 字段（差异 #3）、以及一个记录 {@code @PostConstruct} 运行时
 * {@code getContext()} 是否非空的方法（差异 #5）。
 */
@ContextEntry(ParityContextBean.class)
public class ParityFixtureModule extends UltiToolsPlugin {

    private static ParityFixtureModule instance;

    @Autowired
    private ParityFixtureService service;

    private boolean contextNonNullDuringPostConstruct;

    @PostConstruct
    void recordContextDuringPostConstruct() {
        contextNonNullDuringPostConstruct = getContext() != null;
    }

    @Override
    public boolean registerSelf() {
        return true;
    }

    /**
     * @return the resolved {@code @Autowired} service, or {@code null} if it was never populated
     */
    public ParityFixtureService getService() {
        return service;
    }

    /**
     * @return whether {@code getContext()} returned non-null when the {@code @PostConstruct}
     *         method above ran
     */
    public boolean wasContextNonNullDuringPostConstruct() {
        return contextNonNullDuringPostConstruct;
    }

    /**
     * @return whatever {@code PluginManager.setPluginStaticInstance} set this field to, or
     *         {@code null} if it was never set
     */
    public static ParityFixtureModule getInstance() {
        return instance;
    }
}
