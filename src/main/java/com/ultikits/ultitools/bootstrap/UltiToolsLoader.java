package com.ultikits.ultitools.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.ApiStatus;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;

/** Paper's runtime-library entry point. Internal; not an API for modules. */
@ApiStatus.Internal
public final class UltiToolsLoader implements PluginLoader {
    /** Filtered build resource, also checked against the compile dependencies by the build tests. */
    static final List<String> LIBRARIES = readLibraries(
            UltiToolsLoader.class.getResourceAsStream("/ultitools-libraries.txt"));

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder("central", "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        for (String coordinate : LIBRARIES) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        builder.addLibrary(resolver);
    }

    static List<String> readLibraries(InputStream input) {
        if (input == null) {
            throw new IllegalStateException("Missing ultitools-libraries.txt");
        }
        List<String> libraries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String coordinate = line.trim();
                if (!coordinate.isEmpty() && !coordinate.startsWith("#")) {
                    libraries.add(coordinate);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read ultitools-libraries.txt", failure);
        }
        return Collections.unmodifiableList(libraries);
    }
}
