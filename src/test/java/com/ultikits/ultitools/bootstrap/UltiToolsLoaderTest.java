package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;

class UltiToolsLoaderTest {
    @Test
    void classloader_registersExactlyTheFilteredLibrariesAndPaperMirror() {
        PluginClasspathBuilder builder = mock(PluginClasspathBuilder.class);
        try (MockedConstruction<MavenLibraryResolver> resolvers = mockConstruction(MavenLibraryResolver.class)) {
            new UltiToolsLoader().classloader(builder);
            assertThat(resolvers.constructed()).hasSize(1);
            MavenLibraryResolver resolver = resolvers.constructed().get(0);
            ArgumentCaptor<RemoteRepository> repository = ArgumentCaptor.forClass(RemoteRepository.class);
            verify(resolver).addRepository(repository.capture());
            assertThat(repository.getValue().getUrl()).isEqualTo(MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR);
            ArgumentCaptor<Dependency> dependencies = ArgumentCaptor.forClass(Dependency.class);
            verify(resolver, org.mockito.Mockito.times(5)).addDependency(dependencies.capture());
            assertThat(dependencies.getAllValues()).extracting(dependency -> dependency.getArtifact().getGroupId()
                    + ":" + dependency.getArtifact().getArtifactId() + ":" + dependency.getArtifact().getVersion())
                    .containsExactlyElementsOf(UltiToolsLoader.LIBRARIES);
            verify(builder).addLibrary(resolver);
        }
    }

    @Test
    void libraryResource_isImmutableAndIgnoresBlankLinesAndComments() {
        assertThat(UltiToolsLoader.readLibraries(new ByteArrayInputStream(
                "# comment\n\n g:a:1 \n".getBytes(StandardCharsets.UTF_8)))).containsExactly("g:a:1");
        assertThatThrownBy(() -> UltiToolsLoader.LIBRARIES.add("g:a:1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void libraryResource_missingOrUnreadableFailsLoudly() {
        assertThatThrownBy(() -> UltiToolsLoader.readLibraries(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
        InputStream unreadable = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("fixture read failure"); }
        };
        assertThatThrownBy(() -> UltiToolsLoader.readLibraries(unreadable))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
    }
}
