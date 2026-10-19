package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.linkageerror.configuration.ConfigurationLinkageSurvivor;
import com.ultikits.testfixtures.linkageerror.configuration.FailingInitializerConfiguration;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;

/**
 * A {@code @Configuration} class is created after discovery (decision P-07), outside the
 * per-class catch of the discovery walk. A {@link LinkageError} while creating it (a failing static
 * initializer, a {@code @Bean} signature naming a class of a missing optional plugin) must still
 * skip only that class, with the same scan diagnostics as a class that fails to load: a
 * {@code WARNING} naming it and one {@code SEVERE} module summary. The module keeps loading.
 */
@DisplayName("A @Configuration class that fails to link is skipped; the scan goes on")
class ComponentScannerConfigurationLinkageTest {

    private static final String PACKAGE = "com.ultikits.testfixtures.linkageerror.configuration";

    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    @BeforeEach
    void captureLogs() {
        Logger.getLogger(ComponentScanner.class.getName()).addHandler(capture);
        Logger.getLogger(ModuleScanDiagnostics.class.getName()).addHandler(capture);
    }

    @AfterEach
    void releaseLogs() {
        Logger.getLogger(ComponentScanner.class.getName()).removeHandler(capture);
        Logger.getLogger(ModuleScanDiagnostics.class.getName()).removeHandler(capture);
    }

    @Test
    @DisplayName("a failing static initializer skips that configuration class with WARNING and SEVERE summary; the service still registers")
    void configurationThatFailsToLinkIsSkipped() {
        SimpleContainer container = new SimpleContainer();
        container.setClassLoader(getClass().getClassLoader());
        String failing = FailingInitializerConfiguration.class.getName();

        Throwable thrown = catchThrowable(() -> container.scanComponents(PACKAGE));

        assertThat(thrown).as("the scan must not fail the module").isNull();
        assertThat(container.getBean(ConfigurationLinkageSurvivor.class)).as("the sibling service").isNotNull();
        assertThat(messagesAt(Level.WARNING)).as("WARNING records")
                .contains("Failed to load scanned class: " + failing);
        assertThat(messagesAt(Level.SEVERE)).as("SEVERE records")
                .anySatisfy(message -> assertThat(message)
                        .startsWith("Module '" + PACKAGE + "' skipped 1 class(es)")
                        .contains(failing));
    }

    private List<String> messagesAt(Level level) {
        return logged.stream().filter(record -> record.getLevel().equals(level))
                .map(LogRecord::getMessage).collect(Collectors.toList());
    }
}
