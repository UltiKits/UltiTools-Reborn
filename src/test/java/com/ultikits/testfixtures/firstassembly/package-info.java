/**
 * Fixtures for {@code PluginManagerFirstAssemblyTest} and {@code ComponentScannerDiscoveryOrderTest}
 * (Phase 20.4, plan 03): the first assembly of a module container waits for everything it needs.
 * <p>
 * One sub-package per scenario. Each sub-package holds exactly one top-level module class; that
 * module scans only its own sub-package, and every bean, entity or configuration class the
 * scenario needs is a static nested class of the module class, so no scenario can leak into
 * another:
 * <ul>
 *   <li>{@code aop} -- {@code AopReadyFixtureModule}: the module main class {@code @Autowired}s an
 *       {@code @ExceptionCatch} service and a {@code @Transactional} service; both must be AOP
 *       proxies (C08). Used by {@code PluginManagerFirstAssemblyTest.AopAndConfigurationReadyTests}.</li>
 *   <li>{@code config} -- {@code ConfigReadyFixtureModule}: a service that needs a module config
 *       entity, and a service whose {@code @PostConstruct} observes the module's data scope and
 *       container (C09). Used by {@code PluginManagerFirstAssemblyTest.AopAndConfigurationReadyTests}.</li>
 *   <li>{@code factory} -- {@code FactoryOrderFixtureModule}: a {@code @Configuration} whose
 *       {@code @Bean} product needs a {@code @Service} (C51). Used by
 *       {@code ComponentScannerDiscoveryOrderTest} and
 *       {@code PluginManagerFirstAssemblyTest.FactoryProductDiscoveryTests}.</li>
 *   <li>{@code contextentry} -- {@code ContextEntryFixtureModule}: a {@code @ContextEntry} bean
 *       whose {@code @PostConstruct} registers a tab completer (C62). Used by
 *       {@code PluginManagerFirstAssemblyTest.ContextEntryScopeTests}.</li>
 *   <li>{@code contextentryrefused} -- {@code RefusedContextEntryFixtureModule}: the same shape,
 *       with a {@code registerSelf()} that returns {@code false} (C62). Used by
 *       {@code PluginManagerFirstAssemblyTest.ContextEntryScopeTests}.</li>
 * </ul>
 * The module classes are packaged into a throwaway JAR at test time and loaded through an
 * isolated class loader, because {@code UltiToolsPlugin}'s no-argument constructor reads
 * {@code plugin.yml} from its own class's {@code CodeSource}, which must be a real JAR file.
 */
package com.ultikits.testfixtures.firstassembly;
