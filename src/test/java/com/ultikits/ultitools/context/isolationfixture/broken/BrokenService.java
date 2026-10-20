package com.ultikits.ultitools.context.isolationfixture.broken;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;

/**
 * Fixture for {@code RequiredDependencyModuleIsolationTest}: a {@code @Service} whose required
 * dependency ({@link UnregisteredDependency}) is never registered in the container. Scanning
 * this package and then refreshing the container must abort with {@code ContainerException}
 * rather than leave the field {@code null} (D-08).
 * <br>
 * 用于 {@code RequiredDependencyModuleIsolationTest} 的 fixture：一个必需依赖
 * （{@link UnregisteredDependency}）永远不会被注册到容器中的 {@code @Service}。扫描本包并刷新容器
 * 必须以 {@code ContainerException} 中止，而不是把字段留成 {@code null}（D-08）。
 */
@Service
public class BrokenService {
    @Autowired
    private UnregisteredDependency dependency;

    public UnregisteredDependency getDependency() {
        return dependency;
    }
}
