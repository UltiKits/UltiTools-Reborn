package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.DataScope;

public class ExternalDataStoreTest {
    @TempDir
    File tempDir;

    /** A third-party store that implements only the two abstract members. */
    private DataStore newStore() {
        return new DataStore() {
            @Override public String getStoreType() { return "test"; }
            @Override public void destroyAllOperators() {}
        };
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private DataScope externalScope(Class<?>... owned) throws Exception {
        java.lang.reflect.Method forExternal = DataScope.class.getDeclaredMethod(
                "forExternal", String.class, File.class, java.util.Set.class);
        forExternal.setAccessible(true);
        return (DataScope) forExternal.invoke(
                null, "OwningPlugin", tempDir, new java.util.HashSet<>(java.util.Arrays.asList(owned)));
    }

    /** An external scope that does not own the entity is refused before any construction. */
    @Test
    void defaultMethodRefusesUnownedEntity() throws Exception {
        DataScope scope = externalScope();
        DataStore store = newStore();

        assertThatThrownBy(() -> store.getOperator(scope, BaseDataEntity.class))
                .isInstanceOf(DataAccessException.class)
                .extracting(e -> ((DataAccessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);
    }

    /**
     * Once the scope owns the entity, the ownership check passes and the default body reaches the
     * "not supported" signal of the default {@code getOperatorUnchecked}, since this stub has no
     * storage implementation of its own.
     */
    @Test
    void defaultMethodThrowsUnsupportedOnceOwnershipCheckPasses() throws Exception {
        DataScope scope = externalScope(BaseDataEntity.class);
        DataStore store = newStore();

        assertThatThrownBy(() -> store.getOperator(scope, BaseDataEntity.class))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dataStoreInterface_hasNoFileOverload() {
        // The legacy getOperator(File, Class) overload was removed in 6.4.0.
        assertThat(DataStore.class.getMethods()).noneMatch(m ->
            m.getName().equals("getOperator") && m.getParameterCount() == 2
            && m.getParameterTypes()[0] == File.class
        );
    }
}
