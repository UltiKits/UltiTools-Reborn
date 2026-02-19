package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;

public class ExternalDataStoreTest {
    @TempDir
    File tempDir;

    @Test
    void defaultMethod_throwsUnsupported() {
        DataStore store = new DataStore() {
            @Override public String getStoreType() { return "test"; }
            @Override public <T extends BaseDataEntity<String>>
                DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                return null;
            }
            @Override public void destroyAllOperators() {}
        };
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
