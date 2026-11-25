package com.ultikits.ultitools.utils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleContentLedgersTest {

    @AfterEach
    void restoreDefaultLedger() {
        ModuleContentLedgers.install(ModuleContentLedger.NONE);
    }

    @Test
    void defaultsToTheNoneInstance() {
        assertThat(ModuleContentLedgers.current()).isSameAs(ModuleContentLedger.NONE);
        assertThat(ModuleContentLedgers.contentOf("module.id", "ModuleName")).isSameAs(ModuleContent.NONE);
        assertThat(ModuleContentLedger.NONE.contentOf(null, "OtherModule")).isSameAs(ModuleContent.NONE);
    }

    @Test
    void delegatesBothArgumentsToTheInstalledLedger() {
        var receivedIdentifier = new AtomicReference<String>();
        var receivedName = new AtomicReference<String>();
        var expected = new ModuleContent(List.of(new ModuleContentKind("tag", 2)));
        ModuleContentLedger installed = (identifyString, moduleName) -> {
            receivedIdentifier.set(identifyString);
            receivedName.set(moduleName);
            return expected;
        };

        ModuleContentLedgers.install(installed);

        assertThat(ModuleContentLedgers.current()).isSameAs(installed);
        assertThat(ModuleContentLedgers.contentOf("module.id", "ModuleName")).isSameAs(expected);
        assertThat(receivedIdentifier.get()).isEqualTo("module.id");
        assertThat(receivedName.get()).isEqualTo("ModuleName");
    }

    @Test
    void delegatesAnUnknownIdentifierWithoutNormalizingTheModuleName() {
        var receivedIdentifier = new AtomicReference<String>("not_called");
        var receivedName = new AtomicReference<String>();
        ModuleContentLedgers.install((identifyString, moduleName) -> {
            receivedIdentifier.set(identifyString);
            receivedName.set(moduleName);
            return ModuleContent.NONE;
        });

        assertThat(ModuleContentLedgers.contentOf(null, "UltiTools-Chat")).isSameAs(ModuleContent.NONE);
        assertThat(receivedIdentifier.get()).isNull();
        assertThat(receivedName.get()).isEqualTo("UltiTools-Chat");
    }

    @Test
    void answersUnknownWhenTheLedgerThrows() {
        ModuleContentLedgers.install((identifyString, moduleName) -> {
            throw new IllegalStateException("Unreadable content record");
        });

        assertThat(ModuleContentLedgers.contentOf("module.id", "ModuleName"))
                .isEqualTo(ModuleContent.unknown());
    }

    @Test
    void answersUnknownWhenTheLedgerReturnsNull() {
        ModuleContentLedgers.install((identifyString, moduleName) -> null);

        assertThat(ModuleContentLedgers.contentOf("module.id", "ModuleName"))
                .isEqualTo(ModuleContent.unknown());
    }

    @Test
    void rejectsANullLedgerWithoutReplacingTheCurrentOne() {
        ModuleContentLedger installed = (identifyString, moduleName) -> ModuleContent.unknown();
        ModuleContentLedgers.install(installed);

        assertThatThrownBy(() -> ModuleContentLedgers.install(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(ModuleContentLedgers.current()).isSameAs(installed);
    }

    @Test
    void installingNoneRestoresTheDefault() {
        ModuleContentLedgers.install((identifyString, moduleName) -> ModuleContent.unknown());
        ModuleContentLedgers.install(ModuleContentLedger.NONE);

        assertThat(ModuleContentLedgers.current()).isSameAs(ModuleContentLedger.NONE);
        assertThat(ModuleContentLedgers.contentOf(null, "ModuleName")).isSameAs(ModuleContent.NONE);
    }

    @Test
    void doesNotHideErrors() {
        var failure = new AssertionError("Fatal ledger failure");
        ModuleContentLedgers.install((identifyString, moduleName) -> {
            throw failure;
        });

        assertThatThrownBy(() -> ModuleContentLedgers.contentOf(null, "ModuleName"))
                .isSameAs(failure);
    }
    @Test
    void nullModuleNameIsRejectedBeforeCallingTheLedger() {
        ModuleContentLedgers.install((identify, name) -> { throw new AssertionError("ledger must not be called"); });
        assertThatThrownBy(() -> ModuleContentLedgers.contentOf("identify", null))
                .isInstanceOf(NullPointerException.class).hasMessage("moduleName");
    }
}
