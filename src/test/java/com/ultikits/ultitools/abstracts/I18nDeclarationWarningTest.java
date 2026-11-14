package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.testfixtures.i18ndeclaration.Direct;
import com.ultikits.testfixtures.i18ndeclaration.Aliased;
import com.ultikits.testfixtures.i18ndeclaration.Both;
import com.ultikits.testfixtures.i18ndeclaration.DefaultAlias;
import com.ultikits.testfixtures.i18ndeclaration.Overridden;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.I18n;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/** #877: diagnose obsolete declarations against the module's own packaged resources. */
@SuppressWarnings("deprecation") // Deliberately exercises the retained compatibility declarations.
public class I18nDeclarationWarningTest {
    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;
    private URLClassLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        DependenceManagers dependencies = mock(DependenceManagers.class);
        when(dependencies.getContext()).thenReturn(new SimpleContainer());
        DataStore dataStore = mock(DataStore.class, CALLS_REAL_METHODS);
        fixture = BootLanguageFixture.create(tempDir, core -> {
            when(core.getDataFolder()).thenReturn(tempDir);
            when(core.getDataStore()).thenReturn(dataStore);
            when(core.getDependenceManagers()).thenReturn(dependencies);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (loader != null) {
            loader.close();
        }
        if (fixture != null) {
            fixture.close();
        }
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void directDeclarationWarnsOnceAtRegistrationAndListsEveryMissingLanguage() throws Exception {
        UltiToolsPlugin module = construct(Direct.class, "lang/en.json");
        verify(fixture.logger(), never()).warning(anyString());

        try (MockedStatic<UltiTools> core = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            core.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
            assertThat(new PluginManager().register(module)).isTrue();
        }
        module.commitLanguageProvenance();

        assertThat(warning()).contains("BootModule", "@I18n", "deprecated", "fr", "ja", "lang/")
                .doesNotContain("missing language resources: en");
        assertThat(module.supported()).containsExactly("en");
    }

    @Test
    void moduleAliasWarnsAboutMissingResources() throws Exception {
        UltiToolsPlugin module = construct(Aliased.class, "lang/en.json");
        module.commitLanguageProvenance();
        assertThat(warning()).contains("BootModule", "fr", "ja");
        assertThat(module.supported()).containsExactly("en");
    }

    @Test
    void bothDeclarationsAreAggregatedAndDeduplicated() throws Exception {
        UltiToolsPlugin module = construct(Both.class, "lang/en.json");
        module.commitLanguageProvenance();
        assertThat(warning()).contains("missing language resources: fr, ja");
    }

    @Test
    void completeDeclarationsRecognizeAllThreeExtensionsAndStaySilent() throws Exception {
        UltiToolsPlugin module = construct(Direct.class, "lang/en.json", "lang/fr.yml", "lang/ja.yaml");
        module.commitLanguageProvenance();
        verify(fixture.logger(), never()).warning(anyString());
        assertThat(module.supported()).containsExactly("en", "fr", "ja");
    }

    @Test
    void defaultEmptyAliasDoesNotProduceDeprecationNoise() throws Exception {
        UltiToolsPlugin module = construct(DefaultAlias.class, "lang/en.json");
        module.commitLanguageProvenance();
        verify(fixture.logger(), never()).warning(anyString());
        assertThat(module.supported()).containsExactly("en");
    }

    @Test
    void noDeclarationDoesNotProduceDeprecationNoise() throws Exception {
        UltiToolsPlugin module = construct(BootFixturePlugin.class, "lang/en.json");
        module.commitLanguageProvenance();
        verify(fixture.logger(), never()).warning(anyString());
        assertThat(module.supported()).containsExactly("en");
    }

    @Test
    void diskFilesAndSupportedOverridesCannotHideMissingJarResources() throws Exception {
        fixture.onDisk("lang/fr.json", "{}");
        UltiToolsPlugin module = construct(Overridden.class, "lang/en.json");
        module.commitLanguageProvenance();
        assertThat(warning()).contains("missing language resources: fr, ja");
        assertThat(module.supported()).containsExactly("en", "fr", "ja");
    }

    @Test
    void coreResourcesAndNestedFilesCannotSatisfyDeclarations() throws Exception {
        // The framework itself ships en.json, but this module does not.
        UltiToolsPlugin module = construct(Direct.class, "lang/nested/en.json");
        module.commitLanguageProvenance();
        assertThat(warning()).contains("missing language resources: en, fr, ja");
        assertThat(module.supported()).isEmpty();
    }

    @Test
    void aNewModuleInstanceGetsItsOwnWarning() throws Exception {
        UltiToolsPlugin first = construct(Direct.class, "lang/en.json");
        UltiToolsPlugin second = (UltiToolsPlugin) first.getClass().getConstructor(String.class, String.class)
                .newInstance("2.0.0", fixture.resourceFolder().getAbsolutePath());
        first.commitLanguageProvenance();
        first.commitLanguageProvenance();
        second.commitLanguageProvenance();
        verify(fixture.logger(), times(2)).warning(anyString());
        assertThat(second.supported()).containsExactly("en");
    }

    @Test
    void bothCompatibilityDeclarationsAreDeprecatedWithoutChangingTheirSignatures() throws Exception {
        Deprecated type = I18n.class.getAnnotation(Deprecated.class);
        Deprecated alias = UltiToolsModule.class.getMethod("i18n").getAnnotation(Deprecated.class);
        assertThat(type).isNotNull();
        assertThat(alias).isNotNull();
        assertThat(type.since()).isEqualTo("6.5.0");
        assertThat(alias.since()).isEqualTo("6.5.0");
        assertThat(type.forRemoval()).isTrue();
        assertThat(alias.forRemoval()).isTrue();
        assertThat(I18n.class.getMethod("value").getReturnType()).isEqualTo(String[].class);
        assertThat(UltiToolsModule.class.getMethod("i18n").getReturnType()).isEqualTo(String[].class);
    }

    private String warning() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(fixture.logger(), atLeastOnce()).warning(message.capture());
        List<String> declarations = message.getAllValues().stream()
                .filter(line -> line.contains("@I18n")).collect(Collectors.toList());
        assertThat(declarations).hasSize(1);
        return declarations.get(0);
    }

    private UltiToolsPlugin construct(Class<? extends UltiToolsPlugin> type, String... resourceNames)
            throws Exception {
        String classEntry = type.getName().replace('.', '/') + ".class";
        File jar = new File(tempDir, "module.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()));
                InputStream compiled = type.getClassLoader().getResourceAsStream(classEntry)) {
            assertThat(compiled).isNotNull();
            out.putNextEntry(new JarEntry(classEntry));
            byte[] buffer = new byte[4096];
            int read;
            while ((read = compiled.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.closeEntry();
            for (String resource : resourceNames) {
                out.putNextEntry(new JarEntry(resource));
                out.write("{}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(type.getName())) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
        return (UltiToolsPlugin) loader.loadClass(type.getName()).getConstructor(String.class, String.class)
                .newInstance("1.0.0", fixture.resourceFolder().getAbsolutePath());
    }

}
