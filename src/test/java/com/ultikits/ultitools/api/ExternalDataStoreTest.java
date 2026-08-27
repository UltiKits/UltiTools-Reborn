package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

public class ExternalDataStoreTest {
    @TempDir
    File tempDir;

    private DataStore newStore() {
        return new DataStore() {
            @Override public String getStoreType() { return "test"; }
            @Override public <T extends BaseDataEntity<String>>
                DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                return null;
            }
            @Override public void destroyAllOperators() {}
        };
    }

    /**
     * D-18: an unresolvable data folder now fails closed (D-15) rather than falling straight
     * through to the "not supported" signal -- this is the behavior change Task 3 of 02-07
     * introduces. {@code tempDir} matches no registered external plugin scope.
     */
    @Test
    void defaultMethod_refusesUnregisteredFolder() {
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.findScopeForDataFolder(tempDir)).thenReturn(null);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getPluginManager()).thenReturn(pluginManager));

        DataStore store = newStore();

        assertThatThrownBy(() -> store.getOperator(tempDir, BaseDataEntity.class))
                .isInstanceOf(DataAccessException.class)
                .extracting(e -> ((DataAccessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);
    }

    /**
     * Once {@code tempDir} resolves to a registered scope that owns the entity, the ownership
     * check passes and the method reaches its original "not supported" signal -- unchanged from
     * before Task 3, since this default body genuinely has no storage implementation of its own.
     */
    @Test
    void defaultMethod_throwsUnsupportedOnceOwnershipCheckPasses() throws Exception {
        java.lang.reflect.Method forExternal = DataScope.class.getDeclaredMethod(
                "forExternal", String.class, File.class, java.util.Set.class);
        forExternal.setAccessible(true);
        DataScope scope = (DataScope) forExternal.invoke(
                null, "OwningPlugin", tempDir, java.util.Collections.singleton(BaseDataEntity.class));

        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.findScopeForDataFolder(tempDir)).thenReturn(scope);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getPluginManager()).thenReturn(pluginManager));

        DataStore store = newStore();

        assertThatThrownBy(() -> store.getOperator(tempDir, BaseDataEntity.class))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dataStoreInterface_hasNewMethod() {
        // Verify the method signature exists via compilation
        assertThat(DataStore.class.getMethods()).anyMatch(m ->
            m.getName().equals("getOperator") && m.getParameterCount() == 2
            && m.getParameterTypes()[0] == File.class
        );
    }
}
