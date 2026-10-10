package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;

/** Keeps timeout-specific JDBC plumbing out of the public framework API. */
class TimeoutAwareQueryRunnerVisibilityTest {

    @Test
    void isNotPublicApi() {
        assertThat(Modifier.isPublic(TimeoutAwareQueryRunner.class.getModifiers())).isFalse();
    }

    @Test
    void noPublicFrameworkMemberExposesIt() {
        Class<?>[] operators = {
                AbstractRelationalDataOperator.class, MysqlDataOperator.class, SQLiteDataOperator.class
        };
        for (Class<?> operator : operators) {
            // Walk the hierarchy so inherited protected members are checked as well as public ones.
            for (Class<?> type = operator; type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (isPublicOrProtected(field.getModifiers())) {
                        assertThat(field.getType()).as("field %s", field)
                                .isNotEqualTo(TimeoutAwareQueryRunner.class);
                    }
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (isPublicOrProtected(method.getModifiers())) {
                        assertThat(method.getReturnType()).as("return type of %s", method)
                                .isNotEqualTo(TimeoutAwareQueryRunner.class);
                        assertThat(method.getParameterTypes()).as("parameters of %s", method)
                                .doesNotContain(TimeoutAwareQueryRunner.class);
                    }
                }
            }
        }
    }

    private static boolean isPublicOrProtected(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
