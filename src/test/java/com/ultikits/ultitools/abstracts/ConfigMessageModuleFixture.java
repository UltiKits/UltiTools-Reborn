package com.ultikits.ultitools.abstracts;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.testfixtures.textwiring.TextFixtureModule;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mockito.MockedStatic;

/** Runs a real module constructor over a fixture jar and a scoped framework singleton. */
final class ConfigMessageModuleFixture implements AutoCloseable {
    private static final String MODULE = "DemoModule";
    private final Path rootFolder;
    private final Path moduleFolder;
    private final MockedStatic<UltiTools> singleton;
    private final SimpleContainer rootContext = new SimpleContainer();
    private final YamlConfiguration settings = new YamlConfiguration();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final ConfiguredLocaleResolver resolver;
    private final TextEngine engine;
    private URLClassLoader loader;

    ConfigMessageModuleFixture(Path rootFolder, ConfigManager configs, PluginManager plugins) throws IOException {
        this.rootFolder = rootFolder;
        moduleFolder = rootFolder.resolve("pluginConfig").resolve(MODULE);
        Logger logger = Logger.getAnonymousLogger();
        resolver = new ConfiguredLocaleResolver(() -> settings.getString("language"),
                () -> settings.getString("fallback-language"), logger);
        settings.set("language", "en");
        fallback("en");
        engine = new TextEngine(resolver, logger);
        rootContext.registerSingleton("textEngine", engine);
        var managers = mock(DependenceManagers.class);
        when(managers.getContext()).thenReturn(rootContext);
        var framework = mock(UltiTools.class);
        when(framework.getDependenceManagers()).thenReturn(managers);
        when(framework.getConfig()).thenReturn(settings);
        when(framework.getConfigManager()).thenReturn(configs);
        when(framework.getDataFolder()).thenReturn(rootFolder.toFile());
        when(framework.getPluginManager()).thenReturn(plugins);
        when(framework.getLogger()).thenReturn(logger);
        singleton = mockStatic(UltiTools.class, CALLS_REAL_METHODS);
        singleton.when(UltiTools::getInstance).thenReturn(framework);
    }

    void fallback(String language) throws IOException {
        settings.set("fallback-language", language);
        Files.writeString(rootFolder.resolve("config.yml"), "language: en\nfallback-language: " + language + "\n");
        resolver.reload();
    }

    ConfigMessageModuleFixture shipped(String locale, String yaml) {
        resources.put("messages/" + locale + ".yml", yaml.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    ConfigMessageModuleFixture onDisk(String path, String yaml) throws IOException {
        Path target = disk(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, yaml);
        return this;
    }

    Path disk(String path) {
        return moduleFolder.resolve(path);
    }

    UltiToolsPlugin construct() throws Exception {
        return construct(TextFixtureModule.class);
    }

    UltiToolsPlugin construct(Class<? extends UltiToolsPlugin> main, Class<?>... additionalClasses) throws Exception {
        if (loader == null) {
            resources.put("plugin.yml", ("name: " + MODULE + "\nversion: 1.0.0\napi-version: 700\nmain: "
                    + main.getName() + "\nsource-language: en\n").getBytes(StandardCharsets.UTF_8));
            addClass(main);
            for (Class<?> type : additionalClasses) {
                addClass(type);
            }
            Path jar = rootFolder.resolve("module.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
                for (var entry : resources.entrySet()) {
                    output.putNextEntry(new JarEntry(entry.getKey()));
                    output.write(entry.getValue());
                    output.closeEntry();
                }
            }
            loader = new JarFixtureLoader(jar.toUri().toURL());
        }
        return (UltiToolsPlugin) loader.loadClass(main.getName()).getConstructor().newInstance();
    }

    private void addClass(Class<?> type) throws IOException {
        String path = classEntry(type.getName());
        try (InputStream input = type.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing fixture class: " + path);
            }
            resources.put(path, input.readAllBytes());
        }
    }

    private static String classEntry(String name) {
        return name.replace('.', '/') + ".class";
    }

    private final class JarFixtureLoader extends URLClassLoader {
        private JarFixtureLoader(URL jar) {
            super(new URL[]{jar}, ConfigMessageModuleFixture.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!resources.containsKey(classEntry(name))) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }

    @Override
    public void close() throws IOException {
        try (var jar = loader; var framework = singleton) {
            rootContext.close();
        } finally {
            engine.close();
        }
    }
}
