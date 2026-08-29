package com.ultikits.testfixtures.containerisolation.beta;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;

/**
 * Records, at {@code @PostConstruct} time, exactly which bean names its own container could see.
 * That snapshot is the evidence UAT-04 needs: it is taken DURING assembly, not after it, so a
 * bean leaking in from a concurrently- or previously-assembled module would be captured here.
 * <br>
 * 在 {@code @PostConstruct} 时刻记录自身容器当时可见的 bean 名称快照。快照取自装配**过程中**
 * 而非装配完成后，因此其他模块（并发或先前装配）泄漏进来的 bean 会被这份快照捕获。
 */
public class IsolationBetaModule extends UltiToolsPlugin {

    private static IsolationBetaModule instance;

    @Autowired
    private IsolationBetaService service;

    private List<String> beanNamesDuringPostConstruct = Collections.emptyList();

    @PostConstruct
    void snapshotVisibleBeans() {
        if (getContext() != null) {
            beanNamesDuringPostConstruct = Arrays.asList(getContext().getBeanDefinitionNames());
        }
    }

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** @return the bean names this module's own container exposed while its {@code @PostConstruct} ran */
    public List<String> getBeanNamesDuringPostConstruct() {
        return beanNamesDuringPostConstruct;
    }

    /** @return this module's own scanned service, or {@code null} if injection never happened */
    public IsolationBetaService getService() {
        return service;
    }

    public static IsolationBetaModule getInstance() {
        return instance;
    }
}
