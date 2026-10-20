package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import com.ultikits.testfixtures.economyattribution.ownpackage.OwnPackageModule;
import com.ultikits.testfixtures.economyattribution.ownpackage.commands.OwnPackageCaller;
import com.ultikits.testfixtures.economyattribution.unknownfirst.UnknownFirstCaller;
import com.ultikits.testfixtures.economyattribution.unknownsecond.UnknownSecondCaller;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginListSeeding;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * #489: a module whose declared scan roots do not cover its own main-class package is attributed
 * through that package (an implicit root, merged by the same longest-prefix and ambiguity rules as
 * a declared one), and two callers attribution cannot name each get their own warning instead of
 * sharing one "an unknown caller" slot.
 * <p>
 * Measured across the fifteen modules at their current {@code master}: 0 place the main class
 * outside their declared roots (11 declare {@code scanBasePackages} equal to the main-class
 * package; 4 declare none, which defaults to it) -- control: the same search found the
 * {@code scanBasePackages} declaration in those 11. The first half therefore protects third-party
 * modules; the second half applies to every unattributable caller.
 */
@DisplayName("#489: own-package attribution, and one warning per unattributable caller")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EconomyUtilsOwnPackageAttributionTest {

    private Logger logger;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        logger = mock(Logger.class);
        pluginManager = new PluginManager();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(logger);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        EconomyUtils.reset();
    }

    @AfterEach
    void tearDown() {
        EconomyUtils.reset();
        MockBukkitHelper.safeUnmock();
    }

    private List<String> warnings(int expected) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(logger, times(expected)).warning(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a caller in the module's own package, outside its declared root, is attributed to the module")
    void callerInOwnPackageOutsideDeclaredRootIsAttributed() {
        UltiToolsPlugin module = mock(OwnPackageModule.class);
        when(module.getPluginName()).thenReturn("OwnPackageModule");
        PluginListSeeding.add(pluginManager, module);

        OwnPackageCaller.requestEconomy();

        assertThat(warnings(1).get(0)).contains("Module 'OwnPackageModule'")
                .doesNotContain(EconomyUtils.UNKNOWN_MODULE);
    }

    @Test
    @DisplayName("two unattributable callers in different packages each get one warning")
    void twoUnattributableCallersEachGetAWarning() {
        UnknownFirstCaller.requestEconomy();
        UnknownSecondCaller.requestEconomy();
        UnknownFirstCaller.requestEconomy();

        List<String> warnings = warnings(2);
        assertThat(warnings).allSatisfy(message -> assertThat(message).contains(EconomyUtils.UNKNOWN_MODULE));
    }
}
