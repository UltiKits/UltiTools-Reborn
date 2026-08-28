package com.ultikits.ultitools.context.isolationfixture.clean;

import com.ultikits.ultitools.annotations.Service;

/**
 * Fixture for {@code RequiredDependencyModuleIsolationTest}: a {@code @Service} with no
 * dependencies at all, proving a sibling container scanning this package registers and
 * instantiates normally after a different container's scan of the {@code .broken} package has
 * already failed.
 * <br>
 * 用于 {@code RequiredDependencyModuleIsolationTest} 的 fixture：一个完全没有依赖的
 * {@code @Service}，用来证明即使另一个容器对 {@code .broken} 包的扫描已经失败，扫描本包的同级
 * 容器仍能正常注册并实例化。
 */
@Service
public class CleanService {
    public String ping() {
        return "pong";
    }
}
